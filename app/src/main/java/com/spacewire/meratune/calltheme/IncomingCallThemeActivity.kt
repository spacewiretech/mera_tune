package com.spacewire.meratune.calltheme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import coil.load
import coil.size.Scale
import com.spacewire.meratune.R
import com.spacewire.meratune.util.PhoneUtils
import java.io.File

class IncomingCallThemeActivity : AppCompatActivity() {

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == IncomingCallNotifier.ACTION_DISMISS) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        super.onCreate(savedInstanceState)
        turnScreenOnAndShowWhenLocked()
        configureBannerWindow()
        setContentView(R.layout.activity_incoming_call_theme)
        applyStatusBarInset()
        bindIncomingCall(intent)

        findViewById<View>(R.id.declineCallButton).setOnClickListener {
            val declined = CallActions.decline(this)
            if (!declined) {
                Toast.makeText(this, R.string.call_theme_control_failed, Toast.LENGTH_SHORT).show()
            }
            IncomingCallNotifier.dismiss(this)
            finishAndRemoveTask()
        }

        findViewById<View>(R.id.acceptCallButton).setOnClickListener {
            val accepted = CallActions.accept(this)
            if (!accepted) {
                Toast.makeText(this, R.string.call_theme_control_failed, Toast.LENGTH_SHORT).show()
            }
            IncomingCallNotifier.dismiss(this)
            finishAndRemoveTask()
        }

        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(IncomingCallNotifier.ACTION_DISMISS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bindIncomingCall(intent)
    }

    private fun bindIncomingCall(intent: Intent) {
        val imagePath = intent.getStringExtra(IncomingCallNotifier.EXTRA_IMAGE_PATH)
        val contactName = intent.getStringExtra(IncomingCallNotifier.EXTRA_CONTACT_NAME)
        val phoneNumber = intent.getStringExtra(IncomingCallNotifier.EXTRA_PHONE_NUMBER)

        bindCallPhoto(imagePath)

        findViewById<TextView>(R.id.callThemeTitle).text =
            contactName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.call_theme_unknown_caller)

        val displayNumber = formatPhoneNumber(phoneNumber)
        val hasNumber = !displayNumber.isNullOrBlank()
        findViewById<TextView>(R.id.callThemePhoneLabel).apply {
            text = getString(R.string.call_theme_mobile_prefix)
            visibility = if (hasNumber) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.callThemePhone).apply {
            text = displayNumber
            visibility = if (hasNumber) View.VISIBLE else View.GONE
        }
    }

    private fun bindCallPhoto(imagePath: String?) {
        val file = imagePath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
        val photoView = findViewById<ImageView>(R.id.callThemeImage)

        if (file != null) {
            photoView.load(file) {
                crossfade(true)
                scale(Scale.FILL)
            }
        } else {
            photoView.setImageResource(R.drawable.bg_incoming_fallback)
            photoView.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun formatPhoneNumber(phoneNumber: String?): String? {
        val raw = phoneNumber?.trim().orEmpty()
        if (raw.isBlank()) return null
        return PhoneUtils.normalizeIndianPhone(raw)?.let(PhoneUtils::formatDisplayPhone) ?: raw
    }

    private fun configureBannerWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.TOP)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            dimAmount = 0f
            y = 0
            horizontalMargin = 0f
            verticalMargin = 0f
        }
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun applyStatusBarInset() {
        val root = findViewById<View>(R.id.callThemeRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        turnScreenOnAndShowWhenLocked()
        configureBannerWindow()
    }

    private fun turnScreenOnAndShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onDestroy()
    }
}
