package com.sentinelx.data

import androidx.annotation.Keep
import com.google.gson.Gson

enum class EventType {
    WINDOW_CHANGE,
    CLICK,
    CONTENT_CHANGE,
    SCROLL,
    PAYMENT_OPEN,
    PAYMENT_CONFIRM,
    PAYMENT_CANCEL,
    CALL_START,
    CALL_END,
    VOICE_SAMPLE,
    APP_BACKGROUND,
    APP_FOREGROUND,
    NOTIFICATION_SHOWN,
    INTERVENTION_SHOWN,
    GUARDIAN_ALERTED,
}

enum class CallStateType { IDLE, RINGING, OFFHOOK }

enum class CallerTrust(val riskPts: Int) {
    KNOWN_CONTACT(0),
    BUSINESS_NUMBER(5),
    UNKNOWN(15),
    REPEATED_UNKNOWN(22),
    SCAMMER_MARKED(30),
}

enum class InteractionType {
    TAP,
    LONG_PRESS,
    SCROLL,
    CONFIRM_BTN,
    CANCEL_BTN,
    BACK_PRESS,
}

@Keep
data class SentinelEvent(
    val ts: Long = System.currentTimeMillis(),
    val sessionId: String,
    val userId: String = android.os.Build.ID,
    val eventType: EventType,
    val packageName: String = "",
    val screenName: String = "",
    val callState: CallStateType = CallStateType.IDLE,
    val callerTrust: CallerTrust = CallerTrust.UNKNOWN,
    val interactionType: InteractionType? = null,
    val dwellMs: Long = 0L,
    val voiceStressScore: Float = 0.0f,
    val networkThreatScore: Int = 0,
    val geoHash: String = "tdr1j",
)

@Keep
data class EventBatch(
    val sessionId: String,
    val userId: String,
    val events: List<SentinelEvent>,
    val batchTs: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): EventBatch = Gson().fromJson(json, EventBatch::class.java)
    }
}

@Keep
data class SessionFeatures(
    val sessionId: String,
    val userId: String,
    val secondsSinceCall: Long,
    val switchCount20s: Int,
    val confirmDwellMs: Long,
    val tapDensity: Float,
    val revisitCount: Int,
    val sessionDurationMs: Long,
    val callerTrust: CallerTrust,
    val isMessagingBeforePayment: Boolean,
    val isWhatsAppVoip: Boolean,
    val voiceStressScore: Float,
    val networkThreatScore: Int,
    val geoHash: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "session_id" to sessionId,
        "user_id" to userId,
        "seconds_since_call" to secondsSinceCall,
        "switch_count_20s" to switchCount20s,
        "confirm_dwell_ms" to confirmDwellMs,
        "tap_density" to tapDensity,
        "revisit_count" to revisitCount,
        "session_duration_ms" to sessionDurationMs,
        "caller_trust" to callerTrust.name,
        "is_messaging_before" to isMessagingBeforePayment,
        "is_whatsapp_voip" to isWhatsAppVoip,
        "voice_stress_score" to voiceStressScore,
        "network_threat_score" to networkThreatScore,
        "geo_hash" to geoHash,
    )

    companion object {
        private val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.truecaller",
            "com.instagram.android",
            "com.facebook.orca",
        )

        fun fromEvents(
            events: List<SentinelEvent>,
            sessionId: String,
            userId: String,
            callEndTs: Long,
            callerTrust: CallerTrust,
            voiceStressScore: Float,
            networkThreatScore: Int,
            geoHash: String,
        ): SessionFeatures {
            if (events.isEmpty()) {
                return SessionFeatures(
                    sessionId = sessionId,
                    userId = userId,
                    secondsSinceCall = 9999L,
                    switchCount20s = 0,
                    confirmDwellMs = 14000L,
                    tapDensity = 0.0f,
                    revisitCount = 0,
                    sessionDurationMs = 0L,
                    callerTrust = callerTrust,
                    isMessagingBeforePayment = false,
                    isWhatsAppVoip = false,
                    voiceStressScore = voiceStressScore,
                    networkThreatScore = networkThreatScore,
                    geoHash = geoHash,
                )
            }

            val now = System.currentTimeMillis()
            val paymentConfirmEvent = events.lastOrNull { it.eventType == EventType.PAYMENT_CONFIRM }
            val paymentOpenEvent = events.firstOrNull { it.eventType == EventType.PAYMENT_OPEN }

            val confirmDwellMs = if (paymentConfirmEvent != null && paymentOpenEvent != null) {
                (paymentConfirmEvent.ts - paymentOpenEvent.ts).coerceAtLeast(0L)
            } else {
                14000L
            }

            val last20sTs = now - 20_000L
            val switchCount20s = events
                .asSequence()
                .filter { it.ts >= last20sTs && it.eventType == EventType.WINDOW_CHANGE }
                .map { it.packageName }
                .filter { it.isNotBlank() }
                .distinct()
                .count()

            val paymentOpenTs = paymentOpenEvent?.ts ?: events.first().ts
            val paymentConfirmTs = paymentConfirmEvent?.ts ?: now
            val paymentWindowMs = (paymentConfirmTs - paymentOpenTs).coerceAtLeast(1L)

            val tapEvents = events.count {
                it.interactionType == InteractionType.TAP && it.ts >= paymentOpenTs && it.ts <= paymentConfirmTs
            }
            val tapDensity = tapEvents.toFloat() / (paymentWindowMs / 1000f).coerceAtLeast(0.1f)

            val revisitCount = events.count { it.eventType == EventType.PAYMENT_OPEN }
            val sessionDurationMs = (events.last().ts - events.first().ts).coerceAtLeast(0L)

            val isMessagingBeforePayment = paymentOpenEvent?.let { open ->
                events.any { e -> e.packageName in MESSAGING_PACKAGES && e.ts < open.ts }
            } ?: false

            val isWhatsAppVoip = callerTrust != CallerTrust.KNOWN_CONTACT &&
                events.any { it.packageName == "com.whatsapp" && it.callState == CallStateType.OFFHOOK }

            val secondsSinceCall = if (callEndTs > 0 && paymentConfirmEvent != null) {
                ((paymentConfirmEvent.ts - callEndTs) / 1000L).coerceAtLeast(0L)
            } else {
                9999L
            }

            return SessionFeatures(
                sessionId = sessionId,
                userId = userId,
                secondsSinceCall = secondsSinceCall,
                switchCount20s = switchCount20s,
                confirmDwellMs = confirmDwellMs,
                tapDensity = tapDensity,
                revisitCount = revisitCount,
                sessionDurationMs = sessionDurationMs,
                callerTrust = callerTrust,
                isMessagingBeforePayment = isMessagingBeforePayment,
                isWhatsAppVoip = isWhatsAppVoip,
                voiceStressScore = voiceStressScore,
                networkThreatScore = networkThreatScore,
                geoHash = geoHash,
            )
        }

        fun fromDemo() = SessionFeatures(
            sessionId = java.util.UUID.randomUUID().toString(),
            userId = "demo_user",
            secondsSinceCall = 18L,
            switchCount20s = 5,
            confirmDwellMs = 1100L,
            tapDensity = 6.3f,
            revisitCount = 3,
            sessionDurationMs = 19000L,
            callerTrust = CallerTrust.REPEATED_UNKNOWN,
            isMessagingBeforePayment = true,
            isWhatsAppVoip = true,
            voiceStressScore = 0.87f,
            networkThreatScore = 12,
            geoHash = "tdr1j",
        )

        fun fromSafeDemo() = SessionFeatures(
            sessionId = java.util.UUID.randomUUID().toString(),
            userId = "demo_safe_user",
            secondsSinceCall = 640L,
            switchCount20s = 0,
            confirmDwellMs = 12500L,
            tapDensity = 1.1f,
            revisitCount = 0,
            sessionDurationMs = 36000L,
            callerTrust = CallerTrust.KNOWN_CONTACT,
            isMessagingBeforePayment = false,
            isWhatsAppVoip = false,
            voiceStressScore = 0.11f,
            networkThreatScore = 0,
            geoHash = "tdr1j",
        )
    }
}
