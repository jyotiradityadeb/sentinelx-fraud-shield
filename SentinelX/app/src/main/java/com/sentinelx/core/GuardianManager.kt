package com.sentinelx.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

class GuardianManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "sentinelx_prefs"
        private const val KEY_GUARDIAN_PHONE = "guardian_phone"
        private const val KEY_GUARDIAN_NAME = "guardian_name"
        private const val TAG = "GuardianMgr"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setGuardian(phone: String, name: String) {
        prefs.edit()
            .putString(KEY_GUARDIAN_PHONE, phone)
            .putString(KEY_GUARDIAN_NAME, name)
            .apply()
        Log.d(TAG, "Guardian set: $name ($phone)")
    }

    fun getGuardianPhone(): String? = prefs.getString(KEY_GUARDIAN_PHONE, null)

    fun getGuardianName(): String? = prefs.getString(KEY_GUARDIAN_NAME, null)

    fun hasGuardian(): Boolean = !getGuardianPhone().isNullOrBlank()

    fun sendGuardianSms(score: Int, llmSummary: String, threatType: String): Boolean {
        val phone = getGuardianPhone()
        if (phone.isNullOrBlank()) {
            Log.w(TAG, "No guardian configured, skipping SMS")
            return false
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS permission missing, skipping SMS")
            return false
        }

        val message = buildSmsMessage(score, llmSummary, threatType)
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            Log.d(TAG, "Guardian SMS sent to $phone")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed: ${e.message}", e)
            false
        }
    }

    fun sendGuardianWhatsApp(score: Int, llmSummary: String, threatType: String): Boolean {
        val phone = getGuardianPhone()
        if (phone.isNullOrBlank()) {
            return false
        }
        val digits = phone.filter { it.isDigit() }
        val fullNumber = if (digits.startsWith("91")) digits else "91$digits"
        val message = buildWhatsAppMessage(score, llmSummary, threatType)

        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "https://api.whatsapp.com/send?phone=$fullNumber&text=${Uri.encode(message)}",
                )
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.d(TAG, "WhatsApp opened for guardian: $phone")
                true
            } else {
                Log.w(TAG, "WhatsApp not installed, falling back to SMS")
                sendGuardianSms(score, llmSummary, threatType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp intent failed: ${e.message}", e)
            sendGuardianSms(score, llmSummary, threatType)
        }
    }

    fun alertGuardian(score: Int, llmSummary: String, threatType: String) {
        if (!hasGuardian()) {
            return
        }
        Log.d(TAG, "Alerting guardian. Score=$score, Type=$threatType")
        Handler(Looper.getMainLooper()).post {
            val smsSent = sendGuardianSms(score, llmSummary, threatType)
            if (!smsSent) sendGuardianWhatsApp(score, llmSummary, threatType)
        }
    }

    private fun buildSmsMessage(score: Int, summary: String, type: String): String {
        return "SentinelX Alert\nRisk: $score/120 ($type)\n$summary\nPlease call them now."
    }

    private fun buildWhatsAppMessage(score: Int, summary: String, type: String): String {
        return "SentinelX HIGH RISK Alert\n\nRisk Score: $score/120\nThreat: $type\n\n$summary\n\nPlease contact them immediately."
    }
}
