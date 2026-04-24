package com.sentinelx.core

import android.content.Context

class ScammerNumberStore(private val context: Context) {
    fun getAll(): Set<String> {
        // toMutableSet() required: getStringSet returns a live reference on some Android versions
        return prefs().getStringSet(KEY_NUMBERS, emptySet())
            ?.toMutableSet()
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

    /** Returns true if the number was newly added, false if it was already present. */
    fun add(number: String): Boolean {
        val normalized = normalize(number)
        if (normalized.isBlank()) return false
        val current = prefs().getStringSet(KEY_NUMBERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.contains(normalized)) return false
        current.add(normalized)
        prefs().edit().putStringSet(KEY_NUMBERS, current).apply()
        return true
    }

    /** Returns true if the number was found and removed, false if it was not present. */
    fun remove(number: String): Boolean {
        val normalized = normalize(number)
        if (normalized.isBlank()) return false
        val current = prefs().getStringSet(KEY_NUMBERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val removed = current.remove(normalized)
        if (removed) prefs().edit().putStringSet(KEY_NUMBERS, current).apply()
        return removed
    }

    /** Parses a CSV string of phone numbers and adds any that are not already present.
     *  Returns the count of newly added numbers. */
    fun importFromCsv(csv: String): Int {
        val parsed = csv
            .split(',', '\n', ';')
            .map { normalize(it.trim()) }
            .filter { it.isNotBlank() }
        val current = prefs().getStringSet(KEY_NUMBERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        var added = 0
        for (number in parsed) {
            if (!current.contains(number)) {
                current.add(number)
                added++
            }
        }
        if (added > 0) prefs().edit().putStringSet(KEY_NUMBERS, current).apply()
        return added
    }

    fun normalize(number: String): String {
        var normalized = number.filter { it.isDigit() }
        // Strip leading +, then attempt to strip 1-3 digit country code if result is 10 digits
        if (normalized.startsWith("91") && normalized.length == 12) {
            normalized = normalized.substring(2)
        } else if (normalized.startsWith("1") && normalized.length == 11) {
            normalized = normalized.substring(1)
        } else if (normalized.length in 11..13) {
            // Generic: strip potential country prefix if stripping yields 10 digits
            for (prefixLen in 1..3) {
                val candidate = normalized.substring(prefixLen)
                if (candidate.length == 10 && !candidate.startsWith("0")) {
                    normalized = candidate
                    break
                }
            }
        }
        if (normalized.startsWith("0") && normalized.length == 11) normalized = normalized.substring(1)
        return normalized
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "sentinelx_scammer_numbers"
        private const val KEY_NUMBERS = "numbers"
    }
}
