package com.sentinelx.core

import android.content.Context
import android.os.Build

object BackendConfig {
    private const val PREFS = "sentinelx_prefs"
    private const val KEY_BACKEND_URL = "backend_url"
    const val DEFAULT_BACKEND_URL = "http://127.0.0.1:8000"

    fun getBackendUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL)
            ?.trim()
            ?.removeSuffix("/")
            .orEmpty()
            .ifBlank { DEFAULT_BACKEND_URL }
        if (raw.contains("10.0.2.2") && !isEmulator()) {
            return raw.replace("10.0.2.2", "127.0.0.1")
        }
        return raw
    }

    fun setBackendUrl(context: Context, url: String) {
        val normalized = url.trim().removeSuffix("/")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_URL, normalized.ifBlank { DEFAULT_BACKEND_URL })
            .apply()
    }

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        return fp.contains("generic") || fp.contains("emulator") || model.contains("emulator") || model.contains("sdk")
    }
}
