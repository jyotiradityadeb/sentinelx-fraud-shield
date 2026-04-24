package com.sentinelx.ui

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sentinelx.R
import com.sentinelx.core.SentinelForegroundService

class OnboardingActivity : AppCompatActivity() {
    private var screenIndex = 0

    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var transparencyList: TextView
    private lateinit var nextButton: Button
    private lateinit var enableButton: Button
    private lateinit var usageButton: Button
    private lateinit var guardianButton: Button
    private lateinit var startButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        bindViews()
        wireActions()
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        if (screenIndex == 2) {
            updateStatus()
        }
    }

    private fun bindViews() {
        titleText = findViewById(R.id.titleText)
        bodyText = findViewById(R.id.bodyText)
        transparencyList = findViewById(R.id.transparencyList)
        nextButton = findViewById(R.id.nextButton)
        enableButton = findViewById(R.id.enableButton)
        usageButton = findViewById(R.id.usageButton)
        guardianButton = findViewById(R.id.guardianButton)
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)
    }

    private fun wireActions() {
        nextButton.setOnClickListener {
            if (screenIndex < 2) {
                screenIndex += 1
                renderScreen()
            }
        }
        enableButton.setOnClickListener {
            Log.d(TAG, "Opening accessibility settings")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        usageButton.setOnClickListener {
            Log.d(TAG, "Opening usage access settings")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        guardianButton.setOnClickListener { showGuardianDialog() }
        startButton.setOnClickListener {
            Log.d(TAG, "Starting protection service")
            SentinelForegroundService.start(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun renderScreen() {
        when (screenIndex) {
            0 -> {
                titleText.text = "Your Behavioral Shield"
                bodyText.text = "SentinelX watches HOW you use your phone during payments — never WHAT you type or say."
                transparencyList.visibility = TextView.GONE
                toggleStepThreeViews(false)
                nextButton.visibility = Button.VISIBLE
                nextButton.text = "Next"
            }
            1 -> {
                titleText.text = "What We Collect"
                bodyText.text = "SentinelX only uses behavior signals to detect scam pressure in real time."
                transparencyList.visibility = TextView.VISIBLE
                transparencyList.text =
                    "✓ App names and timestamps\n" +
                    "✓ Time between actions\n" +
                    "✓ Acoustic stress patterns, not audio content\n" +
                    "✗ Message content — NEVER\n" +
                    "✗ Call audio — NEVER\n" +
                    "✗ Keystrokes — NEVER\n" +
                    "✗ Passwords — NEVER"
                toggleStepThreeViews(false)
                nextButton.visibility = Button.VISIBLE
                nextButton.text = "Next"
            }
            else -> {
                titleText.text = "Enable Protection"
                bodyText.text = "Enable permissions and optionally set a guardian contact."
                transparencyList.visibility = TextView.GONE
                toggleStepThreeViews(true)
                nextButton.visibility = Button.GONE
                updateStatus()
            }
        }
    }

    private fun toggleStepThreeViews(visible: Boolean) {
        val v = if (visible) TextView.VISIBLE else TextView.GONE
        enableButton.visibility = v
        usageButton.visibility = v
        guardianButton.visibility = v
        startButton.visibility = v
        statusText.visibility = v
    }

    private fun updateStatus() {
        val accessibilityOk = isAccessibilityEnabled()
        val usageOk = hasUsageStatsPermission()
        val overlayOk = Settings.canDrawOverlays(this)
        val ready = accessibilityOk && usageOk

        if (!overlayOk) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        statusText.text = "Accessibility: ${flag(accessibilityOk)}  Usage: ${flag(usageOk)}  Overlay: ${flag(overlayOk)}"
        startButton.isEnabled = ready
        Log.d(TAG, "Permission state accessibility=$accessibilityOk usage=$usageOk overlay=$overlayOk")
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabledServices.contains("$packageName/com.sentinelx.core.SentinelAccessibilityService", ignoreCase = true)
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun showGuardianDialog() {
        val phoneInput = EditText(this).apply { hint = "Guardian phone" }
        val nameInput = EditText(this).apply { hint = "Guardian name" }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(30, 20, 30, 0)
            addView(phoneInput)
            addView(nameInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Set Guardian")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_GUARDIAN_PHONE, phoneInput.text.toString().trim())
                    .putString(KEY_GUARDIAN_NAME, nameInput.text.toString().trim())
                    .apply()
                Log.d(TAG, "Guardian saved from onboarding")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun flag(ok: Boolean): String = if (ok) "✓" else "✗"

    companion object {
        private const val TAG = "SentinelX-Onboarding"
        private const val PREFS_NAME = "sentinelx_prefs"
        private const val KEY_GUARDIAN_PHONE = "guardian_phone"
        private const val KEY_GUARDIAN_NAME = "guardian_name"
    }
}
