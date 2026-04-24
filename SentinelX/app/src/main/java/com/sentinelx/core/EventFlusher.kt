package com.sentinelx.core

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sentinelx.data.EventBatch
import com.sentinelx.data.SessionFeatures
import com.sentinelx.ui.InterventionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

data class SessionFeaturesPayload(
    val session_id: String,
    val user_id: String,
    val seconds_since_call: Long,
    val switch_count_20s: Int,
    val confirm_dwell_ms: Long,
    val tap_density: Float,
    val revisit_count: Int,
    val session_duration_ms: Long,
    val caller_trust: String,
    val is_messaging_before: Boolean,
    val is_whatsapp_voip: Boolean,
    val voice_stress_score: Float,
    val network_threat_score: Int,
    val geo_hash: String,
)

class EventFlusher(
    private val context: Context,
) {
    private val backendUrl: String
        get() = BackendConfig.getBackendUrl(context)

    private val gson = Gson()
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private var periodicJob: Job? = null
    private val guardianManager = GuardianManager(context)

    fun startFlushing() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive) {
                flushEventsBatch()
                delay(5_000L)
            }
        }
    }

    fun stopFlushing() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun flushAndScore(sessionFeatures: SessionFeatures) {
        scope.launch {
            flushEventsBatch()

            val payload = sessionFeatures.toPayload()
            val scoreResp = postJson("$backendUrl/score-session", gson.toJson(payload)) ?: return@launch
            val scoreJson = parseJson(scoreResp)

            val score = scoreJson?.get("score")?.asInt
                ?: scoreJson?.get("total_score")?.asInt
                ?: 0
            val label = scoreJson?.get("label")?.asString ?: "SAFE"
            val scoredSessionId = scoreJson?.get("session_id")?.asString
            val triggeredSignals = scoreJson?.get("triggered_signals")

            val explainRequest = JsonObject().apply {
                add("risk_result", JsonObject().apply {
                    addProperty("score", score)
                    addProperty("label", label)
                    if (!scoredSessionId.isNullOrBlank()) addProperty("session_id", scoredSessionId)
                    if (triggeredSignals != null) add("triggered_signals", triggeredSignals)
                })
                add("session_features", gson.toJsonTree(payload))
            }

            val explainResp = postJson("$backendUrl/explain", gson.toJson(explainRequest))
            val explainJson = explainResp?.let { parseJson(it) }

            val userPrompt = explainJson?.get("user_prompt")?.asString ?: "Suspicious behavior detected."
            val guardianMessage = explainJson?.get("guardian_message")?.asString ?: "Please contact immediately."
            val threatType = explainJson?.get("threat_type")?.asString ?: "UNKNOWN"

            val signalList = runCatching {
                triggeredSignals?.asJsonArray?.mapNotNull { it.toString() } ?: emptyList()
            }.getOrDefault(emptyList())
            SentinelForegroundService.updateLiveStatus(
                context = context,
                status = "Risk $score/120 ($label)",
            )
            InterventionManager.show(
                context = context,
                score = score,
                label = label,
                llmUserPrompt = userPrompt,
                signals = signalList,
            )
            if (label.equals("HIGH_RISK", ignoreCase = true)) {
                guardianManager.alertGuardian(
                    score = score,
                    llmSummary = guardianMessage,
                    threatType = threatType,
                )
            }
        }
    }

    private suspend fun flushEventsBatch() {
        val events = SentinelAccessibilityService.getBufferSnapshot()
        if (events.isEmpty()) return

        val batch = EventBatch(
            sessionId = events.lastOrNull()?.sessionId ?: UUID.randomUUID().toString(),
            userId = android.os.Build.ID,
            events = events,
        )
        val success = postJson("$backendUrl/events", batch.toJson()) != null
        if (success) {
            SentinelAccessibilityService.clearBuffer()
            Log.d("SentinelX", "Flushed ${events.size} events")
            SentinelForegroundService.updateLiveStatus(
                context = context,
                status = "Live: flushed ${events.size} events",
            )
        } else {
            Log.d("SentinelX", "Failed to flush events; buffer retained (${events.size})")
            SentinelForegroundService.updateLiveStatus(
                context = context,
                status = "Network issue: events buffered (${events.size})",
            )
        }
    }

    private suspend fun postJson(url: String, json: String): String? = withContext(Dispatchers.IO) {
        val body = json.toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()
        return@withContext try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SentinelX", "POST failed ${response.code} for $url")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: IOException) {
            Log.w("SentinelX", "POST exception for $url: ${e.message}")
            null
        }
    }

    private fun parseJson(payload: String): JsonObject? {
        return runCatching {
            JsonParser.parseString(payload).asJsonObject
        }.getOrNull()
    }

    private fun SessionFeatures.toPayload(): SessionFeaturesPayload {
        return SessionFeaturesPayload(
            session_id = sessionId,
            user_id = userId,
            seconds_since_call = secondsSinceCall,
            switch_count_20s = switchCount20s,
            confirm_dwell_ms = confirmDwellMs,
            tap_density = tapDensity,
            revisit_count = revisitCount,
            session_duration_ms = sessionDurationMs,
            caller_trust = callerTrust.name,
            is_messaging_before = isMessagingBeforePayment,
            is_whatsapp_voip = isWhatsAppVoip,
            voice_stress_score = voiceStressScore,
            network_threat_score = networkThreatScore,
            geo_hash = geoHash,
        )
    }
}
