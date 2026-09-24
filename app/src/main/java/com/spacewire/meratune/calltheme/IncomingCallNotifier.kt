package com.spacewire.meratune.calltheme

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.spacewire.meratune.R
import com.spacewire.meratune.analytics.LaunchTrigger
import com.spacewire.meratune.util.PhoneUtils
import java.io.File

object IncomingCallNotifier {
    const val ACTION_DISMISS = "com.spacewire.meratune.calltheme.ACTION_DISMISS"
    const val EXTRA_IMAGE_PATH = "extra_image_path"
    const val EXTRA_CONTACT_NAME = "extra_contact_name"
    const val EXTRA_TUNE_NAME = "extra_tune_name"
    const val EXTRA_PHONE_NUMBER = "extra_phone_number"

    /** A [LaunchTrigger] value: `auto` for the direct start, `notification_tap` for the content intent. */
    const val EXTRA_LAUNCH_TRIGGER = "extra_launch_trigger"

    private const val CHANNEL_HEADS_UP = "incoming_call_banner_v1"
    private const val CHANNEL_SILENT = "incoming_call_silent_v1"
    private const val NOTIFICATION_ID = 7101
    private const val TAG = "IncomingCallNotifier"
    private const val WAKE_LOCK_MS = 15_000L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HEADS_UP,
                context.getString(R.string.call_theme_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.call_theme_notification_channel_desc)
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                context.getString(R.string.call_theme_notification_channel_silent),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.call_theme_notification_channel_desc)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    /** @return whether the overlay activity was requested; `false` means heads-up notification only. */
    fun show(
        context: Context,
        phoneNumber: String?,
        contactName: String?,
        imagePath: String?,
        tuneName: String?,
    ): Boolean {
        ensureChannel(context)
        val appContext = context.applicationContext
        val displayName = contactName?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.call_theme_unknown_caller)

        wakeScreen(appContext)

        val themeIntent = Intent(appContext, IncomingCallThemeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(EXTRA_IMAGE_PATH, imagePath.orEmpty())
            putExtra(EXTRA_CONTACT_NAME, contactName)
            putExtra(EXTRA_TUNE_NAME, tuneName)
            putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(EXTRA_LAUNCH_TRIGGER, LaunchTrigger.AUTO)
        }

        val overlayShown = if (!isLockedOrScreenOff(appContext)) {
            runCatching {
                appContext.startActivity(themeIntent)
                true
            }.onFailure { error ->
                Log.e(TAG, "Could not start incoming-call banner", error)
            }.getOrDefault(false)
        } else {
            false
        }

        postNotification(
            appContext = appContext,
            displayName = displayName,
            phoneNumber = phoneNumber,
            imagePath = imagePath,
            themeIntent = themeIntent,
            headsUp = !overlayShown,
        )
        return overlayShown
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        appContext.getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_ID)
        appContext.sendBroadcast(
            Intent(ACTION_DISMISS).setPackage(appContext.packageName),
        )
    }

    private fun postNotification(
        appContext: Context,
        displayName: String,
        phoneNumber: String?,
        imagePath: String?,
        themeIntent: Intent,
        headsUp: Boolean,
    ) {
        val tapIntent = Intent(themeIntent).putExtra(EXTRA_LAUNCH_TRIGGER, LaunchTrigger.NOTIFICATION_TAP)
        val contentPendingIntent = pendingActivity(appContext, REQUEST_CONTENT, tapIntent)
        val answerIntent = Intent(appContext, IncomingCallActionReceiver::class.java).setAction(
            IncomingCallActionReceiver.ACTION_ANSWER,
        )
        val declineIntent = Intent(appContext, IncomingCallActionReceiver::class.java).setAction(
            IncomingCallActionReceiver.ACTION_DECLINE,
        )
        val customView = buildRemoteViews(
            appContext = appContext,
            displayName = displayName,
            phoneNumber = phoneNumber,
            imagePath = imagePath,
            answerIntent = pendingBroadcast(appContext, REQUEST_ANSWER, answerIntent),
            declineIntent = pendingBroadcast(appContext, REQUEST_DECLINE, declineIntent),
        )
        val channelId = if (headsUp) CHANNEL_HEADS_UP else CHANNEL_SILENT
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_music_note)
            .setColor(ContextCompat.getColor(appContext, R.color.call_header_icon))
            .setContentTitle(displayName)
            .setContentText(
                phoneNumber?.takeIf { it.isNotBlank() }
                    ?: appContext.getString(R.string.call_theme_incoming_title),
            )
            .setSubText(appContext.getString(R.string.call_theme_incoming_title))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(
                if (headsUp) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW,
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(null)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setCustomHeadsUpContentView(customView)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { error -> Log.e(TAG, "Could not post incoming-call notification", error) }
    }

    private fun buildRemoteViews(
        appContext: Context,
        displayName: String,
        phoneNumber: String?,
        imagePath: String?,
        answerIntent: PendingIntent,
        declineIntent: PendingIntent,
    ): RemoteViews {
        val views = RemoteViews(appContext.packageName, R.layout.notification_incoming_call)
        views.setTextViewText(R.id.notificationCallName, displayName)
        val phoneLabel = phoneNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val display = PhoneUtils.normalizeIndianPhone(raw)?.let(PhoneUtils::formatDisplayPhone) ?: raw
            appContext.getString(R.string.call_theme_mobile_label, display)
        } ?: appContext.getString(R.string.call_theme_incoming_title)
        views.setTextViewText(R.id.notificationCallPhone, phoneLabel)

        val photo = loadRoundedPhoto(appContext, imagePath)
        if (photo != null) {
            views.setImageViewBitmap(R.id.notificationCallPhoto, photo)
        } else {
            views.setImageViewResource(R.id.notificationCallPhoto, R.drawable.bg_incoming_fallback)
        }

        views.setOnClickPendingIntent(R.id.notificationAcceptButton, answerIntent)
        views.setOnClickPendingIntent(R.id.notificationDeclineButton, declineIntent)
        return views
    }

    private fun loadRoundedPhoto(context: Context, imagePath: String?): Bitmap? {
        val file = imagePath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() } ?: return null
        val size = (56 * context.resources.displayMetrics.density).toInt().coerceAtLeast(56)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, size, size)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, sample) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, size, size, true)
        if (scaled != decoded) decoded.recycle()
        val radius = size * 0.22f
        return roundCorners(scaled, radius)
    }

    private fun roundCorners(source: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        if (output != source) source.recycle()
        return output
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    internal fun isLockedOrScreenOff(context: Context): Boolean {
        val keyguardLocked = context.getSystemService(KeyguardManager::class.java)
            ?.isKeyguardLocked == true
        val screenOff = context.getSystemService(PowerManager::class.java)
            ?.isInteractive == false
        return keyguardLocked || screenOff
    }

    private fun wakeScreen(context: Context) {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isInteractive) return
        @Suppress("DEPRECATION")
        runCatching {
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "meratune:incoming_call",
            ).acquire(WAKE_LOCK_MS)
        }.onFailure { error -> Log.w(TAG, "Could not wake screen", error) }
    }

    private fun pendingActivity(context: Context, requestCode: Int, intent: Intent): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingBroadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val REQUEST_CONTENT = 7101
    private const val REQUEST_ANSWER = 7102
    private const val REQUEST_DECLINE = 7103
}
