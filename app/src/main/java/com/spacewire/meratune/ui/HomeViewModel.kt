package com.spacewire.meratune.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.AnalyticsTrigger
import com.spacewire.meratune.analytics.FilterSelection
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.Category
import com.spacewire.meratune.data.GenerationQuota
import com.spacewire.meratune.data.HomeRepository
import com.spacewire.meratune.data.NameRingtonesRepository
import com.spacewire.meratune.data.RingtoneGenerationException
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.ActiveRingtoneStore
import com.spacewire.meratune.util.LoadErrorMapper
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.TuneStatsUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class HomeUiState(
    val categories: List<Category> = emptyList(),
    val tunes: List<Tune> = emptyList(),
    val filteredTunes: List<Tune> = emptyList(),
    val selectedCategoryId: String? = Category.ALL_CATEGORY_ID,
    val searchQuery: String = "",
    val isLoadingCategories: Boolean = true,
    val isLoadingTunes: Boolean = false,
    val errorMessage: String? = null,
    /** [Tune.rowKey] of the previewing row (the tune id for a catalog tune). */
    val playingTuneId: String? = null,
    /** [Tune.rowKey] of the row that is the system ringtone (the tune id for a catalog tune). */
    val activeRingtoneId: String? = null,
    /** The profile's first name for the "{name} Tunes" chip; blank when the profile has none. */
    val profileFirstName: String = "",
    /** The user's own ready ringtones, newest first; `null` until the first successful load. */
    val myRingtones: List<Tune>? = null,
    val isLoadingMyRingtones: Boolean = false,
    /**
     * The creation quota of the last successful own-ringtones load (`null` before one, or when that
     * response had none); a failed refresh keeps it.
     */
    val creationQuota: GenerationQuota? = null,
) {
    val isLoading: Boolean
        get() = isLoadingCategories || isLoadingTunes

    /**
     * The synthetic "{name} Tunes" chip is selected: the user's own ringtones, then every active
     * tune filtered by the name (see [HomeTuneFilter.nameTab]).
     */
    val isMyNameSelected: Boolean
        get() = selectedCategoryId == Category.MY_NAME_CATEGORY_ID

    /** The name chip waits for the first load of the user's own ringtones. */
    val isAwaitingMyRingtones: Boolean
        get() = isMyNameSelected && isLoadingMyRingtones && myRingtones == null

    /** The progress indicator: a Home load, or an empty name tab waiting for the user's ringtones. */
    val showLoadingIndicator: Boolean
        get() = isLoading || (isAwaitingMyRingtones && filteredTunes.isEmpty())

    /** No tune for a search query or for the name chip: the create empty state instead of the list. */
    val showSearchEmptyState: Boolean
        get() = (searchQuery.isNotBlank() || isMyNameSelected) &&
            filteredTunes.isEmpty() &&
            !isLoading &&
            !isAwaitingMyRingtones &&
            errorMessage == null

    /**
     * The floating "Make {name} tune" CTA over a non-empty "{name} Tunes" list (the empty state has
     * its own CTA).
     */
    val showNameTabCreateCta: Boolean
        get() = isMyNameSelected && filteredTunes.isNotEmpty() && !showLoadingIndicator && errorMessage == null

    /** The user's newest own ringtone (the limit sheet's card); `null` before a load or without one. */
    val latestOwnRingtone: Tune?
        get() = myRingtones?.firstOrNull { it.generationId != null }

    /** The empty state's name (see [HomeTuneFilter.emptyStateName]); blank uses the generic copy. */
    val emptyStateName: String
        get() = HomeTuneFilter.emptyStateName(searchQuery, isMyNameSelected, profileFirstName)

    /** Selected chip's category name (`my_name` for the name chip); `null` for All. */
    val selectedCategoryName: String?
        get() = selectedCategoryId
            ?.takeIf { it != Category.ALL_CATEGORY_ID }
            ?.let { id -> categories.firstOrNull { it.id == id }?.name }

    /** 1-based position of the [rowKey] row ([Tune.rowKey]) in [filteredTunes]; `null` when it is not listed. */
    fun rankOf(rowKey: String): Int? =
        filteredTunes.indexOfFirst { it.rowKey == rowKey }.takeIf { it >= 0 }?.plus(1)
}

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val repository = HomeRepository()
    private val nameRingtonesRepository = NameRingtonesRepository(application)
    private val activeRingtoneStore = ActiveRingtoneStore(application)
    private val profileStore = ProfileStore(application)
    private val analytics = application.mixpanelAnalytics()
    private val _uiState = MutableStateFlow(HomeUiState(profileFirstName = readProfileFirstName()))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var searchTrackingJob: Job? = null
    private var pendingSearchQuery: String? = null
    private val createdAtMs = SystemClock.elapsedRealtime()
    private var homeViewTracked = false
    private var myRingtonesJob: Job? = null

    /** The locally saved personalized ringtone for the name tab (re-read on resume and after a set). */
    private var savedPersonalizedTune: Tune? = readSavedPersonalizedTune()

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
        savedPersonalizedTune = readSavedPersonalizedTune()
        val state = _uiState.value
        if (state.tunes.isEmpty() && state.myRingtones == null) return

        updateFilteredTunes(activeRingtoneId = resolveActiveKey(state.tunes, state.myRingtones))
    }

    /**
     * Reloads the user's own ringtones for the name chip and their creation quota (Home resumes
     * after the create flow). A failure keeps the last loaded list and quota; before any, the chip
     * lists the saved personalized one.
     */
    fun refreshMyRingtones() {
        if (myRingtonesJob?.isActive == true) return
        myRingtonesJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMyRingtones = true) }
            val loaded = try {
                nameRingtonesRepository.fetchMyRingtones()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val reason = (error as? RingtoneGenerationException)?.code?.name ?: error.javaClass.simpleName
                Log.w(TAG, "My ringtones unavailable: $reason")
                null
            }
            val current = _uiState.value
            val myRingtones = loaded?.ringtones ?: current.myRingtones
            val activeId = resolveActiveKey(current.tunes, myRingtones)
            _uiState.update { state ->
                state.copy(
                    myRingtones = myRingtones,
                    isLoadingMyRingtones = false,
                    activeRingtoneId = activeId,
                    creationQuota = if (loaded != null) loaded.quota else state.creationQuota,
                ).withFilteredTunes()
            }
        }
    }

    /** An own-ringtones (and quota) refresh is in flight. */
    val isRefreshingMyRingtones: Boolean
        get() = myRingtonesJob?.isActive == true

    /**
     * The creation quota for a create CTA tap: waits up to [QUOTA_WAIT_MS] for a running
     * own-ringtones refresh (Home resumed just now), then returns the last loaded one.
     */
    suspend fun latestCreationQuota(): GenerationQuota? {
        myRingtonesJob?.takeIf { it.isActive }?.let { job -> withTimeoutOrNull(QUOTA_WAIT_MS) { job.join() } }
        return _uiState.value.creationQuota
    }

    /** Re-reads the profile name (Home resumes after login / verify saved a user). */
    fun refreshProfileName() {
        val firstName = readProfileFirstName()
        if (firstName == _uiState.value.profileFirstName) return
        _uiState.update { state -> state.copy(profileFirstName = firstName).withFilteredTunes() }
    }

    private fun readProfileFirstName(): String = HomeTuneFilter.firstName(profileStore.getProfile().name)

    /** Home's stable per-tune numbers, like the own ringtones from the server. */
    private fun readSavedPersonalizedTune(): Tune? =
        activeRingtoneStore.savedPersonalizedTune()?.let { TuneStatsUtils.withRandomStats(listOf(it)).first() }

    /** [tune] was set as the default: a personalized row (own ringtone) is recorded as its copy. */
    fun onRingtoneSet(tune: Tune, ringtoneUri: android.net.Uri) {
        activeRingtoneStore.save(tune, ringtoneUri)
        savedPersonalizedTune = readSavedPersonalizedTune()
        updateFilteredTunes(activeRingtoneId = tune.rowKey)
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
        // A failed or not yet loaded own-ringtones list is retried when the name chip opens.
        if (nextCategoryId == Category.MY_NAME_CATEGORY_ID && state.myRingtones == null) refreshMyRingtones()
        // All <-> the name chip show the same loaded tunes: only the local name filter changes.
        val nameChipInvolved = Category.MY_NAME_CATEGORY_ID in setOf(currentCategoryId, nextCategoryId)
        if (nameChipInvolved &&
            categoryIdForFetch(currentCategoryId) == categoryIdForFetch(nextCategoryId) &&
            !state.isLoadingTunes &&
            state.errorMessage == null
        ) {
            updateFilteredTunes(activeRingtoneId = state.activeRingtoneId)
            return
        }
        loadTunes(categoryIdForFetch(nextCategoryId), AnalyticsTrigger.CATEGORY_CHANGE)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { state -> state.copy(searchQuery = query).withFilteredTunes() }

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
                    val activeId = resolveActiveKey(tunes, _uiState.value.myRingtones)
                    _uiState.update { state ->
                        state.copy(
                            tunes = tunes,
                            activeRingtoneId = activeId,
                            isLoadingTunes = false,
                        ).withFilteredTunes()
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
            // Unchanged by the synthetic name chip: DB categories plus All, as before.
            categoryCount = state.categories.count { it.id != Category.MY_NAME_CATEGORY_ID },
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
        _uiState.update { state -> state.copy(activeRingtoneId = activeRingtoneId).withFilteredTunes() }
    }

    /** The row key of the system ringtone among the catalog [tunes] and the user's own [mine]. */
    private fun resolveActiveKey(tunes: List<Tune>, mine: List<Tune>?): String? =
        activeRingtoneStore.resolveActiveKey(getApplication(), tunes, mine.orEmpty())

    /** Recomputes [HomeUiState.filteredTunes] from this state's tunes, query, chip and active row. */
    private fun HomeUiState.withFilteredTunes(): HomeUiState {
        val listed = if (isMyNameSelected) {
            HomeTuneFilter.nameTab(tunes, myRingtones, savedPersonalizedTune, profileFirstName, searchQuery, activeRingtoneId)
        } else {
            HomeTuneFilter.withActiveFirst(HomeTuneFilter.filter(tunes, searchQuery, nameFilter = null), activeRingtoneId)
        }
        return copy(filteredTunes = listed)
    }

    /** All Tunes, then the synthetic "{name} Tunes" chip, then the DB categories. */
    private fun withAllCategory(categories: List<Category>) = listOf(
        Category(id = Category.ALL_CATEGORY_ID, name = "All"),
        Category(id = Category.MY_NAME_CATEGORY_ID, name = Category.MY_NAME_CATEGORY_NAME),
    ) + categories

    /** All and the name chip load every active tune; the name chip filters them locally. */
    private fun categoryIdForFetch(selectedCategoryId: String?) = when (selectedCategoryId) {
        Category.ALL_CATEGORY_ID, Category.MY_NAME_CATEGORY_ID -> null
        else -> selectedCategoryId
    }

    private fun mapLoadError(error: Throwable): String = LoadErrorMapper.message(error)

    private companion object {
        const val TAG = "HomeViewModel"
        const val SEARCH_TRACK_DEBOUNCE_MS = 500L
        const val QUOTA_WAIT_MS = 3_000L
        const val STAGE_CATEGORIES = "categories"
        const val STAGE_TUNES = "tunes"
    }
}
