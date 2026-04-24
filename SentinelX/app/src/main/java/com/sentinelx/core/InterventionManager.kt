package com.sentinelx.core

import android.content.Context
import android.util.Log

object InterventionManager {
    fun show(context: Context, userPrompt: String, dashboardSummary: String) {
        Log.d("SentinelX", "Intervention prompt: $userPrompt")
        Log.d("SentinelX", "Dashboard summary: $dashboardSummary")
    }
}
