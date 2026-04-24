package com.sentinelx.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

enum class AddedReason { MANUAL, IMPORTED, COMMUNITY, AI_SUGGESTED }

data class BlocklistEntry(
    val normalizedNumber: String,
    val displayNumber: String,
    val addedTimestamp: Long,
    val addedReason: AddedReason,
)

data class ImportResult(val added: Int, val skipped: Int, val duplicates: Int)

class BlocklistManager(private val context: Context) {

    private val store = ScammerNumberStore(context)
    private val gson = Gson()
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBlocked(number: String): Boolean = store.isMarked(number)

    fun getBlockedEntries(): List<BlocklistEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return buildLegacyEntries()
        return runCatching {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val list: List<Map<String, Any>> = gson.fromJson(raw, type)
            list.map { map ->
                BlocklistEntry(
                    normalizedNumber = map["normalizedNumber"] as? String ?: "",
                    displayNumber = map["displayNumber"] as? String ?: "",
                    addedTimestamp = (map["addedTimestamp"] as? Double)?.toLong() ?: 0L,
                    addedReason = runCatching {
                        AddedReason.valueOf(map["addedReason"] as? String ?: "MANUAL")
                    }.getOrDefault(AddedReason.MANUAL),
                )
            }.filter { it.normalizedNumber.isNotBlank() }
        }.getOrDefault(buildLegacyEntries())
    }

    fun blockNumber(number: String, reason: AddedReason = AddedReason.MANUAL): Boolean {
        val normalized = store.normalize(number)
        if (normalized.isBlank()) return false
        val isNew = store.add(normalized)
        if (!isNew) return false
        val entries = getBlockedEntries().toMutableList()
        entries.removeAll { it.normalizedNumber == normalized }
        entries.add(
            BlocklistEntry(
                normalizedNumber = normalized,
                displayNumber = number.trim(),
                addedTimestamp = System.currentTimeMillis(),
                addedReason = reason,
            ),
        )
        saveEntries(entries)
        broadcastChange()
        return true
    }

    fun unblockNumber(number: String): Boolean {
        val removed = store.remove(number)
        if (removed) {
            val normalized = store.normalize(number)
            val entries = getBlockedEntries().toMutableList()
            entries.removeAll { it.normalizedNumber == normalized }
            saveEntries(entries)
            broadcastChange()
        }
        return removed
    }

    fun getRecentlyBlocked(limit: Int = 20): List<BlocklistEntry> {
        return getBlockedEntries()
            .sortedByDescending { it.addedTimestamp }
            .take(limit)
    }

    fun exportAsCsv(): String {
        return getBlockedEntries().joinToString("\n") { entry ->
            "${entry.displayNumber},${entry.addedReason},${entry.addedTimestamp}"
        }
    }

    fun importFromCsv(csv: String): ImportResult {
        var added = 0
        var skipped = 0
        var duplicates = 0
        csv.lines().forEach { line ->
            val number = line.split(",").firstOrNull()?.trim() ?: return@forEach
            if (number.isBlank()) return@forEach
            val normalized = store.normalize(number)
            if (normalized.isBlank() || normalized.length < 5) {
                skipped++
                return@forEach
            }
            val isNew = blockNumber(number, AddedReason.IMPORTED)
            if (isNew) added++ else duplicates++
        }
        return ImportResult(added = added, skipped = skipped, duplicates = duplicates)
    }

    suspend fun syncCommunityBlocklist(backendUrl: String) = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url("$backendUrl/community-blocklist").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext
                val body = response.body?.string() ?: return@withContext
                val json = runCatching { gson.fromJson(body, Map::class.java) }.getOrNull() ?: return@withContext
                @Suppress("UNCHECKED_CAST")
                val numbers = json["numbers"] as? List<String> ?: return@withContext
                var added = 0
                numbers.forEach { num ->
                    if (blockNumber(num, AddedReason.COMMUNITY)) added++
                }
                Log.d("BlocklistManager", "Community sync: $added new numbers added")
            }
        } catch (e: IOException) {
            Log.w("BlocklistManager", "Community sync failed: ${e.message}")
        }
    }

    private fun buildLegacyEntries(): List<BlocklistEntry> {
        return store.getAll().map { num ->
            BlocklistEntry(
                normalizedNumber = num,
                displayNumber = num,
                addedTimestamp = 0L,
                addedReason = AddedReason.MANUAL,
            )
        }
    }

    private fun saveEntries(entries: List<BlocklistEntry>) {
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()
    }

    private fun broadcastChange() {
        context.sendBroadcast(Intent(ACTION_BLOCKLIST_CHANGED))
    }

    companion object {
        const val ACTION_BLOCKLIST_CHANGED = "com.sentinelx.BLOCKLIST_CHANGED"
        private const val PREFS_NAME = "sentinelx_blocklist"
        private const val KEY_ENTRIES = "entries"
    }
}
