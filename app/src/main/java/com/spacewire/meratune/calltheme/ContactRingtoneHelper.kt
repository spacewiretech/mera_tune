package com.spacewire.meratune.calltheme

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactRingtoneHelper {

    fun setCustomRingtone(context: Context, contactUri: Uri, ringtoneUri: Uri): Boolean {
        val values = ContentValues().apply {
            put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri.toString())
        }
        return runCatching {
            context.contentResolver.update(contactUri, values, null, null) > 0
        }.getOrDefault(false)
    }
}
