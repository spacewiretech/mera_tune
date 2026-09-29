package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.calltheme.RingtoneSetController
import com.spacewire.meratune.calltheme.SetEntryContext
import com.spacewire.meratune.data.NameRingtonesRepository
import com.spacewire.meratune.data.RingtoneGenerationException
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.InsetDividerDecoration
import com.spacewire.meratune.ui.NameRingtoneAdapter
import com.spacewire.meratune.ui.NameRingtonesPolicy
import com.spacewire.meratune.ui.PlaybackSessionStats
import com.spacewire.meratune.ui.PreviewPlayerController
import com.spacewire.meratune.util.ActiveRingtoneStore
import com.spacewire.meratune.util.Haptics
import com.spacewire.meratune.util.InsetsUi
import com.spacewire.meratune.util.enableLightEdgeToEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Create flow, between the form and the song picker: ringtones that already sing the entered name
 * (`NameRingtonesRepository.fetchNameRingtones`, possibly made by other users; rows never carry a
 * user id). The form opens it only when that list is not empty.
 *
 * - The art previews a row (`tune_played` / `tune_play_ended`, `source` = `name_ringtones`).
 * - Set runs the single-shot set flow like Home. A row the user has not made yet is first recorded
 *   in their own list (`NameRingtonesRepository.claim`, a `generate-ringtone` cache hit), so the set
 *   ringtone carries their generation id; if that fails the row is set as listed. On success the
 *   ringtone is saved as active (personalized, like the Ready screen) and the flow goes Home.
 * - "Make Your Tune" continues to the song picker with the form's name and language.
 */
class NameRingtonesActivity : AppCompatActivity() {

    private lateinit var userName: String
    private lateinit var language: String
    private var rows: List<Tune> = emptyList()

    private lateinit var adapter: NameRingtoneAdapter
    private lateinit var recycler: RecyclerView

    private val repository by lazy { NameRingtonesRepository(this) }
    private var claimJob: Job? = null

    /** A claimed row whose set flow waits for [onResume] (the claim finished in the background). */
    private var pendingSet: Pair<Tune, SetEntryContext>? = null

    /** Tune id of the ringtone set on this screen; the screen then leaves for Home. */
    private var setTuneId: String? = null
    private var goHomePending = false
    private var isNavigating = false

    /** Rows with a `tune_played` in this visit; feeds `ringtone_set_started.was_previewed`. */
    private val previewedTuneIds = mutableSetOf<String>()

    private val previewPlayer: PreviewPlayerController by lazy {
        PreviewPlayerController(
            context = this,
            scope = lifecycleScope,
            listener = object : PreviewPlayerController.Listener {
                override fun onProgress(id: String, progress: Float) {
                    adapter.updateProgress(progress)
                }

                override fun onPlayingChanged(id: String, isPlaying: Boolean) {
                    adapter.setPreview(id, isPlaying)
                }

                override fun onEnded(id: String) {
                    releasePreview()
                }

                override fun onError(id: String, error: PlaybackException) {
                    Log.w(TAG, "Preview failed: ${error.errorCodeName}")
                    adapter.setPreview(null, isPlaying = false)
                    Toast.makeText(this@NameRingtonesActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
                }

                override fun onSessionEnded(stats: PlaybackSessionStats) {
                    mixpanelAnalytics().trackTunePlayEnded(source = AnalyticsSource.NAME_RINGTONES, stats = stats)
                }
            },
        )
    }

    private val setController = RingtoneSetController(
        activity = this,
        analyticsSource = AnalyticsSource.NAME_RINGTONES,
        categoryForTune = { tune -> tune.category?.name.orEmpty() },
        onSuccess = { tune, uri -> onRingtoneSet(tune, uri) },
        // Every row sings the user's name, claimed or not: its title never reaches analytics.
        personalizedForTune = { true },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
        val languageKey = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty().trim()
        val restoredRows = savedInstanceState?.getStringArrayList(STATE_ROWS)
            ?: intent.getStringArrayListExtra(EXTRA_ROWS)
        val listed = NameRingtonesPolicy.rowsToShow(NameRingtonesPolicy.decode(restoredRows))
        if (name.isEmpty() || languageKey.isEmpty() || listed.isEmpty()) {
            finish()
            return
        }
        userName = name
        language = languageKey
        rows = listed
        savedInstanceState?.let { state ->
            state.getStringArrayList(STATE_PREVIEWED_TUNE_IDS)?.let(previewedTuneIds::addAll)
            setTuneId = state.getString(STATE_SET_TUNE_ID)
        }

        enableLightEdgeToEdge()
        setContentView(R.layout.activity_name_ringtones)
        InsetsUi.padForSystemBarsAndIme(findViewById(R.id.nameRingtonesRoot))

        findViewById<TextView>(R.id.nameRingtonesTitle).text = getString(R.string.name_ringtones_title, userName)
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.makeTuneButton).setOnClickListener { onMakeTuneTapped() }

        adapter = NameRingtoneAdapter(onPlayClick = ::onPlayTapped, onSetClick = ::onSetTapped)
        recycler = findViewById(R.id.nameRingtonesRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addItemDecoration(
            InsetDividerDecoration(this, insetStartDp = ROW_DIVIDER_INSET_DP, insetEndDp = ROW_DIVIDER_INSET_DP),
        )
        adapter.submit(rows)

        // Recreated after a successful set: finish the trip Home.
        setTuneId?.let { id ->
            adapter.setActive(id)
            goHomePending = true
        }
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
        if (goHomePending) {
            goHome()
            return
        }
        pendingSet?.let { (tune, entry) ->
            pendingSet = null
            setController.start(tune, entry)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::adapter.isInitialized) previewPlayer.pause()
    }

    override fun onStop() {
        super.onStop()
        if (::adapter.isInitialized) releasePreview()
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) previewPlayer.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::adapter.isInitialized) return
        outState.putStringArrayList(STATE_ROWS, NameRingtonesPolicy.encode(rows))
        outState.putStringArrayList(STATE_PREVIEWED_TUNE_IDS, ArrayList(previewedTuneIds))
        outState.putString(STATE_SET_TUNE_ID, setTuneId)
    }

    // ---------------------------------------------------------------------------------------------
    // Interaction
    // ---------------------------------------------------------------------------------------------

    /** Art tap: toggles the row's preview; a new preview sends `tune_played`. */
    private fun onPlayTapped(tune: Tune, rank: Int) {
        val url = tune.tuneUrl.trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
            return
        }
        val startsNewPreview = previewPlayer.currentId != tune.id
        if (startsNewPreview) adapter.setPreview(tune.id, isPlaying = false)
        previewPlayer.toggle(tune.id, url)
        if (startsNewPreview) {
            previewedTuneIds += tune.id
            mixpanelAnalytics().trackTunePlayed(
                tuneId = tune.id,
                category = tune.category?.name.orEmpty(),
                source = AnalyticsSource.NAME_RINGTONES,
                rank = rank,
            )
        }
    }

    /**
     * Set pill: the single-shot set flow (Home's). Ignored while a claim or a set flow is open, or
     * once a ringtone was set here. An unclaimed row is recorded for the user first.
     */
    private fun onSetTapped(tune: Tune, rank: Int) {
        if (isNavigating || setTuneId != null || claimJob?.isActive == true || !setController.isIdle) return
        val row = rows.firstOrNull { it.id == tune.id } ?: tune
        val entry = SetEntryContext(rank = rank, wasPreviewed = row.id in previewedTuneIds)
        releasePreview()
        Haptics.select(recycler)
        if (!NameRingtonesPolicy.needsClaim(row)) {
            setController.start(row, entry)
            return
        }
        adapter.setClaiming(row.id)
        claimJob = lifecycleScope.launch {
            val claimed = claimOrNull(row)
            adapter.setClaiming(null)
            if (claimed != null) {
                rows = NameRingtonesPolicy.replaceRow(rows, claimed)
                adapter.submit(rows)
            }
            val target = claimed ?: row
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                setController.start(target, entry)
            } else {
                pendingSet = target to entry
            }
        }
    }

    /** The claimed row, or `null` (the row is then set as listed) on any failure or after [CLAIM_TIMEOUT_MS]. */
    private suspend fun claimOrNull(row: Tune): Tune? = try {
        withTimeoutOrNull(CLAIM_TIMEOUT_MS) { repository.claim(row, userName, language) }
            .also { if (it == null) Log.w(TAG, "claim timed out") }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val reason = (error as? RingtoneGenerationException)?.code?.name ?: error.javaClass.simpleName
        Log.w(TAG, "claim failed: $reason")
        null
    }

    private fun onMakeTuneTapped() {
        if (isNavigating || setTuneId != null || claimJob?.isActive == true || !setController.isIdle) return
        isNavigating = true
        releasePreview()
        startActivity(ChooseSongActivity.intent(this, userName, language))
    }

    private fun onRingtoneSet(tune: Tune, uri: Uri) {
        // Like the Ready screen: the personalized copy itself (title, file, generation id once
        // claimed), so Home can show this ringtone as Active rather than its base tune.
        ActiveRingtoneStore(this).save(tune, uri, personalized = true)
        setTuneId = tune.id
        adapter.setActive(tune.id)
        Haptics.confirm(recycler)
        // A short beat on the "Active" pill, then the create flow ends on Home.
        lifecycleScope.launch {
            delay(GO_HOME_DELAY_MS)
            goHome()
        }
    }

    /** Home (CLEAR_TOP, like the Ready screen's "Home par jayen"); waits for [onResume] if not in front. */
    private fun goHome() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            goHomePending = true
            return
        }
        if (isFinishing) return
        goHomePending = false
        isNavigating = true
        startActivity(
            Intent(this, Home::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun releasePreview() {
        previewPlayer.release()
        adapter.setPreview(null, isPlaying = false)
    }

    companion object {
        private const val TAG = "NameRingtones"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_LANGUAGE = "extra_language"
        private const val EXTRA_ROWS = "extra_rows"
        private const val STATE_ROWS = "state_rows"
        private const val STATE_PREVIEWED_TUNE_IDS = "state_previewed_tune_ids"
        private const val STATE_SET_TUNE_ID = "state_set_tune_id"
        private const val ROW_DIVIDER_INSET_DP = 36f

        /** A cache hit answers in about a second; past this the row is set as listed. */
        private const val CLAIM_TIMEOUT_MS = 8_000L
        private const val GO_HOME_DELAY_MS = 600L

        /**
         * @param name the validated display name; @param language a `Languages.storageValue`;
         * @param rows `NameRingtonesPolicy.rowsToShow` of the lookup, not empty.
         */
        fun intent(context: Context, name: String, language: String, rows: List<Tune>): Intent {
            return Intent(context, NameRingtonesActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LANGUAGE, language)
                .putStringArrayListExtra(EXTRA_ROWS, NameRingtonesPolicy.encode(rows))
        }
    }
}
