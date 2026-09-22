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
import androidx.activity.enableEdgeToEdge
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
import com.spacewire.meratune.analytics.firebaseAnalytics
import com.spacewire.meratune.analytics.metaAnalytics
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.AuthException
import com.spacewire.meratune.data.SubscriptionRepository
import com.spacewire.meratune.data.SubscriptionVideoRepository
import com.spacewire.meratune.model.PaymentApp
import com.spacewire.meratune.ui.PaymentAppBottomSheet
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.ProfileStore
import kotlinx.coroutines.launch

class SubscriptionActivity : AppCompatActivity(), CFSubscriptionResponseCallback {

    private val subscriptionRepository = SubscriptionRepository()
    private val subscriptionVideoRepository by lazy { SubscriptionVideoRepository(this) }
    private var pendingSubscriptionId: String? = null
    private var playingVideoUrl: String? = null
    private var pendingAuthAmount: Double? = null
    private var isProcessingPayment = false
    private var selectedPaymentApp: PaymentApp = PaymentApp.DEFAULT
    private var videoPlayer: ExoPlayer? = null
    private lateinit var videoPlayerView: PlayerView
    private lateinit var playButton: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_subscription)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.subscriptionRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        try {
            CFPaymentGatewayService.getInstance().setSubscriptionCheckoutCallback(this)
        } catch (e: CFException) {
            e.printStackTrace()
        }

        selectedPaymentApp = PaymentApp.installed(packageManager).firstOrNull()
            ?: PaymentApp.DEFAULT
        setupActions()
        setupSubscriptionVideo()
        bindFeatureRows()
        bindSelectedPaymentApp()
        mixpanelAnalytics().trackSubscriptionScreenViewed()
        metaAnalytics().trackSubscriptionScreenViewed()
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
            mixpanelAnalytics().logout(this)
            metaAnalytics().clearUserId()
            firebaseAnalytics().clearUserId()
            startActivity(
                PhoneAuthActivity.intent(this).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
            finish()
        }

        findViewById<TextView>(R.id.tryNowButton).setOnClickListener {
            if (isProcessingPayment) return@setOnClickListener
            if (!selectedPaymentApp.isInstalled(packageManager)) {
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
                Toast.makeText(this, R.string.subscription_no_payment_app_installed, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            PaymentAppBottomSheet(this, selectedPaymentApp, installedApps) { app ->
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
                        player.seekTo(0)
                        player.pause()
                        playButton.visibility = View.VISIBLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playButton.visibility = View.VISIBLE
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
            Toast.makeText(this, R.string.auth_generic_error, Toast.LENGTH_SHORT).show()
            finish()
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
                    val message = (error as? AuthException)?.message ?: getString(R.string.subscription_error)
                    mixpanelAnalytics().trackSubscriptionFailed(
                        stage = "create",
                        failureReason = message,
                        paymentApp = selectedPaymentApp,
                    )
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
            mixpanelAnalytics().trackSubscriptionFailed(
                stage = "checkout",
                failureReason = e.message,
                paymentApp = paymentApp,
            )
            Toast.makeText(this, R.string.subscription_error, Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    override fun onSubscriptionVerify(cfSubscriptionResponse: CFSubscriptionResponse) {
        val userId = AuthStore(this).getUserId()
        val subscriptionId = pendingSubscriptionId
        if (userId <= 0L || subscriptionId.isNullOrBlank()) {
            setLoading(false)
            isProcessingPayment = false
            return
        }

        lifecycleScope.launch {
            subscriptionRepository.verifySubscription(userId, subscriptionId)
                .onSuccess { response ->
                    val user = response.user
                    if (response.active == true && user != null) {
                        AuthStore(this@SubscriptionActivity).saveUser(user)
                        ProfileStore(this@SubscriptionActivity).saveUser(user.name.orEmpty(), user.phone)
                        val analytics = mixpanelAnalytics()
                        analytics.identifyUser(user)
                        analytics.trackTrialPaymentCompleted(
                            paymentApp = selectedPaymentApp,
                            subscriptionId = subscriptionId,
                            amount = pendingAuthAmount ?: 3.0,
                        )
                        metaAnalytics().identifyUser(user)
                        metaAnalytics().trackTrialPaymentCompleted(
                            amount = pendingAuthAmount ?: 3.0,
                            subscriptionId = subscriptionId,
                        )
                        metaAnalytics().flush()
                        firebaseAnalytics().identifyUser(user)
                        firebaseAnalytics().trackTrialPaymentCompleted(
                            amount = pendingAuthAmount ?: 3.0,
                            subscriptionId = subscriptionId,
                        )
                        AuthNavigator.navigateAfterAuth(this@SubscriptionActivity, user)
                        finish()
                    } else {
                        mixpanelAnalytics().trackSubscriptionFailed(
                            stage = "verify",
                            failureReason = "pending",
                            paymentApp = selectedPaymentApp,
                        )
                        Toast.makeText(
                            this@SubscriptionActivity,
                            R.string.subscription_pending,
                            Toast.LENGTH_LONG,
                        ).show()
                        setLoading(false)
                    }
                }
                .onFailure { error ->
                    mixpanelAnalytics().trackSubscriptionFailed(
                        stage = "verify",
                        failureReason = error.message,
                        paymentApp = selectedPaymentApp,
                    )
                    Toast.makeText(
                        this@SubscriptionActivity,
                        R.string.subscription_pending,
                        Toast.LENGTH_LONG,
                    ).show()
                    setLoading(false)
                }
            isProcessingPayment = false
        }
    }

    override fun onSubscriptionFailure(cfErrorResponse: CFErrorResponse) {
        isProcessingPayment = false
        setLoading(false)
        val message = paymentFailureMessage(cfErrorResponse)
        Log.e(TAG, "Cashfree checkout failed: ${cfErrorResponse.message} code=${cfErrorResponse.code}")
        mixpanelAnalytics().trackSubscriptionFailed(
            stage = "checkout",
            failureReason = message,
            paymentApp = selectedPaymentApp,
        )
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
        findViewById<TextView>(R.id.tryNowButton).alpha = if (loading) 0.7f else 1f
        findViewById<View>(R.id.paymentAppSelector).isEnabled = !loading
        findViewById<View>(R.id.paymentAppSelector).alpha = if (loading) 0.7f else 1f
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

        fun intent(context: Context): Intent = Intent(context, SubscriptionActivity::class.java)
    }
}
