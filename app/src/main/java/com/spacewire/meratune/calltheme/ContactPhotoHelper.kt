package com.spacewire.meratune.calltheme

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes a gallery image onto an Android contact so the system Phone app
 * can show it as the incoming-call background / avatar.
 */
object ContactPhotoHelper {
    private const val TAG = "ContactPhotoHelper"
    private const val MAX_EDGE_PX = 1440

    fun setContactPhoto(context: Context, contactUri: Uri, imagePath: String): Boolean {
        val file = File(imagePath)
        if (!file.exists()) {
            Log.w(TAG, "Image file missing: $imagePath")
            return false
        }

        val resolver = context.contentResolver
        val lookupUri = ContactsContract.Contacts.lookupContact(resolver, contactUri) ?: contactUri
        val contactId = ContentUris.parseId(lookupUri)
        val rawContactId = resolveRawContactId(context, contactId)
        if (rawContactId == null) {
            Log.w(TAG, "No raw contact for contactId=$contactId")
            return false
        }

        val photoBytes = preparePhotoBytes(imagePath) ?: return false

        val displayOk = writeDisplayPhoto(context, rawContactId, photoBytes)
        val thumbnailOk = writeThumbnailPhoto(context, rawContactId, photoBytes)
        Log.d(TAG, "Photo write displayOk=$displayOk thumbnailOk=$thumbnailOk rawContactId=$rawContactId")
        return displayOk || thumbnailOk
    }

    private fun resolveRawContactId(context: Context, contactId: Long): Long? {
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                if (index >= 0) return cursor.getLong(index)
            }
        }
        return null
    }

    private fun writeDisplayPhoto(context: Context, rawContactId: Long, photoBytes: ByteArray): Boolean {
        return runCatching {
            val rawContactUri = ContentUris.withAppendedId(
                ContactsContract.RawContacts.CONTENT_URI,
                rawContactId,
            )
            val displayPhotoUri = Uri.withAppendedPath(
                rawContactUri,
                ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY,
            )
            context.contentResolver.openAssetFileDescriptor(displayPhotoUri, "rw")?.use { afd ->
                afd.createOutputStream().use { output ->
                    output.write(photoBytes)
                    output.flush()
                }
            } ?: return false
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to write display photo", error)
        }.getOrDefault(false)
    }

    private fun writeThumbnailPhoto(context: Context, rawContactId: Long, photoBytes: ByteArray): Boolean {
        return runCatching {
            val resolver = context.contentResolver
            val existingPhotoId = resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(
                    rawContactId.toString(),
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
                ),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(ContactsContract.Data._ID)
                    if (index >= 0) cursor.getLong(index) else null
                } else {
                    null
                }
            }

            val values = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                put(ContactsContract.Data.IS_PRIMARY, 1)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
            }

            if (existingPhotoId != null) {
                resolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    values,
                    "${ContactsContract.Data._ID} = ?",
                    arrayOf(existingPhotoId.toString()),
                ) > 0
            } else {
                resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to write thumbnail photo", error)
        }.getOrDefault(false)
    }

    private fun preparePhotoBytes(imagePath: String): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(imagePath, options) ?: return null
            val scaled = scaleDownIfNeeded(bitmap, MAX_EDGE_PX)
            if (scaled !== bitmap) {
                bitmap.recycle()
            }
            ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                scaled.recycle()
                stream.toByteArray()
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to prepare photo bytes", error)
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxEdge && halfHeight / sample >= maxEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
