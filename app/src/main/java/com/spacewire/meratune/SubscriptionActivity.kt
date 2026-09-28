package com.spacewire.meratune

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.transition.Fade
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.cashfree.pg.core.api.base.CFPayment
import com.cashfree.pg.core.api.callback.CFSubscriptionResponseCallback
import com.cashfree.pg.core.api.exception.CFException
import com.cashfree.pg.core.api.subscription.CFSubscriptionPayment
import com.cashfree.pg.core.api.subscription.upi.CFSubsUpi
import com.cashfree.pg.core.api.subscription.upi.CFSubsUpiPayment
import com.cashfree.pg.core.api.utils.CFErrorResponse
import com.cashfree.pg.core.api.utils.CFSubscriptionResponse
import com.cashfree.pg.core.api.webcheckout.CFWebCheckoutTheme
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
import com.spacewire.meratune.data.User
import com.spacewire.meratune.model.PaymentApp
import com.spacewire.meratune.ui.CtaButtons
import com.spacewire.meratune.ui.FaqAccordionController
import com.spacewire.meratune.ui.HomeButtonRoute
import com.spacewire.meratune.ui.PaymentAppBadge
import com.spacewire.meratune.ui.PaymentAppBottomSheet
import com.spacewire.meratune.ui.PaywallUiPolicy
import com.spacewire.meratune.ui.PaywallUiState
import com.spacewire.meratune.ui.VerifyTrigger
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.enableLightEdgeToEdge
import java.util.Locale
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
    /** Set in onCreate: the first installed UPI app, else [PaymentApp.UPI_ID]. */
    private var selectedPaymentApp: PaymentApp = PaymentApp.UPI_ID
    private var attempt = 0
    private var previousStatus: String? = null
    private var videoCompleted = false
    private var videoErrorTracked = false
    private var entryPoint: String? = null

    /** Set before every programmatic finish, so only a system-back finish is a `paywall_dismissed`. */
    private var finishingWithoutDismiss = false
    private var dismissTracked = false
    private var logoutHandled = false

    /**
     * System back on the paywall itself leaves to Home like the Home button (the browse choice is
     * remembered), so back never closes the app from here. Registered first: [verifyBackBlocker]
     * and [stateBackCallback] win while they are enabled.
     */
    private val paywallBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = leaveToHome(PaywallDismissMethod.SYSTEM_BACK)
    }

    /** Enabled only while a verify runs: back would finish the paywall and cancel the conversion. */
    private val verifyBackBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    /**
     * Back on Pending / Failed returns to the paywall (not a dismissal). Registered after
     * [verifyBackBlocker], so it wins while enabled; it swallows back itself while a manual
     * re-check verify runs, so the state cannot change under an in-flight verify.
     */
    private val stateBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!verifyInFlight) showState(PaywallUiState.PAYWALL)
        }
    }
    private var uiState = PaywallUiState.PAYWALL

    /** Failed lists the bank / funds / details reasons only after a `payment_failed` checkout. */
    private var failedShowsReasons = false
    private var pendingRecurringAmount: Double? = null

    /**
     * The amounts the paywall shows and the subscription events send: create-subscription's
     * values once it answered, else the defaults 3 / 299.
     */
    private var shownAuthAmount = DEFAULT_AUTH_AMOUNT
    private var shownRecurringAmount = DEFAULT_RECURRING_AMOUNT
    private val authAmountLabel: String get() = formatRupee(shownAuthAmount)
    private val recurringAmountLabel: String get() = formatRupee(shownRecurringAmount)

    /** `paywall_dismissed.dismiss_method` for the next finish (set by [leaveToHome]). */
    private var dismissMethod = PaywallDismissMethod.SYSTEM_BACK
    private var pendingRingAnimator: ObjectAnimator? = null
    /** Set in onStart / cleared in onStop; lifecycle.currentState is still CREATED inside onStart. */
    private var screenStarted = false
    private var videoPlayer: ExoPlayer? = null
    private lateinit var videoPlayerView: PlayerView
    private lateinit var playButton: ImageView
    private lateinit var videoScrim: View
    private lateinit var rootView: ViewGroup
    private lateinit var paywallContainer: View
    private lateinit var pendingContainer: View
    private lateinit var failedContainer: View
    private lateinit var failedReasons: View
    private lateinit var pendingRing: ImageView
    private lateinit var tryNowButton: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var pendingCheckButton: TextView
    private lateinit var pendingCheckProgress: ProgressBar
    private lateinit var paymentAppSelector: View
    private lateinit var originalPrice: TextView
    private lateinit var offerPrice: TextView
    private lateinit var renewalText: TextView
    private lateinit var priceRow: View
    private lateinit var faq: FaqAccordionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_subscription)
        onBackPressedDispatcher.addCallback(this, paywallBackCallback)
        onBackPressedDispatcher.addCallback(this, verifyBackBlocker)
        onBackPressedDispatcher.addCallback(this, stateBackCallback)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.subscriptionRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        bindViews()

        val installedApps = PaymentApp.installed(packageManager)
        selectedPaymentApp = PaymentApp.defaultSelection(installedApps)
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
        setupFaq(savedInstanceState?.getInt(STATE_FAQ_EXPANDED, FaqAccordionController.NONE) ?: FaqAccordionController.NONE)
        bindPricing(pendingAuthAmount, pendingRecurringAmount)
        bindSelectedPaymentApp()
        renderUiState(animate = false)
        // Cashfree delivers the verify callback only once, to the instance that was destroyed.
        if (savedInstanceState?.getBoolean(STATE_VERIFY_PENDING) == true) {
            verifySubscription(pendingSubscriptionId, VerifyTrigger.RESTORED)
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
        outState.putString(STATE_UI_STATE, uiState.name)
        outState.putBoolean(STATE_FAILED_REASONS, failedShowsReasons)
        outState.putInt(STATE_FAQ_EXPANDED, faq.expandedIndex)
        pendingRecurringAmount?.let { outState.putDouble(STATE_PENDING_RECURRING_AMOUNT, it) }
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
        if (state.containsKey(STATE_PENDING_RECURRING_AMOUNT)) {
            pendingRecurringAmount = state.getDouble(STATE_PENDING_RECURRING_AMOUNT)
        }
        failedShowsReasons = state.getBoolean(STATE_FAILED_REASONS)
        uiState = PaywallUiPolicy.restoredState(
            saved = state.getString(STATE_UI_STATE),
            hasSubscriptionId = !pendingSubscriptionId.isNullOrBlank(),
        )
    }

    private fun bindViews() {
        rootView = findViewById(R.id.subscriptionRoot)
        paywallContainer = findViewById(R.id.paywallContainer)
        pendingContainer = findViewById(R.id.pendingContainer)
        failedContainer = findViewById(R.id.failedContainer)
        failedReasons = findViewById(R.id.failedReasons)
        pendingRing = findViewById(R.id.pendingRing)
        videoPlayerView = findViewById(R.id.subscriptionVideoPlayer)
        // android:clipToOutline in XML only applies on API 31+; set it in code so the video,
        // scrim and poster are clipped to the card's rounded corners on every supported version.
        findViewById<View>(R.id.videoContainer).clipToOutline = true
        videoPlayerView.clipToOutline = true
        playButton = findViewById(R.id.playButton)
        videoScrim = findViewById(R.id.videoScrim)
        tryNowButton = findViewById(R.id.tryNowButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        pendingCheckButton = findViewById(R.id.pendingCheckButton)
        pendingCheckProgress = findViewById(R.id.pendingCheckProgress)
        paymentAppSelector = findViewById(R.id.paymentAppSelector)
        originalPrice = findViewById(R.id.originalPrice)
        offerPrice = findViewById(R.id.offerPrice)
        renewalText = findViewById(R.id.renewalText)
        priceRow = findViewById(R.id.priceRow)
        faq = FaqAccordionController(findViewById<LinearLayout>(R.id.faqContainer))

        originalPrice.paintFlags = originalPrice.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        GradientTextHelper.applyDiagonalGradient(offerPrice, R.color.gradient_orange, R.color.gradient_pink)
        ViewCompat.setAccessibilityPaneTitle(pendingContainer, getString(R.string.paywall_pending_title))
        ViewCompat.setAccessibilityPaneTitle(failedContainer, getString(R.string.paywall_failed_title))
    }

    private fun setupFaq(expandedIndex: Int) {
        faq.bind(faqItems(), expandedIndex)
    }

    private fun faqItems(): List<FaqAccordionController.Item> = listOf(
        FaqAccordionController.Item(getString(R.string.paywall_faq_q1), getString(R.string.paywall_faq_a1)),
        FaqAccordionController.Item(
            getString(R.string.paywall_faq_q2, authAmountLabel),
            getString(R.string.paywall_faq_a2, authAmountLabel, recurringAmountLabel),
        ),
        FaqAccordionController.Item(getString(R.string.paywall_faq_q3), getString(R.string.paywall_faq_a3)),
        FaqAccordionController.Item(
            getString(R.string.paywall_faq_q4),
            getString(R.string.paywall_faq_a4, authAmountLabel, recurringAmountLabel),
        ),
    )

    private fun setupActions() {
        findViewById<View>(R.id.homeButton).setOnClickListener {
            leaveToHome(PaywallDismissMethod.HOME_BUTTON)
        }

        pendingCheckButton.setOnClickListener {
            if (!verifyInFlight) verifySubscription(pendingSubscriptionId, VerifyTrigger.MANUAL)
        }

        findViewById<View>(R.id.failedRetryButton).setOnClickListener {
            showState(PaywallUiState.PAYWALL)
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

        tryNowButton.setOnClickListener {
            if (isProcessingPayment) return@setOnClickListener
            attempt += 1
            // Always false for UPI ID (no app): that option opens Cashfree's web checkout instead.
            val appInstalled = selectedPaymentApp.isInstalled(packageManager)
            mixpanelAnalytics().trackSubscriptionCtaTapped(
                paymentApp = selectedPaymentApp,
                paymentAppInstalled = appInstalled,
                attempt = attempt,
                videoCompleted = videoCompleted,
            )
            if (selectedPaymentApp.isUpiApp && !appInstalled) {
                // The chosen app was uninstalled while the paywall was open.
                val installedApps = PaymentApp.installed(packageManager)
                trackFailure(
                    stage = SubscriptionFailureReason.STAGE_PRECHECK,
                    reason = if (installedApps.isEmpty()) {
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
                // The next tap uses an option that exists (another app, or UPI ID).
                selectedPaymentApp = PaymentApp.defaultSelection(installedApps)
                bindSelectedPaymentApp()
                return@setOnClickListener
            }
            startSubscriptionCheckout()
        }

        paymentAppSelector.setOnClickListener {
            if (isProcessingPayment) return@setOnClickListener
            // Installed apps first, UPI ID last (the only row when no UPI app is installed).
            val options = PaymentApp.sheetOptions(PaymentApp.installed(packageManager))
            PaymentAppBottomSheet(this, selectedPaymentApp, options) { app ->
                mixpanelAnalytics().trackPaymentAppSelected(
                    paymentApp = app,
                    previousPaymentApp = selectedPaymentApp,
                )
                selectedPaymentApp = app
                bindSelectedPaymentApp()
            }.show()
        }
    }

    /**
     * The Home button and system back on the paywall: remembers the browse choice and reaches Home
     * (a finish with the onPause `paywall_dismissed` carrying [method]). Ignored while a verify
     * runs, which would otherwise cancel the conversion.
     */
    private fun leaveToHome(method: String) {
        if (verifyInFlight || isFinishing) return
        AuthStore(this).markBrowsingWithoutTrial()
        dismissMethod = method
        when (PaywallUiPolicy.homeButtonRoute(entryPoint, isTaskRoot)) {
            HomeButtonRoute.FINISH -> Unit
            HomeButtonRoute.CLEAR_TOP_TO_HOME -> startActivity(
                Intent(this, Home::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            HomeButtonRoute.NEW_TASK_TO_HOME -> startActivity(
                Intent(this, Home::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
        finish()
    }

    private fun setupSubscriptionVideo() {
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
                    setVideoOverlayVisible(!isPlaying)
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
                        setVideoOverlayVisible(true)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    setVideoOverlayVisible(true)
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

        setVideoOverlayVisible(true)
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

    /** The play disc and the light scrim show together whenever the video is not playing. */
    private fun setVideoOverlayVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        playButton.visibility = visibility
        videoScrim.visibility = visibility
    }

    private fun playSubscriptionVideo(url: String) {
        val player = videoPlayer ?: return
        if (playingVideoUrl == url && player.mediaItemCount > 0) return
        playingVideoUrl = url
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        // Autoplays on the paywall; a restore into Pending / Failed keeps it paused behind them.
        player.playWhenReady = uiState == PaywallUiState.PAYWALL
    }

    private fun bindSelectedPaymentApp() {
        PaymentAppBadge.bind(findViewById(R.id.paymentAppIcon), selectedPaymentApp)
        findViewById<TextView>(R.id.paymentAppName).text = selectedPaymentApp.label(this)
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
                    mixpanelAnalytics().trackSubscriptionInitiated(
                        paymentApp = selectedPaymentApp,
                        authAmount = shownAuthAmount,
                        recurringAmount = shownRecurringAmount,
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

    /** Server amounts from create-subscription; null keeps the current (default 3 / 299) amount. */
    private fun bindPricing(authAmount: Double?, recurringAmount: Double?) {
        authAmount?.let { shownAuthAmount = it }
        recurringAmount?.let {
            shownRecurringAmount = it
            pendingRecurringAmount = it
        }
        originalPrice.text = getString(R.string.price_rupee, recurringAmountLabel)
        offerPrice.text = getString(R.string.price_rupee, authAmountLabel)
        renewalText.text = getString(R.string.paywall_then_price, recurringAmountLabel)
        priceRow.contentDescription = getString(R.string.price_rupee, authAmountLabel)
        faq.rebind(faqItems())
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

            val payment: CFPayment = when (val upiPackage = paymentApp.packageName) {
                // UPI ID: Cashfree's hosted checkout, where the user enters a UPI ID (or picks an app).
                null -> CFSubscriptionPayment.CFSubscriptionCheckoutBuilder()
                    .setSubscriptionSession(session)
                    .setSubscriptionUITheme(webCheckoutTheme())
                    .build()
                else -> CFSubsUpiPayment.CFSubsUpiPaymentBuilder()
                    .setSubscriptionSession(session)
                    .setSubsUpi(
                        CFSubsUpi.CFSubsUpiBuilder()
                            .setMode(CFSubsUpi.Mode.INTENT)
                            .setUPIID(upiPackage)
                            .build(),
                    )
                    .build()
            }

            // Both flows answer through the registered callback (onSubscriptionVerify / onSubscriptionFailure).
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
            failedShowsReasons = false
            showState(PaywallUiState.FAILED)
            e.printStackTrace()
        }
    }

    /** Brand pink header (and status bar) with white text on Cashfree's hosted checkout page. */
    private fun webCheckoutTheme(): CFWebCheckoutTheme =
        CFWebCheckoutTheme.CFWebCheckoutThemeBuilder()
            .setNavigationBarBackgroundColor(colorHex(R.color.gradient_pink))
            .setNavigationBarTextColor(colorHex(R.color.white))
            .build()

    private fun colorHex(colorRes: Int): String =
        String.format(Locale.ROOT, "#%06X", ContextCompat.getColor(this, colorRes) and 0xFFFFFF)

    override fun onSubscriptionVerify(cfSubscriptionResponse: CFSubscriptionResponse) {
        // After a recreation mid-checkout the replayed callback still carries the id.
        verifySubscription(
            pendingSubscriptionId ?: cfSubscriptionResponse.subscriptionId?.takeIf { it.isNotBlank() },
            VerifyTrigger.CHECKOUT,
        )
    }

    /**
     * A result that does not activate the trial opens Pending after a checkout ([trigger] CHECKOUT
     * or RESTORED); a manual re-check from Pending stays where the user is and explains by toast.
     */
    private fun verifySubscription(subscriptionId: String?, trigger: VerifyTrigger) {
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
                        val amount = pendingAuthAmount ?: DEFAULT_AUTH_AMOUNT
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
                        openMembershipWelcome(user)
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
                        setLoading(false)
                        showVerifyNotActive(trigger, R.string.paywall_pending_not_yet)
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
                    setLoading(false)
                    showVerifyNotActive(trigger, R.string.paywall_pending_check_failed)
                }
            isProcessingPayment = false
            verifyPending = false
        }
    }

    /** Pending after a checkout verify; a manual re-check keeps the screen and toasts [manualMessage]. */
    private fun showVerifyNotActive(trigger: VerifyTrigger, manualMessage: Int) {
        showState(PaywallUiPolicy.stateAfterVerifyNotActive(uiState, trigger))
        if (trigger == VerifyTrigger.MANUAL) {
            Toast.makeText(this, manualMessage, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens the member screen for a paid user. A stale status that still needs a subscription keeps
     * today's [AuthNavigator] route as a safety net.
     */
    private fun openMembershipWelcome(user: User) {
        if (AuthNavigator.needsSubscription(user)) {
            AuthNavigator.navigateAfterAuth(this, user)
            return
        }
        startActivity(
            MembershipWelcomeActivity.intent(this, authAmountLabel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    override fun onSubscriptionFailure(cfErrorResponse: CFErrorResponse) {
        isProcessingPayment = false
        setLoading(false)
        Log.e(TAG, "Cashfree checkout failed: ${cfErrorResponse.message} code=${cfErrorResponse.code}")
        trackFailure(
            stage = SubscriptionFailureReason.STAGE_CHECKOUT,
            reason = SubscriptionFailureReason.forCheckout(cfErrorResponse.code),
            cfErrorCode = cfErrorResponse.code,
        )
        // A UPI cancel returns quietly to the paywall; real failures show the Failed screen.
        val nextState = PaywallUiPolicy.stateAfterCheckoutFailure(cfErrorResponse.code)
        if (BuildConfig.DEBUG && nextState == PaywallUiState.FAILED) {
            // Keeps the Cashfree auth / Play-integrity diagnostics visible to developers.
            Toast.makeText(this, paymentFailureMessage(cfErrorResponse), Toast.LENGTH_LONG).show()
        }
        failedShowsReasons = PaywallUiPolicy.showsFailedReasons(cfErrorResponse.code)
        showState(nextState)
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
            authAmount = shownAuthAmount,
            recurringAmount = shownRecurringAmount,
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

    /** The paywall CTA and the Pending re-check CTA share one request, so they load together. */
    private fun setLoading(loading: Boolean) {
        CtaButtons.setLoading(tryNowButton, loadingIndicator, loading)
        CtaButtons.setLoading(pendingCheckButton, pendingCheckProgress, loading)
        paymentAppSelector.isEnabled = !loading
        paymentAppSelector.alpha = if (loading) 0.7f else 1f
    }

    private fun showState(state: PaywallUiState) {
        if (state == uiState) return
        uiState = state
        renderUiState(animate = true)
    }

    private fun renderUiState(animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(rootView, Fade().setDuration(STATE_FADE_MS))
        paywallContainer.visibility = if (uiState == PaywallUiState.PAYWALL) View.VISIBLE else View.GONE
        pendingContainer.visibility = if (uiState == PaywallUiState.PENDING) View.VISIBLE else View.GONE
        failedContainer.visibility = if (uiState == PaywallUiState.FAILED) View.VISIBLE else View.GONE
        failedReasons.visibility = if (failedShowsReasons) View.VISIBLE else View.GONE
        rootView.setBackgroundColor(
            ContextCompat.getColor(
                this,
                when (uiState) {
                    PaywallUiState.PAYWALL -> R.color.surface_paywall
                    PaywallUiState.PENDING -> R.color.surface_payment_pending
                    PaywallUiState.FAILED -> R.color.surface_payment_failed
                },
            ),
        )
        stateBackCallback.isEnabled = uiState != PaywallUiState.PAYWALL
        if (uiState != PaywallUiState.PAYWALL) videoPlayer?.pause()
        updatePendingRing()
    }

    /** Spins the Pending ring only while Pending is showing and the screen is started. */
    private fun updatePendingRing() {
        val spin = uiState == PaywallUiState.PENDING && screenStarted
        if (!spin) {
            pendingRingAnimator?.cancel()
            pendingRingAnimator = null
            pendingRing.rotation = 0f
            return
        }
        if (pendingRingAnimator != null) return
        pendingRingAnimator = ObjectAnimator.ofFloat(pendingRing, View.ROTATION, 0f, 360f).apply {
            duration = PENDING_RING_ROTATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /**
     * System back on the paywall and the Home button ([dismissMethod]; both go to Home) are the ways
     * this screen is dismissed; [verifyBackBlocker] swallows back (and the Home button is ignored)
     * while a verify runs, back on Pending / Failed only returns to the paywall, and a paid user is
     * never counted.
     */
    override fun onPause() {
        super.onPause()
        if (!isFinishing || finishingWithoutDismiss || dismissTracked || verifyInFlight) return
        dismissTracked = true
        mixpanelAnalytics().trackPaywallDismissed(
            entryPoint = entryPoint,
            dismissMethod = dismissMethod,
            attempt = attempt,
            videoCompleted = videoCompleted,
        )
    }

    private fun finishWithoutDismiss() {
        finishingWithoutDismiss = true
        finish()
    }

    override fun onStart() {
        super.onStart()
        screenStarted = true
        updatePendingRing()
    }

    override fun onStop() {
        videoPlayer?.pause()
        super.onStop()
        screenStarted = false
        updatePendingRing()
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
        private const val STATE_UI_STATE = "ui_state"
        private const val STATE_FAILED_REASONS = "failed_reasons"
        private const val STATE_FAQ_EXPANDED = "faq_expanded"
        private const val STATE_PENDING_RECURRING_AMOUNT = "pending_recurring_amount"
        private const val DEFAULT_AUTH_AMOUNT = 3.0
        private const val DEFAULT_RECURRING_AMOUNT = 299.0
        private const val STATE_FADE_MS = 150L
        private const val PENDING_RING_ROTATION_MS = 1_200L

        fun intent(context: Context): Intent = Intent(context, SubscriptionActivity::class.java)
    }
}
