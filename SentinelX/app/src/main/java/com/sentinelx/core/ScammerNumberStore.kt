package com.sentinelx.core

import android.content.Context

class ScammerNumberStore(private val context: Context) {
    fun getAll(): Set<String> {
        val prefs = prefs()
        return prefs.getStringSet(KEY_NUMBERS, emptySet())
            ?.map { normalize(it) }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    fun setFromRawInput(raw: String) {
        val parsed = raw
            .split(',', '\n', ';')
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()
        prefs().edit().putStringSet(KEY_NUMBERS, parsed).apply()
    }

    fun getAsDisplayText(): String {
        return getAll().joinToString(separator = "\n")
    }

    fun isMarked(number: String): Boolean {
        val normalized = normalize(number)
        if (normalized.isBlank()) return false
        return getAll().contains(normalized)
    }

    fun count(): Int = getAll().size

    private fun normalize(number: String): String {
        var normalized = number.filter { it.isDigit() }
        if (normalized.startsWith("91") && normalized.length == 12) normalized = normalized.substring(2)
        if (normalized.startsWith("0") && normalized.length == 11) normalized = normalized.substring(1)
        return normalized
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "sentinelx_scammer_numbers"
        private const val KEY_NUMBERS = "numbers"
    }
}

