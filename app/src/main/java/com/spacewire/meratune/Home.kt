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
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.CategoryAdapter
import com.spacewire.meratune.ui.HomeScreenViewGate
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
import kotlinx.coroutines.launch

class Home : AppCompatActivity() {

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var tuneAdapter: TuneAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var searchInput: EditText
    private lateinit var emptyScroll: ScrollView
    private lateinit var emptyStateImage: ImageView
    private lateinit var createCta: TextView
    private var suppressSearchUpdates = false
    private var isNavigating = false
    private var imeVisible = false
    private var emptyStateShown = false

    /** Keeps `onNewIntent` from re-tracking a `screen_viewed(home)` the lifecycle already sent. */
    private val screenViewGate = HomeScreenViewGate()

    /** Tunes with a `tune_played` in this visit (cleared on pause); feeds `ringtone_set_started.was_previewed`. */
    private val previewedTuneIds = mutableSetOf<String>()

    /** `source` of the current preview's `tune_played`, repeated on its `tune_play_ended`. */
    private var playSource = AnalyticsSource.HOME

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
                    mixpanelAnalytics().trackTunePlayEnded(playSource, stats)
                }
            },
        )
    }

    private val ringtoneSetController = RingtoneSetController(
        activity = this,
        analyticsSource = AnalyticsSource.HOME,
        categoryForTune = { tune -> tune.category?.name.orEmpty() },
        onSuccess = { tune, uri -> viewModel.onRingtoneSet(tune.id, uri) },
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
                    ringtoneSetController.start(
                        tune,
                        SetEntryContext(
                            rank = viewModel.uiState.value.rankOf(tune.id),
                            wasPreviewed = tune.id in previewedTuneIds,
                        ),
                    )
                }
            },
        )

        findViewById<RecyclerView>(R.id.tunesRecycler).apply {
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

        createCta.setOnClickListener {
            if (isNavigating) return@setOnClickListener
            isNavigating = true
            val state = viewModel.uiState.value
            // The trimmed query, or the profile first name under the "{name} Tunes" chip.
            val prefillName = state.emptyStateName
            val fromNameChip = state.searchQuery.isBlank() && state.isMyNameSelected
            viewModel.flushPendingSearchTracking()
            mixpanelAnalytics().trackCreateRingtoneCtaTapped(
                source = if (fromNameChip) AnalyticsSource.MY_NAME_CHIP else AnalyticsSource.SEARCH_BAR,
                prefillNameLength = prefillName.length,
            )
            // No trial yet: the paywall (entry_point locked_home) instead of the create form.
            if (AuthNavigator.needsSubscription(this)) {
                startActivity(SubscriptionActivity.intent(this))
            } else {
                val entryPoint = if (fromNameChip) CreationEntryPoint.MY_NAME_CHIP else CreationEntryPoint.SEARCH_BAR
                startActivity(CreateRingtoneActivity.intent(this, prefillName, entryPoint))
            }
        }
    }

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
        createCta.text = if (name.isBlank()) {
            getString(R.string.create_ringtone)
        } else {
            getString(R.string.home_make_name_tune, name)
        }
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
                        if (state.isLoading) View.VISIBLE else View.GONE

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
                    findViewById<RecyclerView>(R.id.tunesRecycler).visibility =
                        if (showEmptyState) View.GONE else View.VISIBLE

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
        // Keyed on the UI state, not isPlaying: a buffering or system-paused tune still shows pause.
        if (state.playingTuneId == tune.id) {
            viewModel.onPlayToggle(tune.id)
            stopPlayback()
            return
        }

        stopPlayback()
        viewModel.onPlayToggle(tune.id)

        val playbackUrl = tune.tuneUrl.trim()
        if (playbackUrl.isBlank()) {
            showPlaybackError("Empty tune URL for ${tune.name}")
            viewModel.onPlayToggle(tune.id)
            return
        }

        Log.d(TAG, "Starting playback for ${tune.name}: $playbackUrl")

        val fromSearch = state.searchQuery.isNotBlank()
        playSource = if (fromSearch) AnalyticsSource.SEARCH_RESULTS else AnalyticsSource.HOME
        mixpanelAnalytics().trackTunePlayed(
            tuneId = tune.id,
            category = tune.category?.name.orEmpty(),
            source = playSource,
            rank = state.rankOf(tune.id),
            categoryFilter = state.selectedCategoryName,
            fromSearch = fromSearch,
            isActiveRingtone = tune.id == state.activeRingtoneId,
        )
        previewedTuneIds += tune.id

        previewPlayer.play(tune.id, playbackUrl)
    }

    /** While the keyboard is open the empty state drops its logo and scrolls to the CTA. */
    private fun onImeChanged(visible: Boolean) {
        imeVisible = visible
        emptyStateImage.isVisible = !visible
        if (visible && emptyScroll.isVisible) ensureCreateCtaVisible()
    }

    private fun ensureCreateCtaVisible() {
        emptyScroll.post {
            val target = createCta.bottom + emptyScroll.paddingBottom - emptyScroll.height + emptyScroll.paddingTop
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
    }

    private fun resetHomeForReturn() {
        viewModel.flushPendingSearchTracking()
        previewedTuneIds.clear()
        stopPlayback()
        suppressSearchUpdates = true
        searchInput.setText("")
        suppressSearchUpdates = false
        viewModel.resetToAllTunes()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}
