package com.sentinelx.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for EventFlusher intervention gate logic (extracted as pure functions for testability). */
class InterventionGateTest {

    private fun shouldIntervene(
        callerTrust: String,
        score: Int,
        label: String,
    ): Boolean {
        val trust = callerTrust.uppercase()
        val isBlocklisted = trust == "SCAMMER_MARKED"
        val isHighRisk = score >= 80 || label.equals("HIGH_RISK", ignoreCase = true)
        val isSuspicious = score >= 40 || label.equals("SUSPICIOUS", ignoreCase = true)
        val should = isBlocklisted || isHighRisk || isSuspicious
        val isTrustedCaller = trust == "KNOWN_CONTACT"
        return should && !(isTrustedCaller && score < 40)
    }

    @Test
    fun blocklistedNumberAlwaysIntervenes() {
        // Blocklist always wins, even with a low score
        assertTrue(shouldIntervene("SCAMMER_MARKED", 5, "SAFE"))
        assertTrue(shouldIntervene("SCAMMER_MARKED", 90, "HIGH_RISK"))
    }

    @Test
    fun highRiskScoreIntervenes() {
        assertTrue(shouldIntervene("UNKNOWN", 80, "HIGH_RISK"))
        assertTrue(shouldIntervene("UNKNOWN", 100, "HIGH_RISK"))
    }

    @Test
    fun suspiciousScoreIntervenes() {
        assertTrue(shouldIntervene("UNKNOWN", 40, "SUSPICIOUS"))
        assertTrue(shouldIntervene("UNKNOWN", 60, "SUSPICIOUS"))
    }

    @Test
    fun knownContactLowScoreNoIntervention() {
        assertFalse(shouldIntervene("KNOWN_CONTACT", 10, "SAFE"))
        assertFalse(shouldIntervene("KNOWN_CONTACT", 30, "SAFE"))
    }

    @Test
    fun knownContactHighScoreIntervenes() {
        // Even a known contact should trigger alert if score is high enough
        assertTrue(shouldIntervene("KNOWN_CONTACT", 80, "HIGH_RISK"))
    }

    @Test
    fun safeScoreUnknownCallerNoIntervention() {
        assertFalse(shouldIntervene("UNKNOWN", 10, "SAFE"))
        assertFalse(shouldIntervene("UNKNOWN", 20, "SAFE"))
    }
}
