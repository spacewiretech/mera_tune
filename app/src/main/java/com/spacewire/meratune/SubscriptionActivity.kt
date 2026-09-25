package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cashfree.pg.api.CFPaymentGatewayService
import com.cashfree.pg.core.api.CFSubscriptionSession
import com.cashfree.pg.core.api.callback.CFSubscriptionResponseCallback
import com.cashfree.pg.core.api.exception.CFException
import com.cashfree.pg.core.api.subscription.upi.CFSubsUpi
import com.cashfree.pg.core.api.subscription.upi.CFSubsUpiPayment
import com.cashfree.pg.core.api.utils.CFErrorResponse
import com.cashfree.pg.core.api.utils.CFSubscriptionResponse
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.PaywallDismissMethod
import com.spacewire.meratune.analytics.PaywallEntryPoint
import com.spacewire.meratune.analytics.PlayEndReason
import com.spacewire.meratune.analytics.firebaseAnalytics
import com.spacewire.meratune.analytics.metaAnalytics
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.SubscriptionApiException
import com.spacewire.meratune.data.SubscriptionFailureReason
import com.spacewire.meratune.data.SubscriptionRepository
import com.spacewire.meratune.data.SubscriptionVideoRepository
import com.spacewire.meratune.model.PaymentApp
import com.spacewire.meratune.ui.PaymentAppBottomSheet
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.enableLightEdgeToEdge
import kotlinx.coroutines.launch

class SubscriptionActivity : AppCompatActivity(), CFSubscriptionResponseCallback {

    private val subscriptionRepository = SubscriptionRepository()
    private val subscriptionVideoRepository by lazy { SubscriptionVideoRepository(this) }
    private var pendingSubscriptionId: String? = null
    private var playingVideoUrl: String? = null
    private var pendingAuthAmount: Double? = null
    private var isProcessingPayment = false
    private var verifyInFlight = false

    /** A verify request is running; redone after a recreation, which cancels it. */
    private var verifyPending = false
    private var selectedPaymentApp: PaymentApp = PaymentApp.DEFAULT
    private var attempt = 0
    private var previousStatus: String? = null
    private var videoCompleted = false
    private var videoErrorTracked = false
    private var entryPoint: String? = null

    /** Set before every programmatic finish, so only a system-back finish is a `paywall_dismissed`. */
    private var finishingWithoutDismiss = false
    private var dismissTracked = false
    private var logoutHandled = false

    /** Enabled only while a verify runs: back would finish the paywall and cancel the conversion. */
    private val verifyBackBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }
    private var videoPlayer: ExoPlayer? = null
    private lateinit var videoPlayerView: PlayerView
    private lateinit var playButton: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_subscription)
        onBackPressedDispatcher.addCallback(this, verifyBackBlocker)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.subscriptionRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val installedApps = PaymentApp.installed(packageManager)
        selectedPaymentApp = installedApps.firstOrNull() ?: PaymentApp.DEFAULT
        // Restored before the callback is set: Cashfree replays a stored checkout result into it.
        if (savedInstanceState != null) {
            restoreCheckoutState(savedInstanceState)
        } else {
            previousStatus = AuthStore(this).getStatus()
            entryPoint = PaywallEntryPoint.derive(mixpanelAnalytics().currentScreen, previousStatus)
        }

        try {
            CFPaymentGatewayService.getInstance().setSubscriptionCheckoutCallback(this)
        } catch (e: CFException) {
            e.printStackTrace()
        }

        setupActions()
        setupSubscriptionVideo()
        bindFeatureRows()
        bindSelectedPaymentApp()
        // Cashfree delivers the verify callback only once, to the instance that was destroyed.
        if (savedInstanceState?.getBoolean(STATE_VERIFY_PENDING) == true) {
            verifySubscription(pendingSubscriptionId)
        }
        if (savedInstanceState == null) {
            mixpanelAnalytics().trackSubscriptionScreenViewed(
                userStatus = previousStatus,
                installedAppCount = installedApps.size,
                entryPoint = entryPoint,
            )
            metaAnalytics().trackSubscriptionScreenViewed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_SUBSCRIPTION_ID, pendingSubscriptionId)
        pendingAuthAmount?.let { outState.putDouble(STATE_PENDING_AUTH_AMOUNT, it) }
        outState.putString(STATE_PAYMENT_APP, selectedPaymentApp.name)
        outState.putInt(STATE_ATTEMPT, attempt)
        outState.putString(STATE_PREVIOUS_STATUS, previousStatus)
        outState.putBoolean(STATE_VIDEO_COMPLETED, videoCompleted)
        outState.putBoolean(STATE_VIDEO_ERROR_TRACKED, videoErrorTracked)
        outState.putBoolean(STATE_VERIFY_PENDING, verifyPending)
        outState.putString(STATE_ENTRY_POINT, entryPoint)
    }

    private fun restoreCheckoutState(state: Bundle) {
        pendingSubscriptionId = state.getString(STATE_PENDING_SUBSCRIPTION_ID)
        if (state.containsKey(STATE_PENDING_AUTH_AMOUNT)) {
            pendingAuthAmount = state.getDouble(STATE_PENDING_AUTH_AMOUNT)
        }
        state.getString(STATE_PAYMENT_APP)
            ?.let { name -> PaymentApp.entries.firstOrNull { it.name == name } }
            ?.let { selectedPaymentApp = it }
        attempt = state.getInt(STATE_ATTEMPT)
        previousStatus = state.getString(STATE_PREVIOUS_STATUS)
        videoCompleted = state.getBoolean(STATE_VIDEO_COMPLETED)
        videoErrorTracked = state.getBoolean(STATE_VIDEO_ERROR_TRACKED)
        entryPoint = state.getString(STATE_ENTRY_POINT)
    }

    private fun bindFeatureRows() {
        bindFeatureRow(R.id.featureOneRow, R.drawable.ic_lock_white, getString(R.string.subscription_feature_auth, "3"))
        bindFeatureRow(R.id.featureTwoRow, R.drawable.ic_lock_white, getString(R.string.subscription_feature_trial))
        bindFeatureRow(R.id.featureThreeRow, R.drawable.ic_diamond_white, getString(R.string.subscription_feature_autopay, "299"))
    }

    private fun bindFeatureRow(rowId: Int, iconRes: Int, text: String) {
        val row = findViewById<View>(rowId)
        row.findViewById<android.widget.ImageView>(R.id.featureIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.featureText).text = text
    }

    private fun setupActions() {
        findViewById<View>(R.id.languageButton).setOnClickListener {
            startActivity(LanguageSelectionActivity.intent(this))
        }

        findViewById<TextView>(R.id.logoutButton).setOnClickListener {
            if (logoutHandled) return@setOnClickListener
            logoutHandled = true
            mixpanelAnalytics().logout(this, source = AnalyticsSource.SUBSCRIPTION)
            metaAnalytics().clearUserId()
            firebaseAnalytics().clearUserId()
            startActivity(
                PhoneAuthActivity.intent(this).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
            finishWithoutDismiss()
        }

        findViewById<TextView>(R.id.tryNowButton).setOnClickListener {
            if (isProcessingPayment) return@setOnClickListener
            attempt += 1
            val appInstalled = selectedPaymentApp.isInstalled(packageManager)
            mixpanelAnalytics().trackSubscriptionCtaTapped(
                paymentApp = selectedPaymentApp,
                paymentAppInstalled = appInstalled,
                attempt = attempt,
                videoCompleted = videoCompleted,
            )
            if (!appInstalled) {
                trackFailure(
                    stage = SubscriptionFailureReason.STAGE_PRECHECK,
                    reason = if (PaymentApp.installed(packageManager).isEmpty()) {
                        SubscriptionFailureReason.NO_PAYMENT_APP_INSTALLED
                    } else {
                        SubscriptionFailureReason.PAYMENT_APP_NOT_INSTALLED
                    },
                )
                Toast.makeText(
                    this,
                    getString(R.string.subscription_payment_app_not_installed, selectedPaymentApp.displayName),
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            startSubscriptionCheckout()
        }

        findViewById<View>(R.id.paymentAppSelector).setOnClickListener {
            if (isProcessingPayment) return@setOnClickListener
            val installedApps = PaymentApp.installed(packageManager)
            if (installedApps.isEmpty()) {
                trackFailure(
                    stage = SubscriptionFailureReason.STAGE_PRECHECK,
                    reason = SubscriptionFailureReason.NO_PAYMENT_APP_INSTALLED,
                )
                Toast.makeText(this, R.string.subscription_no_payment_app_installed, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            PaymentAppBottomSheet(this, selectedPaymentApp, installedApps) { app ->
                mixpanelAnalytics().trackPaymentAppSelected(
                    paymentApp = app,
                    previousPaymentApp = selectedPaymentApp,
                )
                selectedPaymentApp = app
                bindSelectedPaymentApp()
            }.show()
        }
    }

    private fun setupSubscriptionVideo() {
        videoPlayerView = findViewById(R.id.subscriptionVideoPlayer)
        playButton = findViewById(R.id.playButton)

        videoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        500,
                        1_000,
                    )
                    .build(),
            )
            .build()
            .also { player ->
            videoPlayerView.player = player
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playButton.visibility = if (isPlaying) View.GONE else View.VISIBLE
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        if (!videoCompleted) {
                            videoCompleted = true
                            mixpanelAnalytics().trackSubscriptionVideoEnded(
                                endReason = PlayEndReason.COMPLETED,
                                durationMs = player.knownDurationMs(),
                            )
                        }
                        player.seekTo(0)
                        player.pause()
                        playButton.visibility = View.VISIBLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playButton.visibility = View.VISIBLE
                    if (!videoErrorTracked) {
                        videoErrorTracked = true
                        mixpanelAnalytics().trackSubscriptionVideoEnded(
                            endReason = PlayEndReason.ERROR,
                            errorCode = error.errorCodeName.removePrefix("ERROR_CODE_"),
                            durationMs = player.knownDurationMs(),
                        )
                    }
                    Toast.makeText(
                        this@SubscriptionActivity,
                        R.string.subscription_video_error,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            })
        }

        playButton.visibility = View.VISIBLE
        playButton.setOnClickListener { videoPlayer?.play() }

        videoPlayerView.setOnClickListener {
            val player = videoPlayer ?: return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                }
                player.play()
            }
        }

        loadSubscriptionVideo()
    }

    private fun loadSubscriptionVideo() {
        val fallbackUrl = getString(R.string.subscription_video_url)
        val localeCode = ProfileStore(this).getLocaleCode()
        val startUrl = subscriptionVideoRepository.cachedVideoUrl(localeCode) ?: fallbackUrl
        playSubscriptionVideo(startUrl)

        lifecycleScope.launch {
            val remoteUrl = runCatching {
                subscriptionVideoRepository.fetchVideoUrl(localeCode)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
            subscriptionVideoRepository.cacheVideoUrl(localeCode, remoteUrl)
            val player = videoPlayer ?: return@launch
            if (remoteUrl != playingVideoUrl && player.currentPosition < 1_000L) {
                playSubscriptionVideo(remoteUrl)
            }
        }
    }

    private fun Player.knownDurationMs(): Long? = duration.takeIf { it > 0L }

    private fun playSubscriptionVideo(url: String) {
        val player = videoPlayer ?: return
        if (playingVideoUrl == url && player.mediaItemCount > 0) return
        playingVideoUrl = url
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.play()
    }

    private fun bindSelectedPaymentApp() {
        findViewById<TextView>(R.id.paymentAppIcon).apply {
            setBackgroundResource(selectedPaymentApp.iconBackgroundRes)
            text = selectedPaymentApp.iconLabel
        }
        findViewById<TextView>(R.id.paymentAppName).text = selectedPaymentApp.displayName
    }

    private fun startSubscriptionCheckout() {
        val userId = AuthStore(this).getUserId()
        if (userId <= 0L) {
            trackFailure(
                stage = SubscriptionFailureReason.STAGE_PRECHECK,
                reason = SubscriptionFailureReason.NOT_LOGGED_IN,
            )
            Toast.makeText(this, R.string.auth_generic_error, Toast.LENGTH_SHORT).show()
            finishWithoutDismiss()
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            subscriptionRepository.createSubscription(userId)
                .onSuccess { response ->
                    pendingSubscriptionId = response.subscriptionId
                    pendingAuthAmount = response.authAmount
                    bindPricing(response.authAmount, response.recurringAmount)
                    mixpanelAnalytics().trackSubscriptionStarted(
                        paymentApp = selectedPaymentApp,
                        authAmount = response.authAmount,
                        recurringAmount = response.recurringAmount,
                        attempt = attempt,
                    )
                    metaAnalytics().trackSubscriptionStarted(
                        authAmount = response.authAmount,
                        recurringAmount = response.recurringAmount,
                    )
                    openCashfreeCheckout(
                        subscriptionId = response.subscriptionId!!,
                        sessionId = response.subscriptionSessionId!!,
                        environment = response.environment ?: "sandbox",
                        paymentApp = selectedPaymentApp,
                    )
                }
                .onFailure { error ->
                    trackFailure(
                        stage = SubscriptionFailureReason.STAGE_CREATE,
                        reason = SubscriptionFailureReason.forCreate(error),
                        httpStatus = SubscriptionFailureReason.httpStatus(error),
                    )
                    val message = (error as? SubscriptionApiException)?.message
                        ?: getString(R.string.subscription_error)
                    Toast.makeText(this@SubscriptionActivity, message, Toast.LENGTH_LONG).show()
                    setLoading(false)
                }
        }
    }

    private fun bindPricing(authAmount: Double?, recurringAmount: Double?) {
        authAmount?.let {
            findViewById<View>(R.id.featureOneRow)
                .findViewById<TextView>(R.id.featureText)
                .text = getString(R.string.subscription_feature_auth, formatRupee(it))
        }
        recurringAmount?.let {
            findViewById<View>(R.id.featureThreeRow)
                .findViewById<TextView>(R.id.featureText)
                .text = getString(R.string.subscription_feature_autopay, formatRupee(it))
        }
    }

    private fun formatRupee(amount: Double): String {
        return if (amount % 1.0 == 0.0) amount.toInt().toString() else amount.toString()
    }

    private fun openCashfreeCheckout(
        subscriptionId: String,
        sessionId: String,
        environment: String,
        paymentApp: PaymentApp,
    ) {
        try {
            val cfEnvironment = if (environment.equals("production", ignoreCase = true)) {
                CFSubscriptionSession.Environment.PRODUCTION
            } else {
                CFSubscriptionSession.Environment.SANDBOX
            }

            val session = CFSubscriptionSession.CFSubscriptionSessionBuilder()
                .setEnvironment(cfEnvironment)
                .setSubscriptionSessionID(sessionId)
                .setSubscriptionId(subscriptionId)
                .build()

            val upi = CFSubsUpi.CFSubsUpiBuilder()
                .setMode(CFSubsUpi.Mode.INTENT)
                .setUPIID(paymentApp.packageName)
                .build()

            val payment = CFSubsUpiPayment.CFSubsUpiPaymentBuilder()
                .setSubscriptionSession(session)
                .setSubsUpi(upi)
                .build()

            isProcessingPayment = true
            CFPaymentGatewayService.getInstance().doSubscriptionPayment(this, payment)
        } catch (e: CFException) {
            isProcessingPayment = false
            setLoading(false)
            trackFailure(
                stage = SubscriptionFailureReason.STAGE_CHECKOUT,
                reason = SubscriptionFailureReason.SDK_EXCEPTION,
                paymentApp = paymentApp,
            )
            Toast.makeText(this, R.string.subscription_error, Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    override fun onSubscriptionVerify(cfSubscriptionResponse: CFSubscriptionResponse) {
        // After a recreation mid-checkout the replayed callback still carries the id.
        verifySubscription(
            pendingSubscriptionId ?: cfSubscriptionResponse.subscriptionId?.takeIf { it.isNotBlank() },
        )
    }

    private fun verifySubscription(subscriptionId: String?) {
        if (verifyInFlight) return
        val userId = AuthStore(this).getUserId()
        if (userId <= 0L || subscriptionId.isNullOrBlank()) {
            trackFailure(
                stage = SubscriptionFailureReason.STAGE_VERIFY,
                reason = if (userId <= 0L) {
                    SubscriptionFailureReason.NOT_LOGGED_IN
                } else {
                    SubscriptionFailureReason.MISSING_SUBSCRIPTION_ID
                },
            )
            setLoading(false)
            isProcessingPayment = false
            return
        }

        verifyInFlight = true
        verifyPending = true
        verifyBackBlocker.isEnabled = true
        pendingSubscriptionId = subscriptionId
        isProcessingPayment = true
        setLoading(true)
        lifecycleScope.launch {
            subscriptionRepository.verifySubscription(userId, subscriptionId)
                .onSuccess { response ->
                    val user = response.user
                    if (response.active == true && user != null) {
                        // verifyInFlight stays set: the three conversions below fire once per paywall.
                        AuthStore(this@SubscriptionActivity).saveUser(user)
                        ProfileStore(this@SubscriptionActivity).saveUser(user.name.orEmpty(), user.phone)
                        val amount = pendingAuthAmount ?: 3.0
                        val analytics = mixpanelAnalytics()
                        analytics.identifyUser(user)
                        analytics.trackTrialPaymentCompleted(
                            paymentApp = selectedPaymentApp,
                            subscriptionId = subscriptionId,
                            amount = amount,
                            attempt = attempt.takeIf { it > 0 },
                            previousStatus = previousStatus,
                        )
                        metaAnalytics().identifyUser(user)
                        metaAnalytics().trackTrialPaymentCompleted(
                            amount = amount,
                            subscriptionId = subscriptionId,
                        )
                        metaAnalytics().flush()
                        firebaseAnalytics().identifyUser(user)
                        firebaseAnalytics().trackTrialPaymentCompleted(
                            amount = amount,
                            subscriptionId = subscriptionId,
                        )
                        AuthNavigator.navigateAfterAuth(this@SubscriptionActivity, user)
                        finishWithoutDismiss()
                    } else {
                        verifyInFlight = false
                        verifyBackBlocker.isEnabled = false
                        if (response.active == true) {
                            trackFailure(
                                stage = SubscriptionFailureReason.STAGE_VERIFY,
                                reason = SubscriptionFailureReason.MISSING_USER,
                            )
                        } else {
                            trackFailure(
                                stage = SubscriptionFailureReason.STAGE_VERIFY,
                                reason = SubscriptionFailureReason.PENDING,
                                cashfreeStatus = response.status,
                            )
                        }
                        Toast.makeText(
                            this@SubscriptionActivity,
                            R.string.subscription_pending,
                            Toast.LENGTH_LONG,
                        ).show()
                        setLoading(false)
                    }
                }
                .onFailure { error ->
                    verifyInFlight = false
                    verifyBackBlocker.isEnabled = false
                    trackFailure(
                        stage = SubscriptionFailureReason.STAGE_VERIFY,
                        reason = SubscriptionFailureReason.forVerify(error),
                        httpStatus = SubscriptionFailureReason.httpStatus(error),
                    )
                    Toast.makeText(
                        this@SubscriptionActivity,
                        R.string.subscription_pending,
                        Toast.LENGTH_LONG,
                    ).show()
                    setLoading(false)
                }
            isProcessingPayment = false
            verifyPending = false
        }
    }

    override fun onSubscriptionFailure(cfErrorResponse: CFErrorResponse) {
        isProcessingPayment = false
        setLoading(false)
        val message = paymentFailureMessage(cfErrorResponse)
        Log.e(TAG, "Cashfree checkout failed: ${cfErrorResponse.message} code=${cfErrorResponse.code}")
        trackFailure(
            stage = SubscriptionFailureReason.STAGE_CHECKOUT,
            reason = SubscriptionFailureReason.forCheckout(cfErrorResponse.code),
            cfErrorCode = cfErrorResponse.code,
        )
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** [reason] is a bounded [SubscriptionFailureReason] value, never message text. */
    private fun trackFailure(
        stage: String,
        reason: String,
        paymentApp: PaymentApp = selectedPaymentApp,
        cfErrorCode: String? = null,
        httpStatus: Int? = null,
        cashfreeStatus: String? = null,
    ) {
        mixpanelAnalytics().trackSubscriptionFailed(
            stage = stage,
            failureReason = reason,
            paymentApp = paymentApp,
            cfErrorCode = cfErrorCode,
            httpStatus = httpStatus,
            cashfreeStatus = cashfreeStatus,
            attempt = attempt.takeIf { it > 0 },
        )
    }

    private fun paymentFailureMessage(cfErrorResponse: CFErrorResponse): String {
        val raw = cfErrorResponse.message.orEmpty()
        val code = cfErrorResponse.code.orEmpty()
        if (raw.contains("authentication", ignoreCase = true) || code.contains("authentication", ignoreCase = true)) {
            return getString(R.string.subscription_auth_failed)
        }
        if (raw.contains("trusted source", ignoreCase = true) ||
            raw.contains("play store", ignoreCase = true) ||
            code.contains("installer_package", ignoreCase = true)
        ) {
            return getString(R.string.subscription_integrity_failed)
        }
        return raw.ifBlank { getString(R.string.subscription_error) }
    }

    private fun setLoading(loading: Boolean) {
        findViewById<ProgressBar>(R.id.loadingIndicator).visibility =
            if (loading) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tryNowButton).isEnabled = !loading
        findViewById<View>(R.id.paymentAppSelector).isEnabled = !loading
        findViewById<View>(R.id.paymentAppSelector).alpha = if (loading) 0.7f else 1f
    }

    /**
     * System back is the only remaining way this screen finishes; [verifyBackBlocker] only swallows
     * back while a verify runs, and a paid user is never counted as a dismissal.
     */
    override fun onPause() {
        super.onPause()
        if (!isFinishing || finishingWithoutDismiss || dismissTracked || verifyInFlight) return
        dismissTracked = true
        mixpanelAnalytics().trackPaywallDismissed(
            entryPoint = entryPoint,
            dismissMethod = PaywallDismissMethod.SYSTEM_BACK,
            attempt = attempt,
            videoCompleted = videoCompleted,
        )
    }

    private fun finishWithoutDismiss() {
        finishingWithoutDismiss = true
        finish()
    }

    override fun onStop() {
        videoPlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        videoPlayerView.player = null
        videoPlayer?.release()
        videoPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SubscriptionActivity"
        private const val STATE_PENDING_SUBSCRIPTION_ID = "pending_subscription_id"
        private const val STATE_PENDING_AUTH_AMOUNT = "pending_auth_amount"
        private const val STATE_PAYMENT_APP = "selected_payment_app"
        private const val STATE_ATTEMPT = "checkout_attempt"
        private const val STATE_PREVIOUS_STATUS = "previous_status"
        private const val STATE_VIDEO_COMPLETED = "video_completed"
        private const val STATE_VIDEO_ERROR_TRACKED = "video_error_tracked"
        private const val STATE_VERIFY_PENDING = "verify_pending"
        private const val STATE_ENTRY_POINT = "entry_point"

        fun intent(context: Context): Intent = Intent(context, SubscriptionActivity::class.java)
    }
}
