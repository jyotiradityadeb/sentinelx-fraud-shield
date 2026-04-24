package com.sentinelx.core

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

enum class NumberRiskLevel { LOW, MEDIUM, HIGH, UNKNOWN }

data class NumberRiskPreview(
    val riskLevel: NumberRiskLevel,
    val explanation: String,
    val suggestBlock: Boolean,
)

class AiNumberAnalyzer(private val context: Context) {
    private val gson = Gson()
    private val client = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(number: String): NumberRiskPreview = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("sentinelx_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("demo_mode_enabled", false)) {
            return@withContext fakeDemoResponse(number)
        }
        val backendUrl = BackendConfig.getBackendUrl(context)
        val body = gson.toJson(mapOf("number" to number)).toRequestBody(json)
        val request = Request.Builder().url("$backendUrl/analyze-number").post(body).build()
        return@withContext try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext fallback()
                val bodyStr = response.body?.string() ?: return@withContext fallback()
                @Suppress("UNCHECKED_CAST")
                val map = runCatching { gson.fromJson(bodyStr, Map::class.java) as Map<String, Any> }.getOrNull()
                    ?: return@withContext fallback()
                val level = when ((map["risk_level"] as? String)?.uppercase()) {
                    "HIGH" -> NumberRiskLevel.HIGH
                    "MEDIUM" -> NumberRiskLevel.MEDIUM
                    "LOW" -> NumberRiskLevel.LOW
                    else -> NumberRiskLevel.UNKNOWN
                }
                NumberRiskPreview(
                    riskLevel = level,
                    explanation = map["explanation"] as? String ?: "No details available.",
                    suggestBlock = (map["suggest_block"] as? Boolean) ?: (level == NumberRiskLevel.HIGH),
                )
            }
        } catch (e: IOException) {
            fallback()
        }
    }

    private fun fallback() = NumberRiskPreview(
        riskLevel = NumberRiskLevel.UNKNOWN,
        explanation = "Could not reach backend for analysis.",
        suggestBlock = false,
    )

    private fun fakeDemoResponse(number: String): NumberRiskPreview {
        val digits = number.filter { it.isDigit() }
        return if (digits.startsWith("140") || digits.startsWith("160")) {
            NumberRiskPreview(NumberRiskLevel.HIGH, "Demo: Pattern matches known UPI scam numbers.", true)
        } else {
            NumberRiskPreview(NumberRiskLevel.LOW, "Demo: No known risk signals for this number.", false)
        }
    }
}
