package com.spacewire.meratune

import android.net.Uri
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
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.calltheme.RingtoneSetController
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.CategoryAdapter
import com.spacewire.meratune.ui.HomeViewModel
import com.spacewire.meratune.ui.TuneAdapter
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.StartupPermissionRequester
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Home : AppCompatActivity() {

    private val viewModel: HomeViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var currentPlayingTune: Tune? = null
    private var progressJob: Job? = null

    private lateinit var tuneAdapter: TuneAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var searchInput: EditText
    private var suppressSearchUpdates = false
    private var hasTrackedHomeView = false

    private val ringtoneSetController = RingtoneSetController(
        activity = this,
        analyticsSource = SOURCE_HOME,
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
        startupPermissionRequester.requestIfNeeded()
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
            onSetClick = { tune -> ringtoneSetController.start(tune) },
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
            val query = searchInput.text.toString().trim()
            mixpanelAnalytics().trackCreateRingtoneCtaTapped(
                source = "empty_search",
                prefillNameLength = query.length,
            )
            startActivity(CreateRingtoneActivity.intent(this, query))
        }
    }

    private fun setupErrorRetry() {
        findViewById<TextView>(R.id.errorText).setOnClickListener {
            viewModel.loadHomeData()
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

                    if (state.playingTuneId == null && player?.isPlaying == true) {
                        stopPlayback()
                    }

                    if (!hasTrackedHomeView &&
                        !state.isLoading &&
                        state.errorMessage == null &&
                        state.categories.isNotEmpty()
                    ) {
                        hasTrackedHomeView = true
                        mixpanelAnalytics().trackHomeViewed(
                            tuneCount = state.tunes.size,
                            categoryCount = state.categories.size,
                        )
                    }
                }
            }
        }
    }

    private fun togglePlayback(tune: Tune) {
        if (currentPlayingTune?.id == tune.id && player?.isPlaying == true) {
            viewModel.onPlayToggle(tune.id)
            stopPlayback()
            return
        }

        stopPlayback()
        viewModel.onPlayToggle(tune.id)
        currentPlayingTune = tune

        val playbackUrl = tune.tuneUrl.trim()
        if (playbackUrl.isBlank()) {
            showPlaybackError("Empty tune URL for ${tune.name}")
            viewModel.onPlayToggle(tune.id)
            currentPlayingTune = null
            return
        }

        Log.d(TAG, "Starting playback for ${tune.name}: $playbackUrl")

        mixpanelAnalytics().trackTunePlayed(
            tuneId = tune.id,
            category = tune.category?.name.orEmpty(),
            source = SOURCE_HOME,
        )

        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        viewModel.onPlayToggle(tune.id)
                        stopPlayback()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates(resetRing = false)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Playback failed for $playbackUrl", error)
                    showPlaybackError(error.message ?: getString(R.string.playback_error))
                    viewModel.onPlayToggle(tune.id)
                    stopPlayback()
                }
            })
            setMediaItem(MediaItem.fromUri(Uri.parse(playbackUrl)))
            prepare()
            play()
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                val exoPlayer = player
                if (exoPlayer != null && exoPlayer.isPlaying) {
                    val duration = exoPlayer.duration
                    if (duration > 0L) {
                        val progress = exoPlayer.currentPosition.toFloat() / duration
                        tuneAdapter.updatePlaybackProgress(progress)
                    }
                }
                delay(50)
            }
        }
    }

    private fun stopProgressUpdates(resetRing: Boolean = true) {
        progressJob?.cancel()
        progressJob = null
        if (resetRing) {
            tuneAdapter.updatePlaybackProgress(0f)
        }
    }

    private fun showPlaybackError(detail: String) {
        Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
        Log.e(TAG, detail)
    }

    private fun stopPlayback() {
        stopProgressUpdates()
        player?.release()
        player = null
        currentPlayingTune = null
    }

    companion object {
        private const val TAG = "HomePlayback"
        private const val SOURCE_HOME = "home"
    }

    override fun onPause() {
        super.onPause()
        resetHomeForReturn()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshActiveRingtone()
    }

    private fun resetHomeForReturn() {
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
