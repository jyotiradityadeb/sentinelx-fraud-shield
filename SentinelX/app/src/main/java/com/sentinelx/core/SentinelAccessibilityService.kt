package com.sentinelx.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.sentinelx.data.CallerTrust
import com.sentinelx.data.EventType
import com.sentinelx.data.SentinelEvent
import com.sentinelx.data.SessionFeatures
import java.util.UUID

class SentinelAccessibilityService : AccessibilityService() {
    private var lastWindowChangeTs: Long = 0L
    private var isPaymentSessionActive: Boolean = false
    private var paymentOpenTs: Long = 0L
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var prePaymentAlertShown: Boolean = false
    private var paymentSessionCallerTrust: CallerTrust = CallerTrust.UNKNOWN
    private var paymentSessionHasCallContext: Boolean = false

    private lateinit var eventFlusher: EventFlusher
    private lateinit var callStateMonitor: CallStateMonitor
    private lateinit var appUsageAnalyzer: AppUsageAnalyzer
    private lateinit var voiceStressAnalyzer: VoiceStressAnalyzer
    private lateinit var callerTrustClassifier: CallerTrustClassifier

    override fun onServiceConnected() {
        super.onServiceConnected()
        eventFlusher = EventFlusher(applicationContext)
        callStateMonitor = CallStateMonitor(applicationContext)
        appUsageAnalyzer = AppUsageAnalyzer(applicationContext)
        voiceStressAnalyzer = VoiceStressAnalyzer()
        callerTrustClassifier = CallerTrustClassifier(applicationContext)
        callStateMonitor.startMonitoring()
        currentService = this
        Log.d("SentinelX", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::voiceStressAnalyzer.isInitialized) return
        if (event == null) return

        val mappedType = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> EventType.WINDOW_CHANGE
            AccessibilityEvent.TYPE_VIEW_CLICKED -> EventType.CLICK
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> EventType.CONTENT_CHANGE
            else -> null
        } ?: return

        val ts = System.currentTimeMillis()
        val packageName = event.packageName?.toString().orEmpty()
        val screenName = event.className?.toString().orEmpty()
        val dwellMs = if (lastWindowChangeTs > 0L) ts - lastWindowChangeTs else 0L

        if (mappedType == EventType.WINDOW_CHANGE) {
            lastWindowChangeTs = ts
        }

        val telemetryEvent = SentinelEvent(
            ts = ts,
            sessionId = currentSessionId,
            eventType = mappedType,
            packageName = packageName,
            screenName = screenName,
            dwellMs = dwellMs.coerceAtLeast(0L),
            voiceStressScore = voiceStressAnalyzer.getCurrentStressScore(),
        )
        appendToBuffer(telemetryEvent)

        if (mappedType == EventType.WINDOW_CHANGE && packageName in PAYMENT_PACKAGES && !isPaymentSessionActive) {
            isPaymentSessionActive = true
            paymentOpenTs = ts
            val callContext = resolveCallContextForPaymentOpen()
            paymentSessionCallerTrust = callContext.first
            paymentSessionHasCallContext = callContext.second
            appendToBuffer(
                telemetryEvent.copy(
                    eventType = EventType.PAYMENT_OPEN,
                    dwellMs = 0L,
                ),
            )
            Log.d("SentinelX", "Payment app detected: $packageName")
            maybeShowPrePaymentIntervention()
        }
        if (mappedType == EventType.WINDOW_CHANGE && packageName !in PAYMENT_PACKAGES && isPaymentSessionActive) {
            onPaymentSessionExit(ts, packageName)
        }

        if (isPaymentSessionActive && (screenName.contains("Confirm", true) || screenName.contains("Success", true))) {
            Log.d("SentinelX", "Payment confirm detected on screen: $screenName")
            onPaymentConfirm(ts)
        }
    }

    override fun onInterrupt() {
        Log.d("SentinelX", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::callStateMonitor.isInitialized) {
            callStateMonitor.stopMonitoring()
        }
        if (::eventFlusher.isInitialized) {
            eventFlusher.stopFlushing()
        }
        currentService = null
    }

    private fun onPaymentConfirm(ts: Long) {
        if (!isPaymentSessionActive) return

        val trustNow = mapCallerTrust(resolveCallerNumber())
        if (trustNow != CallerTrust.SCAMMER_MARKED) {
            Log.d("SentinelX", "Payment allowed - caller not in blocked list (${trustNow.name})")
            resetPaymentSession()
            currentSessionId = UUID.randomUUID().toString()
            return
        }

        val confirmDwellMs = (ts - paymentOpenTs).coerceAtLeast(0L)
        appendToBuffer(
            SentinelEvent(
                ts = ts,
                sessionId = currentSessionId,
                eventType = EventType.PAYMENT_CONFIRM,
                dwellMs = confirmDwellMs,
                voiceStressScore = voiceStressAnalyzer.getCurrentStressScore(),
            ),
        )
        Log.d("SentinelX", "Payment confirm event added, dwellMs=$confirmDwellMs, buffer=${getBufferSnapshot().size}")

        val callerTrust = paymentSessionCallerTrust
        val snapshot = getBufferSnapshot()
        val callEndTs = callStateMonitor.lastCallEndTs.takeIf { it > 0L }
            ?: callStateMonitor.getRecentCallInfo(maxAgeSeconds = 300L)?.ts
            ?: 0L
        val features = SessionFeatures.fromEvents(
            events = snapshot,
            sessionId = currentSessionId,
            userId = android.os.Build.ID,
            callEndTs = callEndTs,
            callerTrust = callerTrust,
            voiceStressScore = voiceStressAnalyzer.getCurrentStressScore(),
            networkThreatScore = 0,
            geoHash = "tdr1j",
        )

        val secsSinceCall = callStateMonitor.getSecondsSinceCallEnd()
        if ((trustNow == CallerTrust.KNOWN_CONTACT || trustNow == CallerTrust.BUSINESS_NUMBER) && secsSinceCall > 300L) {
            Log.d("SentinelX", "Safe payment - known contact, no pressure. Skipping risk score.")
            resetPaymentSession()
            currentSessionId = UUID.randomUUID().toString()
            return
        }

        eventFlusher.flushAndScore(features)
        resetPaymentSession()
        currentSessionId = UUID.randomUUID().toString()
    }

    private fun onPaymentSessionExit(ts: Long, nextPackage: String) {
        if (!isPaymentSessionActive) return
        if (nextPackage in PAYMENT_PACKAGES) return
        val sessionAgeMs = (ts - paymentOpenTs).coerceAtLeast(0L)
        if (sessionAgeMs < 1500L) {
            return
        }
        Log.d("SentinelX", "Payment session exit detected, finalizing session (ageMs=$sessionAgeMs)")
        onPaymentConfirm(ts)
    }

    private fun mapCallerTrust(number: String?): CallerTrust {
        if (number.isNullOrBlank()) return CallerTrust.UNKNOWN
        val labels = callerTrustClassifier.classify(number)
        return when {
            labels.contains(CallerTrustClassifier.CallerTrust.SCAMMER_MARKED) -> CallerTrust.SCAMMER_MARKED
            labels.contains(CallerTrustClassifier.CallerTrust.KNOWN_CONTACT) -> CallerTrust.KNOWN_CONTACT
            labels.contains(CallerTrustClassifier.CallerTrust.REPEATED_UNKNOWN) -> CallerTrust.REPEATED_UNKNOWN
            labels.contains(CallerTrustClassifier.CallerTrust.BUSINESS_NUMBER) -> CallerTrust.BUSINESS_NUMBER
            else -> CallerTrust.UNKNOWN
        }
    }

    private fun resetPaymentSession() {
        isPaymentSessionActive = false
        paymentOpenTs = 0L
        prePaymentAlertShown = false
        paymentSessionCallerTrust = CallerTrust.UNKNOWN
        paymentSessionHasCallContext = false
    }

    private fun resolveCallerNumber(): String? {
        val direct = callStateMonitor.lastCallerNumber
        if (!direct.isNullOrBlank()) return direct
        return callStateMonitor.getRecentCallNumber(maxAgeSeconds = 600L).ifBlank { null }
    }

    private fun maybeShowPrePaymentIntervention() {
        if (prePaymentAlertShown) return

        val resolvedNumber = resolveCallerNumber()
        if (resolvedNumber.isNullOrBlank()) {
            Log.d("SentinelX", "Pre-payment skipped - caller number unavailable")
            return
        }
        val callerTrust = mapCallerTrust(resolvedNumber)
        if (callerTrust != CallerTrust.SCAMMER_MARKED) {
            Log.d("SentinelX", "Pre-payment skipped - non-blocked caller: ${callerTrust.name}")
            return
        }

        val secondsSinceEnd = callStateMonitor.getSecondsSinceCallEnd()
        val callActive = callStateMonitor.currentCallState != TelephonyManager.CALL_STATE_IDLE
        val recentCall = callStateMonitor.getRecentCallInfo(maxAgeSeconds = 900L)
        val recentCallContext = callActive || secondsSinceEnd <= 180L || recentCall != null
        if (!recentCallContext) return

        val isMarkedScammer = callerTrust == CallerTrust.SCAMMER_MARKED
        if (!isMarkedScammer) return
        val score = 96
        val label = "HIGH_RISK"
        val message = "Critical risk: this caller is in your scammer list. Stop payment and verify through a trusted channel."
        val signals = listOf(
            "Recent call context",
            "Payment app opened under pressure",
            "Caller trust: ${callerTrust.name}",
        )
        com.sentinelx.ui.InterventionManager.show(
            context = applicationContext,
            score = score,
            label = label,
            llmUserPrompt = message,
            signals = signals,
        )
        prePaymentAlertShown = true
        Log.d("SentinelX", "Pre-payment intervention shown score=$score callerTrust=${callerTrust.name}")
    }

    private fun resolveCallContextForPaymentOpen(): Pair<CallerTrust, Boolean> {
        val callActive = callStateMonitor.currentCallState != TelephonyManager.CALL_STATE_IDLE
        if (callActive) {
            val number = resolveCallerNumber()
            return mapCallerTrust(number) to true
        }
        val recentCall = callStateMonitor.getRecentCallInfo(maxAgeSeconds = 180L)
        if (recentCall != null) {
            return mapCallerTrust(recentCall.number) to true
        }
        return CallerTrust.UNKNOWN to false
    }

    private fun appendToBuffer(event: SentinelEvent) {
        synchronized(bufferLock) {
            if (eventBuffer.size >= MAX_BUFFER_SIZE) {
                eventBuffer.removeFirst()
            }
            eventBuffer.addLast(event)
            Log.d(
                "SentinelX",
                "Event collected type=${event.eventType} pkg=${event.packageName} queued=${eventBuffer.size}",
            )
        }
    }

    fun getBufferSnapshot(): List<SentinelEvent> = Companion.getBufferSnapshot()

    fun clearBuffer() = Companion.clearBuffer()

    companion object {
        val PAYMENT_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm",
            "in.org.npci.upiapp",
            "com.phonepe.app",
            "com.amazon.mShop.android.shopping",
        )

        private const val MAX_BUFFER_SIZE = 200
        private val bufferLock = Any()
        private val eventBuffer = ArrayDeque<SentinelEvent>(MAX_BUFFER_SIZE)

        @Volatile
        private var currentService: SentinelAccessibilityService? = null

        fun getBufferSnapshot(): List<SentinelEvent> {
            synchronized(bufferLock) {
                return eventBuffer.toList()
            }
        }

        fun clearBuffer() {
            synchronized(bufferLock) {
                eventBuffer.clear()
            }
            Log.d("SentinelX", "Accessibility buffer cleared")
        }
    }
}
