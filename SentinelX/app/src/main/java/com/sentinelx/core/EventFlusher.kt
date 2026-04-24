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
            Log.d("EventFlusher", "flushAndScore start backend=$backendUrl session=${sessionFeatures.sessionId}")
            flushEventsBatch()

            val payload = sessionFeatures.toPayload()
            Log.d("EventFlusher", "Posting /score-session payload=${gson.toJson(payload)}")
            val scoreResp = postJson("$backendUrl/score-session", gson.toJson(payload)) ?: return@launch
            Log.d("EventFlusher", "/score-session response=$scoreResp")
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
            if (explainResp != null) {
                Log.d("EventFlusher", "/explain response=$explainResp")
            }
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
            val callerTrust = payload.caller_trust.uppercase()
            val shouldIntervene = callerTrust == "SCAMMER_MARKED"
            if (shouldIntervene) {
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
                val intent = android.content.Intent("com.sentinelx.SCORE_RESULT").apply {
                    putExtra("score", score)
                    putExtra("label", label)
                    putExtra("callerPts", scoreJson?.get("sig_caller_trust")?.asInt ?: 0)
                    putExtra("transitionPts", scoreJson?.get("sig_transition")?.asInt ?: 0)
                    putExtra("confirmPts", scoreJson?.get("sig_confirm_press")?.asInt ?: 0)
                    putExtra("behavioralPts", scoreJson?.get("sig_behavioral")?.asInt ?: 0)
                    putExtra("voicePts", scoreJson?.get("sig_voice_stress")?.asInt ?: 0)
                    putExtra("networkPts", scoreJson?.get("sig_network")?.asInt ?: 0)
                }
                context.sendBroadcast(intent)
            } else {
                Log.d("SentinelX", "Intervention skipped for trusted callerTrust=$callerTrust")
            }
        }
    }

    private suspend fun flushEventsBatch() {
        val events = SentinelAccessibilityService.getBufferSnapshot()
        if (events.isEmpty()) return

        Log.d("EventFlusher", "Event batch queued size=${events.size} backend=$backendUrl")
        val batch = EventBatch(
            sessionId = events.lastOrNull()?.sessionId ?: UUID.randomUUID().toString(),
            userId = android.os.Build.ID,
            events = events,
        )
        Log.d("EventFlusher", "Posting /events with size=${events.size}")
        val eventsResp = postJson("$backendUrl/events", batch.toJson())
        if (eventsResp != null) {
            Log.d("EventFlusher", "/events response=$eventsResp")
        }
        val success = eventsResp != null
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
                    Log.w("EventFlusher", "POST failed code=${response.code} url=$url")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: IOException) {
            Log.w("SentinelX", "POST exception for $url: ${e.message}")
            Log.w("EventFlusher", "Network error url=$url message=${e.message}")
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
