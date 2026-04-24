package com.sentinelx.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GuardianNotifier(
    private val backendUrl: String = DEFAULT_BACKEND_URL,
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun send(context: Context, userId: String, guardianMessage: String, score: Int, threatType: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val payload = mapOf(
                "user_id" to userId,
                "llm_summary" to guardianMessage,
                "score" to score,
                "threat_type" to threatType,
            )
            val request = Request.Builder()
                .url("$backendUrl/notify-guardian")
                .post(gson.toJson(payload).toRequestBody(jsonType))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d("SentinelX", "Guardian notifier success: ${response.code}")
                    } else {
                        Log.w("SentinelX", "Guardian notifier failed: ${response.code}")
                    }
                }
            } catch (e: IOException) {
                Log.w("SentinelX", "Guardian notifier network error: ${e.message}")
            }
        }
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "http://127.0.0.1:8000"
    }
}
