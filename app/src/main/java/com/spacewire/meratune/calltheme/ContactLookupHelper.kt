package com.spacewire.meratune.calltheme

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

data class ContactDetails(
    val contactUri: Uri,
    val displayName: String?,
    val phoneNumbers: List<String>,
) {
    /** Caller-matching keys; empty when no number has 10+ digits (short codes, landlines without STD). */
    val phoneKeys: List<String>
        get() = phoneNumbers.mapNotNull(PhoneMatch::normalizeKey).distinct()
}

object ContactLookupHelper {

    fun loadDetails(context: Context, contactUri: Uri): ContactDetails? {
        val resolver = context.contentResolver
        val resolvedUri = ContactsContract.Contacts.lookupContact(resolver, contactUri) ?: contactUri
        val contactId = resolver.query(
            resolvedUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val id = if (idIndex >= 0) cursor.getString(idIndex) else return null
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            id to name
        } ?: return null

        val phones = mutableListOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.first),
            null,
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                if (numberIndex >= 0) {
                    cursor.getString(numberIndex)?.trim()?.takeIf { it.isNotEmpty() }?.let(phones::add)
                }
            }
        }

        return ContactDetails(
            contactUri = resolvedUri,
            displayName = contactId.second,
            phoneNumbers = phones,
        )
    }

    fun findNameByNumber(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex < 0) return@use null
                cursor.getString(nameIndex)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}
