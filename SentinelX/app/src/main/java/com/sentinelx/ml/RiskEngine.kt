package com.sentinelx.ml

data class SessionFeatures(
    val secondsSinceCall: Long,
    val switchCount20s: Int,
    val confirmDwellMs: Long,
    val tapDensity: Float,
    val revisitCount: Int,
    val sessionDurationMs: Long,
    val callerTrust: String,
    val isMessagingBeforePayment: Boolean,
    val isWhatsAppVoip: Boolean,
    val voiceStressScore: Float,
    val networkThreatScore: Int,
    val behavioralAnomalyScore: Float,
)

data class RiskResult(
    val totalScore: Int,
    val label: String,
    val triggeredSignals: List<Pair<String, Int>>,
    val callerTrustPts: Int,
    val transitionVelocityPts: Int,
    val confirmPressurePts: Int,
    val behavioralDeviationPts: Int,
    val voiceStressIndexPts: Int,
    val networkThreatPts: Int,
)

class RiskEngine {
    fun score(f: SessionFeatures): RiskResult {
        val callerTrust = calcCallerTrustIndex(f)
        val transitionVelocity = calcTransitionVelocityScore(f)
        val confirmationPressure = calcConfirmationPressureScore(f)
        val behavioralDeviation = calcBehavioralDeviationScore(f)
        val voiceStress = calcVoiceStressIndex(f)
        val networkThreat = calcNetworkThreatScore(f)

        val total = callerTrust + transitionVelocity + confirmationPressure +
            behavioralDeviation + voiceStress + networkThreat

        val label = when {
            total >= 80 -> "HIGH_RISK"
            total >= 40 -> "SUSPICIOUS"
            else -> "SAFE"
        }

        val signals = listOf(
            "Caller Trust Index" to callerTrust,
            "Transition Velocity" to transitionVelocity,
            "Confirmation Pressure" to confirmationPressure,
            "Behavioral Deviation" to behavioralDeviation,
            "Voice Stress Index" to voiceStress,
            "Network Threat Score" to networkThreat,
        ).filter { it.second > 0 }

        return RiskResult(
            totalScore = total,
            label = label,
            triggeredSignals = signals,
            callerTrustPts = callerTrust,
            transitionVelocityPts = transitionVelocity,
            confirmPressurePts = confirmationPressure,
            behavioralDeviationPts = behavioralDeviation,
            voiceStressIndexPts = voiceStress,
            networkThreatPts = networkThreat,
        )
    }

    private fun calcCallerTrustIndex(f: SessionFeatures): Int {
        val points = when (f.callerTrust) {
            "KNOWN_CONTACT" -> 0
            "BUSINESS_NUMBER" -> 5
            "UNKNOWN" -> 15
            "REPEATED_UNKNOWN" -> 22
            "SCAMMER_MARKED" -> 30
            else -> 15
        }
        return points.coerceAtMost(30)
    }

    private fun calcTransitionVelocityScore(f: SessionFeatures): Int {
        var points = 0
        if (f.secondsSinceCall < 30) {
            points += 16
        } else if (f.secondsSinceCall in 30..60) {
            points += 8
        }

        points += when {
            f.switchCount20s >= 4 -> 12
            f.switchCount20s >= 2 -> 6
            else -> 0
        }

        if (f.isMessagingBeforePayment) points += 8
        if (f.isWhatsAppVoip) points += 12
        return points.coerceAtMost(20)
    }

    private fun calcConfirmationPressureScore(f: SessionFeatures): Int {
        var points = 0
        if (f.confirmDwellMs < 2_000) {
            points += 12
        } else if (f.confirmDwellMs in 2_000..5_000) {
            points += 6
        }

        points += when {
            f.tapDensity > 5.0f -> 8
            f.tapDensity > 3.0f -> 4
            else -> 0
        }

        if (f.revisitCount > 2) points += 6
        return points.coerceAtMost(20)
    }

    private fun calcBehavioralDeviationScore(f: SessionFeatures): Int {
        return (f.behavioralAnomalyScore * 18f).toInt().coerceIn(0, 18)
    }

    private fun calcVoiceStressIndex(f: SessionFeatures): Int {
        return when {
            f.voiceStressScore < 0.2f -> 0
            f.voiceStressScore < 0.4f -> 8
            f.voiceStressScore < 0.6f -> 14
            f.voiceStressScore < 0.8f -> 18
            else -> 22
        }
    }

    private fun calcNetworkThreatScore(f: SessionFeatures): Int {
        return f.networkThreatScore.coerceIn(0, 15)
    }

    companion object {
        fun demo(): SessionFeatures = SessionFeatures(
            secondsSinceCall = 18,
            switchCount20s = 5,
            confirmDwellMs = 1100,
            tapDensity = 6.3f,
            revisitCount = 3,
            sessionDurationMs = 24_000,
            callerTrust = "REPEATED_UNKNOWN",
            isMessagingBeforePayment = true,
            isWhatsAppVoip = true,
            voiceStressScore = 0.87f,
            networkThreatScore = 12,
            behavioralAnomalyScore = 0.82f,
        )

        fun safeDemo(): SessionFeatures = SessionFeatures(
            secondsSinceCall = 9_999,
            switchCount20s = 0,
            confirmDwellMs = 14_000,
            tapDensity = 1.0f,
            revisitCount = 0,
            sessionDurationMs = 60_000,
            callerTrust = "KNOWN_CONTACT",
            isMessagingBeforePayment = false,
            isWhatsAppVoip = false,
            voiceStressScore = 0.05f,
            networkThreatScore = 0,
            behavioralAnomalyScore = 0.0f,
        )
    }
}
