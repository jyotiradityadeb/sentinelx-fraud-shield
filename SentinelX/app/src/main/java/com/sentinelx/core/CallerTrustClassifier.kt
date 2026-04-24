package com.sentinelx.core

import android.Manifest
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class CallerTrustClassifier(
    private val context: Context,
    private val hashProvider: NumberHashProvider = DefaultNumberHashProvider(),
    initialThreatCache: Map<String, Int> = emptyMap(),
) {
    enum class CallerTrust {
        KNOWN_CONTACT,
        BUSINESS_NUMBER,
        UNKNOWN,
        REPEATED_UNKNOWN,
        NETWORK_FLAGGED,
    }

    interface NumberHashProvider {
        fun hash(number: String): String
    }

    class DefaultNumberHashProvider : NumberHashProvider {
        override fun hash(number: String): String {
            return number.filter { it.isDigit() }
        }
    }

    private val threatCache: MutableMap<String, Int> = initialThreatCache.toMutableMap()

    fun classify(phoneNumber: String): Set<CallerTrust> {
        val normalized = normalize(phoneNumber)
        if (normalized.isBlank()) return setOf(CallerTrust.UNKNOWN)

        val labels = mutableSetOf<CallerTrust>()

        if (isKnownContact(normalized)) {
            labels += CallerTrust.KNOWN_CONTACT
        } else {
            labels += CallerTrust.UNKNOWN
            if (isRepeatedUnknown(normalized)) {
                labels += CallerTrust.REPEATED_UNKNOWN
            }
        }

        if (looksBusinessNumber(normalized)) {
            labels += CallerTrust.BUSINESS_NUMBER
        }

        if (threatCache.containsKey(hashProvider.hash(normalized))) {
            labels += CallerTrust.NETWORK_FLAGGED
        }

        return labels
    }

    fun updateThreatCache(entries: Map<String, Int>) {
        threatCache.putAll(entries)
    }

    private fun isKnownContact(number: String): Boolean {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return false
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(number),
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun isRepeatedUnknown(number: String): Boolean {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return false
        val cutoffTs = System.currentTimeMillis() - THIRTY_DAYS_MS
        val selection = "${CallLog.Calls.DATE} >= ?"
        val args = arrayOf(cutoffTs.toString())

        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                selection,
                args,
                null,
            )?.use { cursor ->
                var hits = 0
                while (cursor.moveToNext()) {
                    val current = normalize(cursor.getString(0).orEmpty())
                    if (current == number && !isKnownContact(current)) {
                        hits += 1
                        if (hits >= 2) return@use true
                    }
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    private fun looksBusinessNumber(number: String): Boolean {
        return number.startsWith("1800") || number.startsWith("1860") || number.length <= 8
    }

    private fun normalize(number: String): String {
        var normalized = number.filter { it.isDigit() }
        if (normalized.startsWith("91") && normalized.length == 12) normalized = normalized.substring(2)
        if (normalized.startsWith("0") && normalized.length == 11) normalized = normalized.substring(1)
        return normalized
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
