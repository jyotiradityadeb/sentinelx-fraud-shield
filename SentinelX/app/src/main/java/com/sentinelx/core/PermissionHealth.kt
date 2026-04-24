package com.sentinelx.core

import android.app.AppOpsManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings

object PermissionHealth {
    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val normalized = enabled.lowercase()
        val full = "${context.packageName}/com.sentinelx.core.sentinelaccessibilityservice".lowercase()
        val short = "${context.packageName}/.core.sentinelaccessibilityservice".lowercase()
        return normalized.contains(full) || normalized.contains(short)
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
