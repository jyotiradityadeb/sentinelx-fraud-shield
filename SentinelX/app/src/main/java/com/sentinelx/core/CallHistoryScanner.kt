package com.sentinelx.core

import android.Manifest
import android.content.Context
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class ScanProgress(
    val scanned: Int,
    val total: Int,
    val suspiciousFound: Int,
    val currentNumber: String,
    val suspiciousNumbers: List<String>,
    val done: Boolean = false,
)

class CallHistoryScanner(private val context: Context) {

    fun scanRecentCalls(days: Int = 30): Flow<ScanProgress> = flow {
        if (!hasCallLogPermission()) {
            emit(ScanProgress(0, 0, 0, "", emptyList(), done = true))
            return@flow
        }
        val blocklistManager = BlocklistManager(context)
        val cutoffTs = System.currentTimeMillis() - days * 86_400_000L
        val numbers = mutableMapOf<String, MutableList<Long>>()

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            "${CallLog.Calls.DATE} >= ?",
            arrayOf(cutoffTs.toString()),
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
            while (cursor.moveToNext()) {
                val num = cursor.getString(numIdx).orEmpty().filter { it.isDigit() || it == '+' }
                val date = cursor.getLong(dateIdx)
                if (num.isNotBlank()) numbers.getOrPut(num) { mutableListOf() }.add(date)
            }
        }

        val unique = numbers.keys.toList()
        val total = unique.size
        val suspicious = mutableListOf<String>()
        var scanned = 0

        for (number in unique) {
            scanned++
            val timestamps = numbers[number] ?: emptyList()
            val isSuspicious = isSuspiciousPattern(number, timestamps, blocklistManager)
            if (isSuspicious) suspicious.add(number)
            emit(ScanProgress(scanned, total, suspicious.size, number, suspicious.toList()))
        }
        emit(ScanProgress(scanned, total, suspicious.size, "", suspicious.toList(), done = true))
    }.flowOn(Dispatchers.IO)

    private fun isSuspiciousPattern(
        number: String,
        timestamps: List<Long>,
        blocklistManager: BlocklistManager,
    ): Boolean {
        if (blocklistManager.isBlocked(number)) return true
        if (timestamps.size >= 3) return true
        // Calls at odd hours (between 10pm and 7am) are suspicious
        val oddHourCalls = timestamps.count { ts ->
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = ts }.get(java.util.Calendar.HOUR_OF_DAY)
            hour >= 22 || hour < 7
        }
        if (oddHourCalls >= 2) return true
        return false
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
