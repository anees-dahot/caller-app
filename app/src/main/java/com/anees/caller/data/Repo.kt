package com.anees.caller.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import java.util.Locale

data class PhoneNum(val number: String, val label: String)

data class Contact(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val starred: Boolean,
    val numbers: MutableList<PhoneNum> = mutableListOf(),
) {
    val t9: String by lazy { T9.encode(name) }
    val digits: List<String> by lazy { numbers.map { T9.digitsOnly(it.number) } }
    val uri: Uri get() = ContactsContract.Contacts.getLookupUri(id, lookupKey)
}

data class CallEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val count: Int = 1,
    val accountId: String?,
) {
    val missed get() = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
}

object T9 {
    private val map = IntArray(26).also {
        val groups = arrayOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")
        groups.forEachIndexed { i, g -> g.forEach { c -> it[c - 'a'] = i + 2 } }
    }

    fun encode(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase(Locale.ROOT)) {
            when (ch) {
                in 'a'..'z' -> sb.append(('0' + map[ch - 'a']))
                in '0'..'9' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString()
    }

    fun digitsOnly(s: String): String = s.filter { it.isDigit() }

    /** Match from start of any word in the name, or anywhere in a number. */
    fun matches(c: Contact, q: String): Boolean {
        if (q.isEmpty()) return false
        val t = c.t9
        if (t.startsWith(q)) return true
        var i = t.indexOf(' ')
        while (i >= 0) {
            if (t.startsWith(q, i + 1)) return true
            i = t.indexOf(' ', i + 1)
        }
        return c.digits.any { it.contains(q) }
    }
}

object Repo {
    @Volatile var contactsCache: List<Contact> = emptyList()

    fun loadContacts(ctx: Context): List<Contact> {
        val out = LinkedHashMap<Long, Contact>()
        val proj = arrayOf(
            Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.STARRED,
            Phone.NUMBER, Phone.TYPE, Phone.LABEL,
        )
        try {
            ctx.contentResolver.query(
                // No COLLATE in sort order: some OEM contact providers reject it. Sort below.
                Phone.CONTENT_URI, proj, null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val number = c.getString(4) ?: continue
                    val contact = out.getOrPut(id) {
                        Contact(id, c.getString(1) ?: "", c.getString(2) ?: number, c.getInt(3) == 1)
                    }
                    val norm = T9.digitsOnly(number)
                    if (contact.numbers.none { T9.digitsOnly(it.number).takeLast(9) == norm.takeLast(9) }) {
                        val label = Phone.getTypeLabel(ctx.resources, c.getInt(5), c.getString(6)).toString()
                        contact.numbers.add(PhoneNum(number, label))
                    }
                }
            }
        } catch (_: Exception) {
            // SecurityException, or provider-specific SQL errors on OEM ROMs.
        }
        val collator = java.text.Collator.getInstance()
        return out.values.sortedWith { a, b -> collator.compare(a.name, b.name) }.also { contactsCache = it }
    }

    fun loadCalls(ctx: Context, limit: Int = 500, number: String? = null): List<CallEntry> {
        val list = ArrayList<CallEntry>()
        val proj = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        val sel = number?.let { "${CallLog.Calls.NUMBER} = ?" }
        val args = number?.let { arrayOf(it) }
        // LIMIT inside sort order is ignored by some OEM providers on API 30+, so cap manually.
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, sel, args, "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && list.size < limit) {
                    list.add(
                        CallEntry(
                            c.getLong(0), c.getString(1) ?: "", c.getString(2)?.takeIf { it.isNotBlank() },
                            c.getInt(3), c.getLong(4), c.getLong(5), 1, c.getString(6),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    /** Collapse back-to-back calls with same number and same missed/not-missed kind. */
    fun group(calls: List<CallEntry>): List<CallEntry> {
        val out = ArrayList<CallEntry>()
        for (e in calls) {
            val last = out.lastOrNull()
            if (last != null && same(last.number, e.number) && last.missed == e.missed &&
                dayOf(last.date) == dayOf(e.date)
            ) {
                out[out.size - 1] = last.copy(count = last.count + 1)
            } else out.add(e)
        }
        return out
    }

    private fun dayOf(t: Long) = (t + java.util.TimeZone.getDefault().getOffset(t)) / 86_400_000L

    @Suppress("DEPRECATION")
    fun same(a: String, b: String): Boolean =
        a == b || (a.isNotEmpty() && b.isNotEmpty() && PhoneNumberUtils.compare(a, b))

    fun lookupName(ctx: Context, number: String): Pair<String, Uri?>? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) to ContactsContract.Contacts.getLookupUri(c.getLong(1), c.getString(2)) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadContact(ctx: Context, uri: Uri): Contact? {
        val id = try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        } catch (_: Exception) { null } ?: return null
        val all = loadContacts(ctx)
        return all.firstOrNull { it.id == id }
    }

    fun setStarred(ctx: Context, c: Contact, starred: Boolean) {
        val v = android.content.ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
        try {
            ctx.contentResolver.update(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.id), v, null, null,
            )
        } catch (_: Exception) {
        }
    }

    fun deleteCall(ctx: Context, id: Long) {
        try {
            ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} = ?", arrayOf(id.toString()))
        } catch (_: Exception) {
        }
    }

    fun initial(name: String): String {
        val ch = name.firstOrNull { it.isLetterOrDigit() } ?: return "#"
        return if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
    }
}
