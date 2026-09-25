package com.spacewire.meratune

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.AnalyticsTrigger
import com.spacewire.meratune.analytics.FilterSelection
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.calltheme.RingtoneSetController
import com.spacewire.meratune.calltheme.SetChoice
import com.spacewire.meratune.calltheme.SetEntryContext
import com.spacewire.meratune.data.Category
import com.spacewire.meratune.data.FallbackLevel
import com.spacewire.meratune.data.HomeRepository
import com.spacewire.meratune.data.Languages
import com.spacewire.meratune.data.RankedSong
import com.spacewire.meratune.data.RankedSongs
import com.spacewire.meratune.data.SongRanker
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.data.VoiceFilter
import com.spacewire.meratune.ui.FilterChip
import com.spacewire.meratune.ui.FilterChipAdapter
import com.spacewire.meratune.ui.InsetDividerDecoration
import com.spacewire.meratune.ui.PlaybackSessionStats
import com.spacewire.meratune.ui.PreviewPlayerController
import com.spacewire.meratune.ui.SongChoiceAdapter
import com.spacewire.meratune.util.Haptics
import com.spacewire.meratune.util.LoadErrorMapper
import com.spacewire.meratune.util.TuneStatsUtils
import com.spacewire.meratune.util.enableLightEdgeToEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Step 2 of the personalized-ringtone flow: preview and pick one of the curated songs.
 * A row tap only toggles its preview. "chuno" commits: `sample_selected`, then the set-mode sheets
 * ([RingtoneSetController.choose]); the choice travels to generation and is applied on the final
 * screen.
 */
class ChooseSongActivity : AppCompatActivity() {

    private enum class ScreenState { LOADING, CONTENT, EMPTY, ERROR }

    private enum class LoadState(val analyticsValue: String) {
        CONTENT("content"),
        EMPTY("empty"),
        ERROR("error"),
    }

    private val repository = HomeRepository()

    private lateinit var userName: String
    private lateinit var requestedLanguage: String
    private lateinit var effectiveLanguage: String

    private var voiceFilter: VoiceFilter = VoiceFilter.ALL
    private var categoryFilterId: String = Category.ALL_CATEGORY_ID

    private var allTunes: List<Tune>? = null
    private var ranked: RankedSongs? = null
    private var visibleSongs: List<RankedSong> = emptyList()
    private val trackedSelections = mutableSetOf<String>()
    private val previewedTuneIds = mutableSetOf<String>()

    /** Card whose preview played to the end; tapping it again replays it as a new preview. */
    private var endedPreviewId: String? = null

    /**
     * `trigger` of the load whose `sample_list_viewed` is still owed; `null` when the reload only
     * repeats an already reported state after recreation.
     */
    private var pendingLoadTrigger: String? = AnalyticsTrigger.INITIAL
    private var reportedLoadState: String? = null
    private var isNavigating = false

    /** A chuno choice that completed while this screen was not in front; launched in [onResume]. */
    private var pendingLaunch: Pair<Tune, SetChoice>? = null

    // A field so its result launchers register before the activity is created.
    private val setController = RingtoneSetController(
        activity = this,
        analyticsSource = AnalyticsSource.CREATION_FLOW,
        categoryForTune = { tune -> tune.category?.name.orEmpty() },
        onChosen = { tune, choice -> launchProcessing(tune, choice) },
    )

    private var loadJob: Job? = null
    private var skeletonAnimator: ObjectAnimator? = null

    private lateinit var songAdapter: SongChoiceAdapter
    private lateinit var voiceChipAdapter: FilterChipAdapter
    private lateinit var categoryChipAdapter: FilterChipAdapter

    private lateinit var songsRecycler: RecyclerView
    private lateinit var voiceChipsRecycler: RecyclerView
    private lateinit var categoryChipsRecycler: RecyclerView
    private lateinit var skeletonContainer: View
    private lateinit var emptyContainer: View
    private lateinit var errorText: TextView
    private lateinit var fallbackBanner: TextView

    private val previewPlayer: PreviewPlayerController by lazy {
        PreviewPlayerController(
            context = this,
            scope = lifecycleScope,
            listener = object : PreviewPlayerController.Listener {
                // The picker rows show play / pause only (no progress ring).
                override fun onProgress(id: String, progress: Float) = Unit

                override fun onPlayingChanged(id: String, isPlaying: Boolean) {
                    songAdapter.setPreview(id, isPlaying)
                }

                override fun onEnded(id: String) {
                    endedPreviewId = id
                    songAdapter.setPreview(id, isPlaying = false)
                }

                override fun onError(id: String, error: PlaybackException) {
                    Log.w(TAG, "Preview failed for $id: ${error.errorCodeName}")
                    songAdapter.setPreview(null, isPlaying = false)
                    Toast.makeText(this@ChooseSongActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
                }

                override fun onSessionEnded(stats: PlaybackSessionStats) {
                    mixpanelAnalytics().trackTunePlayEnded(source = AnalyticsSource.SONG_PICKER, stats = stats)
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
        val language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty().trim()
        if (name.isEmpty() || language.isEmpty()) {
            finish()
            return
        }
        userName = name
        requestedLanguage = language
        effectiveLanguage = savedInstanceState?.getString(STATE_EFFECTIVE_LANGUAGE) ?: language
        savedInstanceState?.let { state ->
            voiceFilter = state.getString(STATE_VOICE_FILTER)
                ?.let { stored -> VoiceFilter.entries.firstOrNull { it.name == stored } }
                ?: VoiceFilter.ALL
            categoryFilterId = state.getString(STATE_CATEGORY_FILTER) ?: Category.ALL_CATEGORY_ID
            state.getStringArrayList(STATE_TRACKED_SELECTIONS)?.let(trackedSelections::addAll)
            state.getStringArrayList(STATE_PREVIEWED_TUNE_IDS)?.let(previewedTuneIds::addAll)
            pendingLoadTrigger = state.getString(STATE_PENDING_LOAD_TRIGGER)
            reportedLoadState = state.getString(STATE_REPORTED_LOAD_STATE)
        }

        enableLightEdgeToEdge()
        setContentView(R.layout.activity_choose_song)

        val scroll = findViewById<View>(R.id.chooseSongScroll)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chooseSongRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // The list scrolls under the navigation bar (clipToPadding=false) and ends above it.
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            scroll.updatePadding(bottom = systemBars.bottom)
            insets
        }

        bindViews()
        setupLists()
        setupActions()
        loadSongs(pendingLoadTrigger)
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
        pendingLaunch?.let { (tune, choice) ->
            pendingLaunch = null
            launchProcessing(tune, choice)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::songAdapter.isInitialized) previewPlayer.pause()
    }

    override fun onStop() {
        super.onStop()
        if (::songAdapter.isInitialized) releasePreview()
    }

    override fun onDestroy() {
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        if (::songAdapter.isInitialized) releasePreview()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::effectiveLanguage.isInitialized) return
        outState.putString(STATE_VOICE_FILTER, voiceFilter.name)
        outState.putString(STATE_CATEGORY_FILTER, categoryFilterId)
        outState.putString(STATE_EFFECTIVE_LANGUAGE, effectiveLanguage)
        outState.putStringArrayList(STATE_TRACKED_SELECTIONS, ArrayList(trackedSelections))
        outState.putStringArrayList(STATE_PREVIEWED_TUNE_IDS, ArrayList(previewedTuneIds))
        outState.putString(STATE_PENDING_LOAD_TRIGGER, pendingLoadTrigger)
        outState.putString(STATE_REPORTED_LOAD_STATE, reportedLoadState)
    }

    private fun bindViews() {
        songsRecycler = findViewById(R.id.songsRecycler)
        voiceChipsRecycler = findViewById(R.id.voiceChipsRecycler)
        categoryChipsRecycler = findViewById(R.id.categoryChipsRecycler)
        skeletonContainer = findViewById(R.id.skeletonContainer)
        emptyContainer = findViewById(R.id.emptyContainer)
        errorText = findViewById(R.id.errorText)
        fallbackBanner = findViewById(R.id.fallbackBanner)

        findViewById<TextView>(R.id.songChoiceTitle).text = getString(R.string.song_choice_title, userName)
        findViewById<TextView>(R.id.songChoiceSubtitle).text = getString(R.string.song_choice_subtitle, userName)
    }

    private fun setupLists() {
        songAdapter = SongChoiceAdapter(onPreviewClick = ::onPreviewTapped, onChooseClick = ::onChooseTapped)
        songsRecycler.layoutManager = LinearLayoutManager(this)
        songsRecycler.adapter = songAdapter
        songsRecycler.addItemDecoration(
            InsetDividerDecoration(this, insetStartDp = ROW_DIVIDER_INSET_DP, insetEndDp = ROW_DIVIDER_INSET_DP),
        )

        voiceChipAdapter = FilterChipAdapter { chip -> onVoiceChipTapped(chip) }
        voiceChipsRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        voiceChipsRecycler.adapter = voiceChipAdapter

        categoryChipAdapter = FilterChipAdapter { chip -> onCategoryChipTapped(chip) }
        categoryChipsRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        categoryChipsRecycler.adapter = categoryChipAdapter
    }

    private fun setupActions() {
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }
        errorText.setOnClickListener { loadSongs(AnalyticsTrigger.RETRY) }
        findViewById<View>(R.id.emptyChangeLanguageButton).setOnClickListener { finish() }
        findViewById<View>(R.id.emptyHindiButton).setOnClickListener {
            effectiveLanguage = SongRanker.HINDI
            voiceFilter = VoiceFilter.ALL
            categoryFilterId = Category.ALL_CATEGORY_ID
            loadSongs(AnalyticsTrigger.HINDI_FALLBACK)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    /** [trigger] is the `sample_list_viewed` trigger; `null` for the silent reload after recreation. */
    private fun loadSongs(trigger: String?) {
        pendingLoadTrigger = trigger
        loadJob?.cancel()
        releasePreview()
        render(ScreenState.LOADING)
        loadJob = lifecycleScope.launch {
            try {
                // Same seeded like/view counts as Home for the same tune.
                val tunes = TuneStatsUtils.withRandomStats(repository.fetchPersonalizableTunes())
                allTunes = tunes
                applyFilters(isTerminalLoad = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "fetchPersonalizableTunes failed: ${error.javaClass.simpleName}")
                allTunes = null
                ranked = null
                visibleSongs = emptyList()
                errorText.text = getString(R.string.load_error_with_retry, loadErrorMessage(error))
                render(ScreenState.ERROR)
                trackPickerViewed(
                    loadState = LoadState.ERROR,
                    sampleCount = 0,
                    categoryCount = 0,
                    fallbackLevel = null,
                    failureReason = LoadErrorMapper.reason(error),
                )
            }
        }
    }

    /**
     * Ranks [allTunes] for [effectiveLanguage] + [voiceFilter], then applies the category chip.
     * Only an exact-language tier is shown; any fallback is offered through the empty state so the
     * user explicitly agrees to hear their name in Hindi.
     */
    private fun applyFilters(isTerminalLoad: Boolean) {
        val tunes = allTunes ?: return
        val unfiltered = SongRanker.rank(tunes, effectiveLanguage, VoiceFilter.ALL)
        val hasTier = unfiltered.fallbackLevel == FallbackLevel.NONE && unfiltered.songs.isNotEmpty()

        if (!hasTier) {
            ranked = unfiltered
            visibleSongs = emptyList()
            renderEmptyState()
            render(ScreenState.EMPTY)
            if (isTerminalLoad) {
                trackPickerViewed(
                    loadState = LoadState.EMPTY,
                    sampleCount = 0,
                    categoryCount = 0,
                    fallbackLevel = analyticsFallbackLevel(unfiltered.fallbackLevel),
                )
            }
            return
        }

        val availableVoices = VoiceFilter.entries.filter { filter ->
            filter == VoiceFilter.ALL || unfiltered.songs.any { it.tune.voiceKey == filter.voiceKey }
        }
        if (voiceFilter !in availableVoices) voiceFilter = VoiceFilter.ALL

        val result = SongRanker.rank(tunes, effectiveLanguage, voiceFilter)
        ranked = result

        if (result.categories.size < MIN_CATEGORIES_FOR_CHIPS ||
            result.categories.none { it.id == categoryFilterId }
        ) {
            categoryFilterId = Category.ALL_CATEGORY_ID
        }
        visibleSongs = if (categoryFilterId == Category.ALL_CATEGORY_ID) {
            result.songs
        } else {
            result.songs.filter { it.tune.category?.id == categoryFilterId }
        }

        previewPlayer.currentId?.let { playingId ->
            if (visibleSongs.none { it.tune.id == playingId }) releasePreview()
        }

        renderVoiceChips(availableVoices)
        renderCategoryChips(result.categories)
        songAdapter.submit(visibleSongs)
        previewPlayer.currentId?.let { songAdapter.setPreview(it, previewPlayer.isPlaying) }

        fallbackBanner.visibility = if (isHindiFallbackActive()) {
            fallbackBanner.text = getString(R.string.song_choice_fallback_banner, languageLabel(requestedLanguage))
            View.VISIBLE
        } else {
            View.GONE
        }

        render(ScreenState.CONTENT)

        if (isTerminalLoad) {
            trackPickerViewed(
                loadState = LoadState.CONTENT,
                sampleCount = result.songs.size,
                categoryCount = result.categories.size,
                fallbackLevel = analyticsFallbackLevel(result.fallbackLevel),
            )
        }
    }

    private fun render(state: ScreenState) {
        songsRecycler.visibility = if (state == ScreenState.CONTENT) View.VISIBLE else View.GONE
        emptyContainer.visibility = if (state == ScreenState.EMPTY) View.VISIBLE else View.GONE
        errorText.visibility = if (state == ScreenState.ERROR) View.VISIBLE else View.GONE

        if (state != ScreenState.CONTENT) {
            voiceChipsRecycler.visibility = View.GONE
            categoryChipsRecycler.visibility = View.GONE
            fallbackBanner.visibility = View.GONE
        }
        if (state == ScreenState.LOADING) {
            showSkeleton()
        } else {
            hideSkeleton()
        }
    }

    private fun renderEmptyState() {
        val alreadyHindi = effectiveLanguage.equals(SongRanker.HINDI, ignoreCase = true)
        val hindiButton = findViewById<View>(R.id.emptyHindiButton)
        val message = findViewById<TextView>(R.id.emptyMessage)
        if (alreadyHindi) {
            hindiButton.visibility = View.GONE
            message.visibility = View.GONE
        } else {
            hindiButton.visibility = View.VISIBLE
            message.visibility = View.VISIBLE
            message.text = getString(R.string.song_choice_empty_message, languageLabel(effectiveLanguage))
        }
    }

    private fun renderVoiceChips(available: List<VoiceFilter>) {
        if (available.size < 2) {
            voiceChipsRecycler.visibility = View.GONE
            return
        }
        val chips = available.map { filter ->
            FilterChip(
                key = filter.name,
                label = when (filter) {
                    VoiceFilter.ALL -> getString(R.string.song_choice_filter_all)
                    VoiceFilter.MALE -> getString(R.string.create_form_voice_male)
                    VoiceFilter.FEMALE -> getString(R.string.create_form_voice_female)
                },
            )
        }
        voiceChipAdapter.submit(chips, voiceFilter.name)
        voiceChipsRecycler.visibility = View.VISIBLE
    }

    private fun renderCategoryChips(categories: List<Category>) {
        if (categories.size < MIN_CATEGORIES_FOR_CHIPS) {
            categoryChipsRecycler.visibility = View.GONE
            return
        }
        val chips = listOf(FilterChip(Category.ALL_CATEGORY_ID, getString(R.string.song_choice_filter_all))) +
            categories.map { FilterChip(it.id, it.name) }
        categoryChipAdapter.submit(chips, categoryFilterId)
        categoryChipsRecycler.visibility = View.VISIBLE
    }

    private fun showSkeleton() {
        skeletonContainer.visibility = View.VISIBLE
        if (skeletonAnimator == null) {
            skeletonAnimator = ObjectAnimator.ofFloat(skeletonContainer, View.ALPHA, 1f, SKELETON_MIN_ALPHA).apply {
                duration = SKELETON_PULSE_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
        }
        skeletonAnimator?.takeUnless { it.isStarted }?.start()
    }

    private fun hideSkeleton() {
        skeletonAnimator?.cancel()
        skeletonContainer.alpha = 1f
        skeletonContainer.visibility = View.GONE
    }

    // ---------------------------------------------------------------------------------------------
    // Interaction
    // ---------------------------------------------------------------------------------------------

    /** Row or art tap: toggles the preview only (`sample_previewed` for a new preview). */
    private fun onPreviewTapped(song: RankedSong) {
        val tune = song.tune
        val url = tune.tuneUrl.trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
            return
        }
        val startsNewPreview = previewPlayer.currentId != tune.id || endedPreviewId == tune.id
        if (startsNewPreview) {
            songAdapter.setPreview(tune.id, isPlaying = false)
        }
        previewPlayer.toggle(tune.id, url)
        if (startsNewPreview) {
            endedPreviewId = null
            previewedTuneIds.add(tune.id)
            mixpanelAnalytics().trackSamplePreviewed(
                sampleId = tune.id,
                category = tune.category?.name.orEmpty(),
                language = effectiveLanguage,
                voice = tune.voiceKey,
                rank = song.rank,
            )
        }
    }

    /**
     * "chuno": commits the song. `sample_selected` (once per tune), then the set-mode sheets; the
     * choice opens Processing ([launchProcessing]). Ignored while a set flow is already open
     * (a sheet showing, or a photo still being staged) or while navigating.
     */
    private fun onChooseTapped(song: RankedSong) {
        if (isNavigating || !setController.isIdle) return
        val tune = song.tune
        trackSelectedOnce(song)
        releasePreview()
        Haptics.select(songsRecycler)
        setController.choose(
            tune,
            SetEntryContext(rank = song.rank, wasPreviewed = tune.id in previewedTuneIds),
        )
    }

    private fun trackSelectedOnce(song: RankedSong) {
        val tune = song.tune
        if (!trackedSelections.add(tune.id)) return
        mixpanelAnalytics().trackSampleSelected(
            sampleId = tune.id,
            category = tune.category?.name.orEmpty(),
            language = effectiveLanguage,
            voice = tune.voiceKey,
            rank = song.rank,
            voiceFilter = voiceFilter.analyticsValue,
            categoryFilter = activeCategoryName(),
        )
    }

    private fun onVoiceChipTapped(chip: FilterChip) {
        val next = VoiceFilter.entries.firstOrNull { it.name == chip.key } ?: return
        if (next == voiceFilter) return
        voiceFilter = next
        Haptics.select(voiceChipsRecycler)
        applyFilters(isTerminalLoad = false)
        mixpanelAnalytics().trackVoiceFiltered(
            voiceFilter = next.name.lowercase(Locale.ROOT),
            resultCount = visibleSongs.size,
        )
    }

    private fun onCategoryChipTapped(chip: FilterChip) {
        if (chip.key == categoryFilterId) return
        categoryFilterId = chip.key
        Haptics.select(categoryChipsRecycler)
        val isAll = chip.key == Category.ALL_CATEGORY_ID
        mixpanelAnalytics().trackCategoryFiltered(
            categoryId = chip.key.takeUnless { isAll },
            categoryName = chip.label.takeUnless { isAll },
            source = AnalyticsSource.SONG_PICKER,
            selection = if (isAll) FilterSelection.ALL else FilterSelection.SELECTED,
        )
        applyFilters(isTerminalLoad = false)
    }

    /** Opens Processing with the chuno [choice]; waits for [onResume] if this screen is not in front. */
    private fun launchProcessing(tune: Tune, choice: SetChoice) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingLaunch = tune to choice
            return
        }
        if (isNavigating) return
        isNavigating = true
        releasePreview()
        startActivity(
            RingtoneProcessingActivity.intent(
                context = this,
                name = userName,
                language = effectiveLanguage,
                tune = tune,
                previewedCount = previewedTuneIds.size,
                setChoice = choice,
            ),
        )
    }

    private fun releasePreview() {
        previewPlayer.release()
        songAdapter.setPreview(null, isPlaying = false)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun isHindiFallbackActive(): Boolean =
        !effectiveLanguage.equals(requestedLanguage, ignoreCase = true) &&
            effectiveLanguage.equals(SongRanker.HINDI, ignoreCase = true)

    /** The user's language fell back to Hindi when they accepted the empty-state offer. */
    private fun analyticsFallbackLevel(level: FallbackLevel): String =
        if (level == FallbackLevel.NONE && isHindiFallbackActive()) {
            FallbackLevel.HINDI.analyticsValue
        } else {
            level.analyticsValue
        }

    /**
     * [LoadErrorMapper] copy for network / timeout failures. Anything it would only echo verbatim
     * (e.g. a PostgrestRestException carrying the request URL and headers) gets the generic copy.
     */
    private fun loadErrorMessage(error: Throwable): String {
        val mapped = LoadErrorMapper.message(error)
        return if (mapped.isBlank() || mapped == error.message) getString(R.string.processing_error_generic) else mapped
    }

    private fun activeCategoryName(): String? {
        if (categoryFilterId == Category.ALL_CATEGORY_ID) return null
        return ranked?.categories?.firstOrNull { it.id == categoryFilterId }?.name
    }

    private fun languageLabel(storageValue: String): String {
        val definition = Languages.all.firstOrNull { it.storageValue.equals(storageValue, ignoreCase = true) }
        return definition?.let { getString(it.nativeLabelRes) } ?: storageValue
    }

    /**
     * Once per load that reaches a terminal state. After recreation the reload stays silent when it
     * lands on the state already reported, and is tagged `restored` when it lands on a different one.
     */
    private fun trackPickerViewed(
        loadState: LoadState,
        sampleCount: Int,
        categoryCount: Int,
        fallbackLevel: String?,
        failureReason: String? = null,
    ) {
        val trigger = pendingLoadTrigger
        pendingLoadTrigger = null
        if (trigger == null && loadState.analyticsValue == reportedLoadState) return
        reportedLoadState = loadState.analyticsValue
        mixpanelAnalytics().trackSampleListViewed(
            language = effectiveLanguage,
            sampleCount = sampleCount,
            categoryCount = categoryCount,
            fallbackLevel = fallbackLevel.orEmpty(),
            voiceFilter = voiceFilter.analyticsValue,
            loadState = loadState.analyticsValue,
            trigger = trigger ?: AnalyticsTrigger.RESTORED,
            failureReason = failureReason,
            requestedLanguage = requestedLanguage,
        )
    }

    companion object {
        private const val TAG = "ChooseSong"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_LANGUAGE = "extra_language"
        private const val STATE_VOICE_FILTER = "state_voice_filter"
        private const val STATE_CATEGORY_FILTER = "state_category_filter"
        private const val STATE_EFFECTIVE_LANGUAGE = "state_effective_language"
        private const val STATE_TRACKED_SELECTIONS = "state_tracked_selections"
        private const val STATE_PREVIEWED_TUNE_IDS = "state_previewed_tune_ids"
        private const val STATE_PENDING_LOAD_TRIGGER = "state_pending_load_trigger"
        private const val STATE_REPORTED_LOAD_STATE = "state_reported_load_state"
        private const val MIN_CATEGORIES_FOR_CHIPS = 2
        private const val SKELETON_MIN_ALPHA = 0.4f
        private const val SKELETON_PULSE_MS = 700L
        private const val ROW_DIVIDER_INSET_DP = 36f

        /** @param name the validated display name; @param language a `Languages.storageValue`. */
        fun intent(context: Context, name: String, language: String): Intent {
            return Intent(context, ChooseSongActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LANGUAGE, language)
        }
    }
}
