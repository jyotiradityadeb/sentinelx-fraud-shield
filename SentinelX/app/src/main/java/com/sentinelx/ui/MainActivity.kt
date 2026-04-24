package com.sentinelx.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sentinelx.R
import com.sentinelx.core.BackendConfig
import com.sentinelx.core.PermissionHealth
import com.sentinelx.core.ScammerNumberStore
import com.sentinelx.core.SentinelForegroundService

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var openAccessibilityButton: Button
    private lateinit var openUsageButton: Button
    private lateinit var openBatteryButton: Button
    private lateinit var backendUrlInput: EditText
    private lateinit var saveBackendUrlButton: Button
    private lateinit var scammerNumbersInput: EditText
    private lateinit var saveScammerNumbersButton: Button
    private lateinit var scammerNumberStore: ScammerNumberStore
    private val runtimePermsRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        openAccessibilityButton = findViewById(R.id.openAccessibilityButton)
        openUsageButton = findViewById(R.id.openUsageButton)
        openBatteryButton = findViewById(R.id.openBatteryButton)
        backendUrlInput = findViewById(R.id.backendUrlInput)
        saveBackendUrlButton = findViewById(R.id.saveBackendUrlButton)
        scammerNumbersInput = findViewById(R.id.scammerNumbersInput)
        saveScammerNumbersButton = findViewById(R.id.saveScammerNumbersButton)
        scammerNumberStore = ScammerNumberStore(this)
        backendUrlInput.setText(BackendConfig.getBackendUrl(this))
        scammerNumbersInput.setText(scammerNumberStore.getAsDisplayText())

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
        saveScammerNumbersButton.setOnClickListener {
            val raw = scammerNumbersInput.text?.toString().orEmpty()
            scammerNumberStore.setFromRawInput(raw)
            scammerNumbersInput.setText(scammerNumberStore.getAsDisplayText())
            Toast.makeText(this, "Scammer numbers saved", Toast.LENGTH_SHORT).show()
            renderStatus()
        }

        SentinelForegroundService.start(this)
        requestMissingRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        requestMissingRuntimePermissions()
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
                "Scammer list: ${scammerNumberStore.count()} saved\n" +
                "Backend: $backend"
    }

    private fun requestMissingRuntimePermissions() {
        val required = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            required.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), runtimePermsRequestCode)
        }
    }

    private fun flag(value: Boolean): String = if (value) "ON" else "OFF"
}
