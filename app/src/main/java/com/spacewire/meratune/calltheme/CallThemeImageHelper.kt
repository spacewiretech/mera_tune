package com.spacewire.meratune.calltheme

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Call-theme photo files under `filesDir`.
 *
 * - `call_themes/` holds the photos saved themes point at ([persistImage], [promoteStaged]).
 * - `call_theme_staging/` holds photos picked on the song picker ("chuno") before the ringtone is
 *   set on the final screen ([stageImage]). Photo Picker and camera URIs do not survive other
 *   activities or process death, so the bytes are copied at pick time. Staged files older than
 *   [STAGED_MAX_AGE_MS] are pruned on the next stage. Only `call_theme_staging/` is excluded from backup.
 */
object CallThemeImageHelper {

    /** Home path: copies [sourceUri] straight into `call_themes/`. */
    suspend fun persistImage(context: Context, sourceUri: Uri): String = withContext(Dispatchers.IO) {
        copyInto(context, sourceUri, DIR_NAME)
    }

    /** Create path ("chuno"): prunes stale staged files, then copies [sourceUri] into staging. */
    suspend fun stageImage(context: Context, sourceUri: Uri): String = withContext(Dispatchers.IO) {
        pruneStaged(context)
        copyInto(context, sourceUri, STAGING_DIR_NAME)
    }

    /**
     * Moves a staged photo into `call_themes/` after the ringtone was set. A path outside the
     * staging folder (Home path, or an already promoted file on a retry) is returned unchanged.
     */
    suspend fun promoteStaged(context: Context, path: String): String = withContext(Dispatchers.IO) {
        val source = File(path)
        val stagingDir = File(context.filesDir, STAGING_DIR_NAME)
        if (source.parentFile?.canonicalPath != stagingDir.canonicalPath) return@withContext path
        val directory = ensureDir(context, DIR_NAME)
        val destination = File(directory, source.name)
        if (!source.renameTo(destination)) {
            source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            source.delete()
        }
        if (destination.length() == 0L) throw IOException("Promoted image is empty")
        destination.absolutePath
    }

    /** True when [path] names an existing, non-empty file. */
    fun isUsableImage(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = File(path)
        return file.isFile && file.length() > 0L
    }

    private fun copyInto(context: Context, sourceUri: Uri, dirName: String): String {
        val directory = ensureDir(context, dirName)
        val destination = File(directory, "${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Could not read selected image")

        if (destination.length() == 0L) {
            destination.delete()
            throw IOException("Selected image is empty")
        }
        return destination.absolutePath
    }

    private fun ensureDir(context: Context, dirName: String): File =
        File(context.filesDir, dirName).apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Could not create call theme image directory")
            }
        }

    private fun pruneStaged(context: Context) {
        val cutoff = System.currentTimeMillis() - STAGED_MAX_AGE_MS
        File(context.filesDir, STAGING_DIR_NAME).listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private const val DIR_NAME = "call_themes"
    private const val STAGING_DIR_NAME = "call_theme_staging"
    private const val STAGED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
}
