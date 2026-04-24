package com.sentinelx.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

data class AppEvent(
    val packageName: String,
    val timestamp: Long,
    val eventType: Int,
)

class AppUsageAnalyzer(private val context: Context) {
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)

    fun getRecentApps(windowMs: Long = 60_000L): List<AppEvent> {
        if (usageStatsManager == null) return emptyList()
        val end = System.currentTimeMillis()
        val start = end - windowMs
        val events = usageStatsManager.queryEvents(start, end)
        val out = mutableListOf<AppEvent>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            ) {
                out += AppEvent(
                    packageName = event.packageName.orEmpty(),
                    timestamp = event.timeStamp,
                    eventType = event.eventType,
                )
            }
        }
        return out.sortedBy { it.timestamp }
    }

    fun getSwitchCount(windowMs: Long = 20_000L): Int {
        val fgEvents = getRecentApps(windowMs).filter { isForegroundEvent(it.eventType) }
        if (fgEvents.isEmpty()) return 0

        var count = 0
        var previous: String? = null
        for (event in fgEvents) {
            if (event.packageName != previous) {
                count++
                previous = event.packageName
            }
        }
        return (count - 1).coerceAtLeast(0)
    }

    fun isMessagingBeforePayment(): Boolean {
        val events = getRecentApps(60_000L).filter { isForegroundEvent(it.eventType) }
        val firstMessaging = events.firstOrNull { it.packageName in MESSAGING_PACKAGES } ?: return false
        val firstPaymentAfter = events.firstOrNull {
            it.packageName in PAYMENT_PACKAGES && it.timestamp > firstMessaging.timestamp
        }
        return firstPaymentAfter != null
    }

    fun getCallToPaymentSeconds(callEndTs: Long): Long {
        if (callEndTs <= 0L) return Long.MAX_VALUE
        val events = getRecentApps(5 * 60_000L).filter { isForegroundEvent(it.eventType) }
        val payment = events.firstOrNull { it.packageName in PAYMENT_PACKAGES && it.timestamp >= callEndTs }
            ?: return Long.MAX_VALUE
        return ((payment.timestamp - callEndTs) / 1000L).coerceAtLeast(0L)
    }

    fun detectWhatsAppVoipToPayment(isOffhookDuringWhatsApp: Boolean, callEndTs: Long): Boolean {
        if (!isOffhookDuringWhatsApp || callEndTs <= 0L) return false
        val cutoff = callEndTs + 30_000L
        val events = getRecentApps(2 * 60_000L).filter { isForegroundEvent(it.eventType) }
        return events.any {
            it.packageName in PAYMENT_PACKAGES && it.timestamp in callEndTs..cutoff
        }
    }

    private fun isForegroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            eventType == UsageEvents.Event.ACTIVITY_RESUMED
    }

    companion object {
        private val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
        )

        private val PAYMENT_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm",
            "in.org.npci.upiapp",
            "com.phonepe.app",
        )
    }
}
