package com.sentinelx.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sentinelx.R
import com.sentinelx.core.BackendConfig
import com.sentinelx.core.PermissionHealth
import com.sentinelx.core.SentinelForegroundService

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var openAccessibilityButton: Button
    private lateinit var openUsageButton: Button
    private lateinit var openBatteryButton: Button
    private lateinit var backendUrlInput: EditText
    private lateinit var saveBackendUrlButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        openAccessibilityButton = findViewById(R.id.openAccessibilityButton)
        openUsageButton = findViewById(R.id.openUsageButton)
        openBatteryButton = findViewById(R.id.openBatteryButton)
        backendUrlInput = findViewById(R.id.backendUrlInput)
        saveBackendUrlButton = findViewById(R.id.saveBackendUrlButton)
        backendUrlInput.setText(BackendConfig.getBackendUrl(this))

        openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        openUsageButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        openBatteryButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        saveBackendUrlButton.setOnClickListener {
            val raw = backendUrlInput.text?.toString().orEmpty().trim()
            if (!(raw.startsWith("http://") || raw.startsWith("https://"))) {
                Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            BackendConfig.setBackendUrl(this, raw)
            backendUrlInput.setText(BackendConfig.getBackendUrl(this))
            Toast.makeText(this, "Backend URL saved", Toast.LENGTH_SHORT).show()
        }

        SentinelForegroundService.start(this)
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val a11y = PermissionHealth.isAccessibilityEnabled(this)
        val usage = PermissionHealth.hasUsageStatsPermission(this)
        val overlay = PermissionHealth.canDrawOverlays(this)
        val battery = PermissionHealth.isIgnoringBatteryOptimizations(this)
        val backend = BackendConfig.getBackendUrl(this)
        val readiness = if (a11y && usage) "Protection active" else "Action needed"
        statusText.text =
            "SentinelX status: $readiness\n" +
                "Accessibility: ${flag(a11y)}\n" +
                "Usage Access: ${flag(usage)}\n" +
                "Overlay: ${flag(overlay)}\n" +
                "Battery Optimized Off: ${flag(battery)}\n" +
                "Backend: $backend"
    }

    private fun flag(value: Boolean): String = if (value) "ON" else "OFF"
}
