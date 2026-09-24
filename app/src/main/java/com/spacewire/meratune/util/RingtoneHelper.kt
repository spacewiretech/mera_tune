package com.spacewire.meratune.util

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.spacewire.meratune.data.Tune
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The [RingtoneHelper.setRingtone] step that failed. */
enum class RingtoneSetStep {
    DOWNLOAD,
    SAVE,
    SET_DEFAULT,
}

class RingtoneSetException(val step: RingtoneSetStep, cause: Throwable) : Exception(cause)

object RingtoneHelper {

    /** Failures are [RingtoneSetException]s carrying the step that failed and the original cause. */
    suspend fun setRingtone(context: Context, tune: Tune): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceUrl = tune.tuneUrl.trim()
            val bytes = step(RingtoneSetStep.DOWNLOAD) {
                require(sourceUrl.isNotBlank()) { "Empty tune URL" }
                downloadAudio(sourceUrl)
            }

            val extension = extensionFromUrl(sourceUrl)
            val mimeType = mimeTypeForExtension(extension)
            val fileName = fileNameFor(
                title = tune.name,
                suffix = tune.generationId ?: tune.id,
                extension = extension,
            )
            val uri = step(RingtoneSetStep.SAVE) {
                saveRingtone(context, tune.name, fileName, bytes, mimeType)
            }

            step(RingtoneSetStep.SET_DEFAULT) {
                RingtoneManager.setActualDefaultRingtoneUri(
                    context,
                    RingtoneManager.TYPE_RINGTONE,
                    uri,
                )
            }
            uri
        }
    }

    fun needsWriteSettingsPermission(context: Context): Boolean {
        return !Settings.System.canWrite(context)
    }

    /** Launched for result: a new-task flag would make it return RESULT_CANCELED at once. */
    fun writeSettingsIntent(context: Context) =
        android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * File name for a saved ringtone: `<sanitized title or "meratune">_<first 8 chars of suffix>.<extension>`.
     * The suffix (generation id or tune id) keeps Unicode-only titles from colliding on `meratune_ringtone`.
     * Pure function so it is unit-tested on the JVM.
     */
    internal fun fileNameFor(title: String, suffix: String, extension: String): String {
        val base = sanitizeFileName(title).ifBlank { DEFAULT_FILE_BASE }
        val safeSuffix = suffix
            .replace(Regex("[^a-zA-Z0-9]"), "")
            .take(SUFFIX_LENGTH)
        val ext = extension.trim().trimStart('.').lowercase().ifBlank { "mp3" }
        return if (safeSuffix.isEmpty()) "$base.$ext" else "${base}_$safeSuffix.$ext"
    }

    private inline fun <T> step(current: RingtoneSetStep, block: () -> T): T =
        try {
            block()
        } catch (error: Throwable) {
            throw RingtoneSetException(current, error)
        }

    private fun downloadAudio(sourceUrl: String): ByteArray {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        return connection.inputStream.use { input ->
            input.readBytes()
        }.also {
            connection.disconnect()
        }.takeIf { bytes -> bytes.isNotEmpty() }
            ?: throw IOException("Downloaded tune is empty")
    }

    private fun saveRingtone(
        context: Context,
        title: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveRingtoneViaMediaStore(context, title, fileName, bytes, mimeType)
        } else {
            saveRingtoneLegacy(context, title, fileName, bytes, mimeType)
        }
    }

    private fun saveRingtoneViaMediaStore(
        context: Context,
        title: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/MeraTune")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_RINGTONE, true)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_ALARM, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            put(MediaStore.Audio.Media.TITLE, title)
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create ringtone file")

        resolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
        } ?: throw IOException("Could not write ringtone file")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveRingtoneLegacy(
        context: Context,
        title: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ): Uri {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not access ringtones folder")
        }

        val file = File(directory, fileName)
        file.writeBytes(bytes)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.IS_RINGTONE, true)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_ALARM, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.SIZE, file.length())
        }

        return context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: Uri.fromFile(file)
    }

    private fun extensionFromUrl(sourceUrl: String): String {
        val path = sourceUrl.substringBefore('?').substringAfterLast('/')
        val extension = path.substringAfterLast('.', "mp3").lowercase()
        return extension.takeIf { it.matches(Regex("mp3|wav|ogg|m4a|aac")) } ?: "mp3"
    }

    private fun mimeTypeForExtension(extension: String): String {
        return when (extension) {
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            else -> "audio/mpeg"
        }
    }

    private fun sanitizeFileName(title: String): String {
        return title
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_{2,}"), "_")
            .take(MAX_BASE_LENGTH)
            .trim('_', '.')
    }

    private const val DEFAULT_FILE_BASE = "meratune"
    private const val SUFFIX_LENGTH = 8
    private const val MAX_BASE_LENGTH = 48
}
