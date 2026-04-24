package com.sentinelx.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {
    private val engine = RiskEngine()

    @Test
    fun demoScenarioIsHighRisk() {
        val result = engine.score(RiskEngine.demo())
        assertTrue(result.totalScore >= 80)
        assertEquals("HIGH_RISK", result.label)
        assertFalse(result.triggeredSignals.isEmpty())
    }

    @Test
    fun knownContactNormalBehaviorIsSafe() {
        val features = SessionFeatures(
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
        val result = engine.score(features)
        assertTrue(result.totalScore in 0..39)
        assertEquals("SAFE", result.label)
    }

    @Test
    fun voiceStressPlusUnknownCallerIsSuspicious() {
        val features = SessionFeatures(
            secondsSinceCall = 20,
            switchCount20s = 1,
            confirmDwellMs = 10_000,
            tapDensity = 1.2f,
            revisitCount = 0,
            sessionDurationMs = 60_000,
            callerTrust = "UNKNOWN",
            isMessagingBeforePayment = false,
            isWhatsAppVoip = false,
            voiceStressScore = 0.9f,
            networkThreatScore = 0,
            behavioralAnomalyScore = 0.0f,
        )
        val result = engine.score(features)
        assertTrue(result.totalScore in 40..79)
        assertEquals("SUSPICIOUS", result.label)
    }

    @Test
    fun networkThreatIsCappedAtFifteen() {
        val features = RiskEngine.safeDemo().copy(networkThreatScore = 999)
        val result = engine.score(features)
        assertEquals(15, result.networkThreatPts)
    }
}
