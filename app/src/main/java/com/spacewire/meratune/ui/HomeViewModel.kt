package com.spacewire.meratune.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.AnalyticsTrigger
import com.spacewire.meratune.analytics.FilterSelection
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.Category
import com.spacewire.meratune.data.HomeRepository
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.ActiveRingtoneStore
import com.spacewire.meratune.util.LoadErrorMapper
import kotlinx.coroutines.CancellationException
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

    /** Selected chip's category name; `null` for All. */
    val selectedCategoryName: String?
        get() = selectedCategoryId
            ?.takeIf { it != Category.ALL_CATEGORY_ID }
            ?.let { id -> categories.firstOrNull { it.id == id }?.name }

    /** 1-based position of [tuneId] in [filteredTunes]; `null` when it is not listed. */
    fun rankOf(tuneId: String): Int? =
        filteredTunes.indexOfFirst { it.id == tuneId }.takeIf { it >= 0 }?.plus(1)
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
    private var pendingSearchQuery: String? = null
    private val createdAtMs = SystemClock.elapsedRealtime()
    private var homeViewTracked = false

    init {
        loadHomeData(AnalyticsTrigger.INITIAL)
    }

    /** Error-text retry; ignored while a load is already running (double tap). */
    fun retryLoad() {
        if (_uiState.value.isLoading) return
        loadHomeData(AnalyticsTrigger.RETRY)
    }

    private fun loadHomeData(trigger: String) {
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
                    loadTunes(categoryIdForFetch(_uiState.value.selectedCategoryId), trigger)
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoadingCategories = false,
                            isLoadingTunes = false,
                            errorMessage = mapLoadError(error),
                        )
                    }
                    trackLoadFailed(STAGE_CATEGORIES, error, trigger)
                }
        }
    }

    fun refreshActiveRingtone() {
        // Before the empty-list return: the check does not depend on the loaded tunes.
        activeRingtoneStore.consumeReplacedRingtone(getApplication())?.let {
            analytics.trackRingtoneReplacedExternally(it)
        }
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
        val state = _uiState.value
        val currentCategoryId = state.selectedCategoryId ?: Category.ALL_CATEGORY_ID
        val nextCategoryId = when {
            categoryId == Category.ALL_CATEGORY_ID -> Category.ALL_CATEGORY_ID
            currentCategoryId == categoryId -> Category.ALL_CATEGORY_ID
            else -> categoryId
        }
        if (nextCategoryId != currentCategoryId) {
            trackCategoryFiltered(state.categories, categoryId, currentCategoryId, nextCategoryId)
        }
        _uiState.update { it.copy(selectedCategoryId = nextCategoryId) }
        loadTunes(categoryIdForFetch(nextCategoryId), AnalyticsTrigger.CATEGORY_CHANGE)
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
        pendingSearchQuery = null
        if (query.isBlank()) return

        pendingSearchQuery = query
        searchTrackingJob = viewModelScope.launch {
            delay(SEARCH_TRACK_DEBOUNCE_MS)
            trackPendingSearch()
        }
    }

    /** Sends a still-debouncing `search_performed` now, so it precedes whatever the user does next. */
    fun flushPendingSearchTracking() {
        searchTrackingJob?.cancel()
        searchTrackingJob = null
        trackPendingSearch()
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
        loadTunes(categoryIdForFetch(Category.ALL_CATEGORY_ID), AnalyticsTrigger.RESET)
    }

    fun onPlayToggle(tuneId: String) {
        _uiState.update { state ->
            state.copy(
                playingTuneId = if (state.playingTuneId == tuneId) null else tuneId,
            )
        }
    }

    private fun loadTunes(categoryId: String?, trigger: String) {
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
                    trackHomeViewedOnce()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isLoadingTunes = false,
                            errorMessage = mapLoadError(error),
                        )
                    }
                    trackLoadFailed(STAGE_TUNES, error, trigger)
                }
        }
    }

    /** Once per ViewModel (survives rotation); `load_ms` is time from Home creation to first content. */
    private fun trackHomeViewedOnce() {
        val state = _uiState.value
        if (homeViewTracked || state.categories.isEmpty()) return
        homeViewTracked = true
        analytics.trackHomeViewed(
            tuneCount = state.tunes.size,
            categoryCount = state.categories.size,
            loadMs = SystemClock.elapsedRealtime() - createdAtMs,
            hasActiveRingtone = state.activeRingtoneId != null,
        )
    }

    private fun trackLoadFailed(stage: String, error: Throwable, trigger: String) {
        analytics.trackHomeLoadFailed(
            stage = stage,
            failureReason = LoadErrorMapper.reason(error),
            trigger = trigger,
        )
    }

    private fun trackPendingSearch() {
        val query = pendingSearchQuery ?: return
        pendingSearchQuery = null
        val state = _uiState.value
        analytics.trackSearchPerformed(
            queryLength = query.trim().length,
            resultCount = state.filteredTunes.size,
            categoryFilter = state.selectedCategoryName,
        )
    }

    private fun trackCategoryFiltered(
        categories: List<Category>,
        tappedCategoryId: String,
        currentCategoryId: String,
        nextCategoryId: String,
    ) {
        val (selection, categoryId) = when {
            nextCategoryId != Category.ALL_CATEGORY_ID -> FilterSelection.SELECTED to nextCategoryId
            tappedCategoryId == Category.ALL_CATEGORY_ID -> FilterSelection.ALL to null
            else -> FilterSelection.DESELECTED to currentCategoryId
        }
        analytics.trackCategoryFiltered(
            categoryId = categoryId,
            categoryName = categoryId?.let { id -> categories.firstOrNull { it.id == id }?.name },
            source = AnalyticsSource.HOME,
            selection = selection,
        )
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

    private fun mapLoadError(error: Throwable): String = LoadErrorMapper.message(error)

    private companion object {
        const val SEARCH_TRACK_DEBOUNCE_MS = 500L
        const val STAGE_CATEGORIES = "categories"
        const val STAGE_TUNES = "tunes"
    }
}
