package com.spacewire.meratune

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.analytics.AnalyticsScreen
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.CreationEntryPoint
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.calltheme.RingtoneSetController
import com.spacewire.meratune.calltheme.SetEntryContext
import com.spacewire.meratune.data.GenerationQuota
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.CategoryAdapter
import com.spacewire.meratune.ui.CreationLimitBottomSheet
import com.spacewire.meratune.ui.CreationLimitPolicy
import com.spacewire.meratune.ui.CtaButtons
import com.spacewire.meratune.ui.HomeScreenViewGate
import com.spacewire.meratune.ui.HomeUiState
import com.spacewire.meratune.ui.HomeViewModel
import com.spacewire.meratune.ui.InsetDividerDecoration
import com.spacewire.meratune.ui.PlaybackSessionStats
import com.spacewire.meratune.ui.PreviewPlayerController
import com.spacewire.meratune.ui.TuneAdapter
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.InsetsUi
import com.spacewire.meratune.util.StartupPermissionRequester
import com.spacewire.meratune.util.enableLightEdgeToEdge
import androidx.media3.common.PlaybackException
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class Home : AppCompatActivity() {

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var tuneAdapter: TuneAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var searchInput: EditText
    private lateinit var emptyScroll: ScrollView
    private lateinit var emptyStateImage: ImageView
    private lateinit var createCta: TextView
    private lateinit var createCtaContainer: View
    private lateinit var createCtaProgress: View
    private lateinit var tunesRecycler: RecyclerView
    private lateinit var nameTabCta: TextView
    private lateinit var nameTabCtaContainer: View
    private lateinit var nameTabCtaProgress: View
    private var tunesBasePaddingBottom = 0

    /** [HomeUiState.showNameTabCreateCta] of the last state; the keyboard hides the CTA meanwhile. */
    private var nameTabCtaWanted = false
    private var limitSheet: BottomSheetDialog? = null
    private var suppressSearchUpdates = false
    private var isNavigating = false
    private var imeVisible = false
    private var emptyStateShown = false

    /** Keeps `onNewIntent` from re-tracking a `screen_viewed(home)` the lifecycle already sent. */
    private val screenViewGate = HomeScreenViewGate()

    /**
     * Rows ([Tune.rowKey]) with a `tune_played` in this visit (cleared on pause); feeds
     * `ringtone_set_started.was_previewed`.
     */
    private val previewedTuneIds = mutableSetOf<String>()

    /** `source` of the current preview's `tune_played`, repeated on its `tune_play_ended`. */
    private var playSource = AnalyticsSource.HOME

    /** `tune_id` of the current preview: the player id is the row key, the base tune id for an own ringtone. */
    private var playTuneId: String? = null

    private val previewPlayer: PreviewPlayerController by lazy {
        PreviewPlayerController(
            context = this,
            scope = lifecycleScope,
            listener = object : PreviewPlayerController.Listener {
                override fun onProgress(id: String, progress: Float) {
                    tuneAdapter.updatePlaybackProgress(progress)
                }

                override fun onPlayingChanged(id: String, isPlaying: Boolean) = Unit

                override fun onEnded(id: String) {
                    viewModel.onPlayToggle(id)
                    stopPlayback()
                }

                override fun onError(id: String, error: PlaybackException) {
                    Log.e(TAG, "Playback failed for tune $id", error)
                    showPlaybackError(error.message ?: getString(R.string.playback_error))
                    viewModel.onPlayToggle(id)
                    stopPlayback()
                }

                override fun onSessionEnded(stats: PlaybackSessionStats) {
                    mixpanelAnalytics().trackTunePlayEnded(playSource, stats, tuneId = playTuneId ?: stats.id)
                }
            },
        )
    }

    private val ringtoneSetController = RingtoneSetController(
        activity = this,
        analyticsSource = AnalyticsSource.HOME,
        categoryForTune = { tune -> tune.category?.name.orEmpty() },
        onSuccess = { tune, uri -> viewModel.onRingtoneSet(tune, uri) },
    )
    private val startupPermissionRequester = StartupPermissionRequester(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_home)
        screenViewGate.onCreate(restored = savedInstanceState != null)

        // The keyboard raises the bottom padding, so the empty state's CTA stays reachable.
        InsetsUi.padForSystemBarsAndIme(findViewById(R.id.main)) { visible -> onImeChanged(visible) }

        setupAdapters()
        setupSearch()
        setupEmptyState()
        setupNameTabCta()
        setupErrorRetry()
        observeUiState()
        // A recreated Home must not re-prompt; a pending result is re-delivered to the new launchers.
        if (savedInstanceState == null) {
            startupPermissionRequester.requestIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // CLEAR_TOP re-entry (Ready screen "Go home") skips onCreate, so the lifecycle tracker does not see it.
        // A never-launched Home gets onCreate (already tracked) and then onNewIntent: skip that one.
        if (screenViewGate.shouldTrackOnNewIntent()) {
            mixpanelAnalytics().trackScreenViewed(AnalyticsScreen.HOME)
        }
    }

    private fun setupAdapters() {
        categoryAdapter = CategoryAdapter { categoryId ->
            viewModel.onCategorySelected(categoryId)
        }

        findViewById<RecyclerView>(R.id.categoriesRecycler).apply {
            layoutManager = LinearLayoutManager(this@Home, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
        }

        tuneAdapter = TuneAdapter(
            onPlayClick = { tune -> togglePlayback(tune) },
            onSetClick = { tune ->
                // No trial yet: the paywall instead of the set flow (so no ringtone_set_started).
                if (AuthNavigator.needsSubscription(this)) {
                    openPaywall()
                } else {
                    // An own ringtone (name chip) sets its own file: personalized, with its generation id.
                    ringtoneSetController.start(
                        tune,
                        SetEntryContext(
                            rank = viewModel.uiState.value.rankOf(tune.rowKey),
                            wasPreviewed = tune.rowKey in previewedTuneIds,
                        ),
                    )
                }
            },
        )

        tunesRecycler = findViewById<RecyclerView>(R.id.tunesRecycler).apply {
            layoutManager = LinearLayoutManager(this@Home)
            adapter = tuneAdapter
            addItemDecoration(InsetDividerDecoration(this@Home, R.color.divider, ROW_INSET_DP, ROW_INSET_DP))
        }

        findViewById<View>(R.id.profileIcon).setOnClickListener {
            startActivity(ProfileActivity.intent(this))
        }
    }

    private fun setupSearch() {
        searchInput = findViewById(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchUpdates) return
                viewModel.onSearchQueryChanged(s?.toString().orEmpty())
            }
        })
        // The search key only closes the keyboard; search_performed keeps its debounce.
        searchInput.setOnEditorActionListener { view, actionId, event ->
            val isSearchKey = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSearchKey) {
                WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
            }
            isSearchKey
        }
    }

    private fun setupEmptyState() {
        emptyScroll = findViewById(R.id.searchEmptyState)
        emptyStateImage = findViewById(R.id.emptyStateImage)
        createCta = findViewById(R.id.createRingtoneButton)
        createCtaContainer = findViewById(R.id.createRingtoneContainer)
        createCtaProgress = findViewById(R.id.createRingtoneProgress)

        // One paragraph with a vertical gradient on "Sirf Aapke Liye!" (plain text if a translation drops it).
        val highlight = getString(R.string.empty_search_title_highlight)
        GradientTextHelper.setTextWithGradientHighlight(
            findViewById(R.id.emptyStateTitle),
            getString(R.string.empty_search_title, highlight),
            highlight,
            R.color.gradient_orange,
            R.color.gradient_pink,
            GradientTextHelper.Direction.VERTICAL,
        )

        createCta.setOnClickListener { onCreateCtaTapped(createCta, createCtaProgress) }
    }

    private fun setupNameTabCta() {
        nameTabCtaContainer = findViewById(R.id.nameTabCreateContainer)
        nameTabCta = findViewById(R.id.nameTabCreateButton)
        nameTabCtaProgress = findViewById(R.id.nameTabCreateProgress)
        tunesBasePaddingBottom = tunesRecycler.paddingBottom
        nameTabCta.setOnClickListener { onCreateCtaTapped(nameTabCta, nameTabCtaProgress, fromNameTabCta = true) }
        // The label can wrap (long name, large font), so the list's room follows the CTA's height.
        nameTabCtaContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) tunesRecycler.post { applyTunesBottomPadding() }
        }
    }

    /**
     * The empty state's and the name tab's "Make {name} tune" CTA ([button], with its [progress]
     * spinner; [fromNameTabCta] for the name tab's floating one, which always makes the user's own
     * name). No trial yet: the paywall. A member whose creation quota is used up gets the limit
     * sheet instead of the create form; an unknown quota lets the server decide.
     */
    private fun onCreateCtaTapped(button: TextView, progress: View, fromNameTabCta: Boolean = false) {
        if (isNavigating) return
        isNavigating = true
        val state = viewModel.uiState.value
        // Empty state: the trimmed query, or the profile first name under the "{name} Tunes" chip.
        val prefillName = if (fromNameTabCta) state.profileFirstName.trim() else state.emptyStateName
        val fromNameChip = fromNameTabCta || (state.searchQuery.isBlank() && state.isMyNameSelected)
        viewModel.flushPendingSearchTracking()
        mixpanelAnalytics().trackCreateRingtoneCtaTapped(
            source = if (fromNameChip) AnalyticsSource.MY_NAME_CHIP else AnalyticsSource.SEARCH_BAR,
            prefillNameLength = prefillName.length,
        )
        // No trial yet: the paywall (entry_point locked_home) instead of the create form.
        if (AuthNavigator.needsSubscription(this)) {
            startActivity(SubscriptionActivity.intent(this))
            return
        }
        val entryPoint = if (fromNameChip) CreationEntryPoint.MY_NAME_CHIP else CreationEntryPoint.SEARCH_BAR
        lifecycleScope.launch {
            // Only a refresh in flight (Home just resumed) makes the tap wait, behind the spinner.
            val waiting = viewModel.isRefreshingMyRingtones
            if (waiting) CtaButtons.setLoading(button, progress, true)
            val quota = try {
                viewModel.latestCreationQuota()
            } finally {
                if (waiting) CtaButtons.setLoading(button, progress, false)
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                isNavigating = false
                return@launch
            }
            // The server's plan is fresher than the stored status: `default` = neither trial nor
            // active there (e.g. autopay cancelled since login), so the paywall, as for non-members.
            if (quota?.plan == GenerationQuota.PLAN_DEFAULT) {
                startActivity(SubscriptionActivity.intent(this@Home))
                return@launch
            }
            if (quota != null && CreationLimitPolicy.isExhausted(quota, System.currentTimeMillis())) {
                showCreationLimitSheet(quota)
                isNavigating = false
                return@launch
            }
            startActivity(CreateRingtoneActivity.intent(this@Home, prefillName, entryPoint))
        }
    }

    private fun showCreationLimitSheet(quota: GenerationQuota) {
        limitSheet?.dismiss()
        limitSheet = CreationLimitBottomSheet(
            context = this,
            quota = quota,
            latestRingtone = viewModel.uiState.value.latestOwnRingtone,
            onExploreTunes = { exploreAllTunes() },
        ).show()
        mixpanelAnalytics().trackCreationLimitReached(
            limitType = CreationLimitPolicy.limitType(quota),
            plan = quota.plan,
            source = AnalyticsSource.HOME,
            quotaUsedToday = quota.usedCount,
            quotaDailyLimit = quota.limitCount,
        )
    }

    /** The limit sheet's "Explore More Tunes": All Tunes, with the search box cleared. */
    private fun exploreAllTunes() {
        clearSearchInput()
        viewModel.resetToAllTunes()
    }

    /** "Make %1$s tune" with the searched or chip name; a blank name keeps "Create Ringtone". */
    private fun createCtaLabel(name: String): String =
        if (name.isBlank()) getString(R.string.create_ringtone) else getString(R.string.home_make_name_tune, name)

    /**
     * The searched name (or, under the name chip, the profile first name) in the message and the
     * "Make %1$s tune" CTA; a blank name keeps the generic "Create Ringtone" copy.
     */
    private fun bindEmptyStateName(name: String) {
        findViewById<TextView>(R.id.emptyStateMessage).text = if (name.isBlank()) {
            getString(R.string.home_my_name_empty_message)
        } else {
            getString(R.string.empty_search_message, name)
        }
        createCta.text = createCtaLabel(name)
    }

    /** The floating CTA over a non-empty "{name} Tunes" list: "Make {first name} tune", whatever the search. */
    private fun bindNameTabCta(state: HomeUiState) {
        nameTabCtaWanted = state.showNameTabCreateCta
        if (nameTabCtaWanted) nameTabCta.text = createCtaLabel(state.profileFirstName.trim())
        updateNameTabCtaVisibility()
    }

    /** Hidden while the keyboard is open; the list then gets its plain bottom padding back. */
    private fun updateNameTabCtaVisibility() {
        nameTabCtaContainer.isVisible = nameTabCtaWanted && !imeVisible
        applyTunesBottomPadding()
    }

    /** While the floating CTA shows, the list's last row scrolls up above it. */
    private fun applyTunesBottomPadding() {
        val extra = if (nameTabCtaContainer.isVisible) {
            nameTabCtaContainer.height + nameTabCtaContainer.marginBottom
        } else {
            0
        }
        val bottom = tunesBasePaddingBottom + extra
        if (tunesRecycler.paddingBottom != bottom) tunesRecycler.updatePadding(bottom = bottom)
    }

    /** A gated Set tap; [isNavigating] (reset on resume) keeps a double tap to one paywall. */
    private fun openPaywall() {
        if (isNavigating) return
        isNavigating = true
        startActivity(SubscriptionActivity.intent(this))
    }

    private fun setupErrorRetry() {
        findViewById<TextView>(R.id.errorText).setOnClickListener {
            viewModel.retryLoad()
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    findViewById<ProgressBar>(R.id.loadingIndicator).visibility =
                        if (state.showLoadingIndicator) View.VISIBLE else View.GONE

                    val errorView = findViewById<TextView>(R.id.errorText)
                    if (state.errorMessage != null) {
                        errorView.visibility = View.VISIBLE
                        errorView.text = getString(
                            R.string.load_error_with_retry,
                            state.errorMessage,
                        )
                    } else {
                        errorView.visibility = View.GONE
                    }

                    categoryAdapter.submitList(state.categories, state.selectedCategoryId, state.profileFirstName)
                    tuneAdapter.submitList(state.filteredTunes, state.playingTuneId, state.activeRingtoneId)

                    val showEmptyState = state.showSearchEmptyState
                    emptyScroll.visibility = if (showEmptyState) View.VISIBLE else View.GONE
                    tunesRecycler.visibility = if (showEmptyState) View.GONE else View.VISIBLE
                    bindNameTabCta(state)

                    if (showEmptyState) bindEmptyStateName(state.emptyStateName)
                    if (showEmptyState && !emptyStateShown && imeVisible) ensureCreateCtaVisible()
                    emptyStateShown = showEmptyState

                    if (state.playingTuneId == null && previewPlayer.isPlaying) {
                        stopPlayback()
                    }
                }
            }
        }
    }

    private fun togglePlayback(tune: Tune) {
        val state = viewModel.uiState.value
        // Keyed on the row (an own ringtone shares its base tune id), not isPlaying: a buffering or
        // system-paused tune still shows pause.
        val rowKey = tune.rowKey
        if (state.playingTuneId == rowKey) {
            viewModel.onPlayToggle(rowKey)
            stopPlayback()
            return
        }

        stopPlayback()
        viewModel.onPlayToggle(rowKey)

        val playbackUrl = tune.tuneUrl.trim()
        if (playbackUrl.isBlank()) {
            showPlaybackError("Empty tune URL for ${tune.name}")
            viewModel.onPlayToggle(rowKey)
            return
        }

        Log.d(TAG, "Starting playback for ${tune.name}: $playbackUrl")

        val fromSearch = state.searchQuery.isNotBlank()
        playSource = if (fromSearch) AnalyticsSource.SEARCH_RESULTS else AnalyticsSource.HOME
        playTuneId = tune.id
        mixpanelAnalytics().trackTunePlayed(
            tuneId = tune.id,
            category = tune.category?.name.orEmpty(),
            source = playSource,
            rank = state.rankOf(rowKey),
            categoryFilter = state.selectedCategoryName,
            fromSearch = fromSearch,
            isActiveRingtone = rowKey == state.activeRingtoneId,
        )
        previewedTuneIds += rowKey

        previewPlayer.play(rowKey, playbackUrl)
    }

    /**
     * While the keyboard is open the empty state drops its logo and scrolls to the CTA, and the
     * name tab's floating CTA hides.
     */
    private fun onImeChanged(visible: Boolean) {
        imeVisible = visible
        emptyStateImage.isVisible = !visible
        updateNameTabCtaVisibility()
        if (visible && emptyScroll.isVisible) ensureCreateCtaVisible()
    }

    private fun ensureCreateCtaVisible() {
        emptyScroll.post {
            // The wrapper (CTA + spinner) is the column's child: its bottom is in column coordinates.
            val target = createCtaContainer.bottom + emptyScroll.paddingBottom - emptyScroll.height + emptyScroll.paddingTop
            emptyScroll.smoothScrollTo(0, target.coerceAtLeast(0))
        }
    }

    private fun showPlaybackError(detail: String) {
        Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
        Log.e(TAG, detail)
    }

    private fun stopPlayback() {
        previewPlayer.release()
        if (::tuneAdapter.isInitialized) {
            tuneAdapter.updatePlaybackProgress(0f)
        }
    }

    companion object {
        private const val TAG = "HomePlayback"
        private const val ROW_INSET_DP = 36f
    }

    override fun onPause() {
        super.onPause()
        resetHomeForReturn()
    }

    override fun onResume() {
        super.onResume()
        screenViewGate.onResume()
        isNavigating = false
        viewModel.refreshProfileName()
        viewModel.refreshActiveRingtone()
        viewModel.refreshMyRingtones()
    }

    private fun resetHomeForReturn() {
        viewModel.flushPendingSearchTracking()
        previewedTuneIds.clear()
        stopPlayback()
        clearSearchInput()
        viewModel.resetToAllTunes()
    }

    /** Empties the search box without the ViewModel seeing a query change (the caller resets it). */
    private fun clearSearchInput() {
        suppressSearchUpdates = true
        searchInput.setText("")
        suppressSearchUpdates = false
    }

    override fun onDestroy() {
        limitSheet?.dismiss()
        limitSheet = null
        stopPlayback()
        super.onDestroy()
    }
}
