package com.spacewire.meratune.calltheme

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

object CallThemeImageHelper {

    suspend fun persistImage(context: Context, sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, DIR_NAME).apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Could not create call theme image directory")
            }
        }
        val destination = File(directory, "${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Could not read selected image")

        if (destination.length() == 0L) {
            destination.delete()
            throw IOException("Selected image is empty")
        }
        destination.absolutePath
    }

    private const val DIR_NAME = "call_themes"
}
