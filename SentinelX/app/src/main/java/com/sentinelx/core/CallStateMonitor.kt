package com.sentinelx.core

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class CallEndEvent(
    val number: String,
    val durationMs: Long,
    val endTs: Long,
)

data class RecentCallInfo(
    val number: String,
    val ts: Long,
)

class CallStateMonitor(
    private val context: Context,
    private val onCallEnd: ((CallEndEvent) -> Unit)? = null,
    private val onStateChanged: ((Int) -> Unit)? = null,
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val callStartTs = AtomicLong(0L)
    private val callAnsweredTs = AtomicLong(0L)
    private val callEndTs = AtomicLong(0L)
    private val callerNumber = AtomicReference<String?>(null)

    @Volatile
    private var lastState: Int = TelephonyManager.CALL_STATE_IDLE

    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    val lastCallerNumber: String?
        get() = callerNumber.get()

    val lastCallEndTs: Long
        get() = callEndTs.get()

    val currentCallState: Int
        get() = lastState

    fun startMonitoring() {
        if (!hasPhonePermission()) {
            Log.w("SentinelX", "CallStateMonitor missing READ_PHONE_STATE")
            return
        }
        if (telephonyManager == null) {
            Log.w("SentinelX", "CallStateMonitor unavailable: TelephonyManager null")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startWithTelephonyCallback()
        } else {
            startWithPhoneStateListener()
        }
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                runCatching { telephonyManager?.unregisterTelephonyCallback(it) }
            }
            telephonyCallback = null
        } else {
            phoneStateListener?.let {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        }
    }

    fun getSecondsSinceCallEnd(): Long {
        val end = callEndTs.get()
        if (end <= 0L) return Long.MAX_VALUE
        return ((System.currentTimeMillis() - end) / 1000L).coerceAtLeast(0L)
    }

    fun getRecentCallNumber(maxAgeSeconds: Long = 600L): String {
        return getRecentCallInfo(maxAgeSeconds)?.number.orEmpty()
    }

    fun getRecentCallInfo(maxAgeSeconds: Long = 600L): RecentCallInfo? {
        if (!hasCallLogPermission()) return null
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )
            if (cursor != null && cursor.moveToFirst()) {
                val number = cursor.getString(0).orEmpty()
                val ts = cursor.getLong(1)
                val ageSeconds = ((System.currentTimeMillis() - ts) / 1000L).coerceAtLeast(0L)
                if (ageSeconds <= maxAgeSeconds) RecentCallInfo(number = number, ts = ts) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun startWithTelephonyCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleStateChange(state, null)
            }
        }
        telephonyCallback = callback
        telephonyManager?.registerTelephonyCallback(context.mainExecutor, callback)
    }

    private fun startWithPhoneStateListener() {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleStateChange(state, phoneNumber)
            }
        }
        phoneStateListener = listener
        @Suppress("DEPRECATION")
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun handleStateChange(state: Int, phoneNumber: String?) {
        onStateChanged?.invoke(state)
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                callStartTs.set(System.currentTimeMillis())
                if (!phoneNumber.isNullOrBlank()) {
                    callerNumber.set(phoneNumber)
                }
                Log.d("SentinelX", "CallStateMonitor: RINGING")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (callStartTs.get() == 0L) {
                    callStartTs.set(System.currentTimeMillis())
                }
                callAnsweredTs.set(System.currentTimeMillis())
                Log.d("SentinelX", "CallStateMonitor: OFFHOOK")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    val endTs = System.currentTimeMillis()
                    val durationMs = if (callAnsweredTs.get() > 0L) endTs - callAnsweredTs.get() else 0L
                    callEndTs.set(endTs)
                    val resolvedNumber = resolveLastNumber().ifBlank { callerNumber.get().orEmpty() }
                    callerNumber.set(resolvedNumber.ifBlank { null })
                    onCallEnd?.invoke(
                        CallEndEvent(
                            number = resolvedNumber,
                            durationMs = durationMs.coerceAtLeast(0L),
                            endTs = endTs,
                        ),
                    )
                    Log.d("SentinelX", "CallStateMonitor: IDLE (call ended, duration=${durationMs}ms)")
                }
            }
        }
        lastState = state
    }

    private fun resolveLastNumber(): String {
        if (!hasCallLogPermission()) return ""
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0).orEmpty()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        } finally {
            cursor?.close()
        }
    }

    private fun hasPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
