package com.spacewire.meratune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.Category
import com.spacewire.meratune.data.HomeRepository
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.ActiveRingtoneStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val categories: List<Category> = emptyList(),
    val tunes: List<Tune> = emptyList(),
    val filteredTunes: List<Tune> = emptyList(),
    val selectedCategoryId: String? = Category.ALL_CATEGORY_ID,
    val searchQuery: String = "",
    val isLoadingCategories: Boolean = true,
    val isLoadingTunes: Boolean = false,
    val errorMessage: String? = null,
    val playingTuneId: String? = null,
    val activeRingtoneId: String? = null,
) {
    val isLoading: Boolean
        get() = isLoadingCategories || isLoadingTunes

    val showSearchEmptyState: Boolean
        get() = searchQuery.isNotBlank() &&
            filteredTunes.isEmpty() &&
            !isLoading &&
            errorMessage == null
}

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val repository = HomeRepository()
    private val activeRingtoneStore = ActiveRingtoneStore(application)
    private val analytics = application.mixpanelAnalytics()
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var searchTrackingJob: Job? = null

    init {
        loadHomeData()
    }

    fun loadHomeData() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingCategories = true,
                    isLoadingTunes = true,
                    errorMessage = null,
                )
            }

            runCatching { repository.fetchActiveCategories() }
                .onSuccess { categories ->
                    _uiState.update {
                        it.copy(
                            categories = withAllCategory(categories),
                            isLoadingCategories = false,
                        )
                    }
                    loadTunes(categoryIdForFetch(_uiState.value.selectedCategoryId))
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingCategories = false,
                            isLoadingTunes = false,
                            errorMessage = mapLoadError(error),
                        )
                    }
                }
        }
    }

    fun refreshActiveRingtone() {
        val tunes = _uiState.value.tunes
        if (tunes.isEmpty()) return

        val activeId = activeRingtoneStore.resolveActiveTuneId(getApplication(), tunes)
        updateFilteredTunes(activeRingtoneId = activeId)
    }

    fun onRingtoneSet(tuneId: String, ringtoneUri: android.net.Uri) {
        activeRingtoneStore.save(tuneId, ringtoneUri)
        updateFilteredTunes(activeRingtoneId = tuneId)
    }

    fun onCategorySelected(categoryId: String) {
        val nextCategoryId = when {
            categoryId == Category.ALL_CATEGORY_ID -> Category.ALL_CATEGORY_ID
            _uiState.value.selectedCategoryId == categoryId -> Category.ALL_CATEGORY_ID
            else -> categoryId
        }
        if (nextCategoryId != Category.ALL_CATEGORY_ID) {
            val categoryName = _uiState.value.categories
                .firstOrNull { it.id == nextCategoryId }
                ?.name
                .orEmpty()
            if (categoryName.isNotBlank()) {
                analytics.trackCategoryFiltered(nextCategoryId, categoryName)
            }
        }
        _uiState.update { it.copy(selectedCategoryId = nextCategoryId) }
        loadTunes(categoryIdForFetch(nextCategoryId))
    }

    fun onSearchQueryChanged(query: String) {
        val filteredTunes = buildFilteredTunes(
            _uiState.value.tunes,
            query,
            _uiState.value.activeRingtoneId,
        )
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredTunes = filteredTunes,
            )
        }

        searchTrackingJob?.cancel()
        if (query.isBlank()) return

        searchTrackingJob = viewModelScope.launch {
            delay(SEARCH_TRACK_DEBOUNCE_MS)
            analytics.trackSearchPerformed(
                queryLength = query.length,
                resultCount = filteredTunes.size,
            )
        }
    }

    fun resetToAllTunes() {
        val state = _uiState.value
        if (state.searchQuery.isBlank() &&
            state.selectedCategoryId == Category.ALL_CATEGORY_ID &&
            state.playingTuneId == null
        ) {
            updateFilteredTunes(activeRingtoneId = state.activeRingtoneId)
            return
        }

        _uiState.update {
            it.copy(
                searchQuery = "",
                selectedCategoryId = Category.ALL_CATEGORY_ID,
                playingTuneId = null,
            )
        }
        loadTunes(categoryIdForFetch(Category.ALL_CATEGORY_ID))
    }

    fun onPlayToggle(tuneId: String) {
        _uiState.update { state ->
            state.copy(
                playingTuneId = if (state.playingTuneId == tuneId) null else tuneId,
            )
        }
    }

    private fun loadTunes(categoryId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTunes = true, errorMessage = null) }

            runCatching { repository.fetchActiveTunes(categoryId) }
                .onSuccess { tunes ->
                    val activeId = activeRingtoneStore.resolveActiveTuneId(getApplication(), tunes)
                    _uiState.update { state ->
                        state.copy(
                            tunes = tunes,
                            activeRingtoneId = activeId,
                            filteredTunes = buildFilteredTunes(tunes, state.searchQuery, activeId),
                            isLoadingTunes = false,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingTunes = false,
                            errorMessage = mapLoadError(error),
                        )
                    }
                }
        }
    }

    private fun updateFilteredTunes(activeRingtoneId: String?) {
        _uiState.update { state ->
            state.copy(
                activeRingtoneId = activeRingtoneId,
                filteredTunes = buildFilteredTunes(state.tunes, state.searchQuery, activeRingtoneId),
            )
        }
    }

    private fun buildFilteredTunes(
        tunes: List<Tune>,
        query: String,
        activeRingtoneId: String?,
    ): List<Tune> {
        return sortWithActiveFirst(applySearchFilter(tunes, query), activeRingtoneId)
    }

    private fun sortWithActiveFirst(tunes: List<Tune>, activeRingtoneId: String?): List<Tune> {
        val activeTune = activeRingtoneId?.let { id -> tunes.find { it.id == id } } ?: return tunes
        return listOf(activeTune) + tunes.filter { it.id != activeRingtoneId }
    }

    private fun withAllCategory(categories: List<Category>) = listOf(
        Category(id = Category.ALL_CATEGORY_ID, name = "All"),
    ) + categories

    private fun categoryIdForFetch(selectedCategoryId: String?) =
        if (selectedCategoryId == Category.ALL_CATEGORY_ID) null else selectedCategoryId

    private fun applySearchFilter(tunes: List<Tune>, query: String): List<Tune> {
        if (query.isBlank()) return tunes
        return tunes.filter { tune ->
            tune.name.contains(query, ignoreCase = true)
        }
    }

    private fun mapLoadError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("timeout", ignoreCase = true) ->
                "Could not reach the server. Check your internet connection and try again."
            message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("UnknownHostException", ignoreCase = true) ->
                "No internet connection. Please check your network and try again."
            else -> error.message ?: "Failed to load data"
        }
    }

    private companion object {
        const val SEARCH_TRACK_DEBOUNCE_MS = 500L
    }
}
