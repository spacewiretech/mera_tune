package com.spacewire.meratune

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.spacewire.meratune.ui.HomeViewModel
import com.spacewire.meratune.ui.PlaybackSessionStats
import com.spacewire.meratune.ui.PreviewPlayerController
import com.spacewire.meratune.ui.TuneAdapter
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.StartupPermissionRequester
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.launch

class Home : AppCompatActivity() {

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var tuneAdapter: TuneAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var searchInput: EditText
    private var suppressSearchUpdates = false
    private var isNavigating = false

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
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

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
        mixpanelAnalytics().trackScreenViewed(AnalyticsScreen.HOME)
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
                ringtoneSetController.start(
                    tune,
                    SetEntryContext(
                        rank = viewModel.uiState.value.rankOf(tune.id),
                        wasPreviewed = tune.id in previewedTuneIds,
                    ),
                )
            },
        )

        findViewById<RecyclerView>(R.id.tunesRecycler).apply {
            layoutManager = LinearLayoutManager(this@Home)
            adapter = tuneAdapter
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
    }

    private fun setupEmptyState() {
        GradientTextHelper.applyHorizontalGradient(
            findViewById(R.id.emptyStateTitleLine2),
            R.color.gradient_pink,
            R.color.gradient_orange,
        )

        findViewById<TextView>(R.id.createRingtoneButton).setOnClickListener {
            if (isNavigating) return@setOnClickListener
            isNavigating = true
            val query = searchInput.text.toString().trim()
            viewModel.flushPendingSearchTracking()
            mixpanelAnalytics().trackCreateRingtoneCtaTapped(
                source = AnalyticsSource.SEARCH_BAR,
                prefillNameLength = query.length,
            )
            startActivity(CreateRingtoneActivity.intent(this, query, CreationEntryPoint.SEARCH_BAR))
        }
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

                    categoryAdapter.submitList(state.categories, state.selectedCategoryId)
                    tuneAdapter.submitList(state.filteredTunes, state.playingTuneId, state.activeRingtoneId)

                    val showEmptyState = state.showSearchEmptyState
                    findViewById<View>(R.id.searchEmptyState).visibility =
                        if (showEmptyState) View.VISIBLE else View.GONE
                    findViewById<RecyclerView>(R.id.tunesRecycler).visibility =
                        if (showEmptyState) View.GONE else View.VISIBLE

                    if (showEmptyState) {
                        findViewById<TextView>(R.id.emptyStateMessage).text = getString(
                            R.string.empty_search_message,
                            state.searchQuery.trim(),
                        )
                    }

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
    }

    override fun onPause() {
        super.onPause()
        resetHomeForReturn()
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
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
