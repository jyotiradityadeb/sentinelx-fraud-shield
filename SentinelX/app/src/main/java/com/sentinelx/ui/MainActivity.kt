package com.sentinelx.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.sentinelx.core.BackendConfig
import com.sentinelx.core.BlocklistManager
import com.sentinelx.core.EventFlusher
import com.sentinelx.core.GuardianManager
import com.sentinelx.core.PermissionHealth
import com.sentinelx.core.SentinelForegroundService
import com.sentinelx.data.SessionFeatures
import com.sentinelx.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var blocklistManager: BlocklistManager
    private lateinit var guardianManager: GuardianManager
    private val runtimePermsRequestCode = 1001

    private val scoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SCORE_RESULT) return
            val score = intent.getIntExtra("score", 0)
            val label = intent.getStringExtra("label") ?: "SAFE"
            val caller = intent.getIntExtra("callerPts", 0)
            val transition = intent.getIntExtra("transitionPts", 0)
            val confirm = intent.getIntExtra("confirmPts", 0)
            val behavioral = intent.getIntExtra("behavioralPts", 0)
            val voice = intent.getIntExtra("voicePts", 0)
            val network = intent.getIntExtra("networkPts", 0)
            updateSignalDisplay(caller, transition, confirm, behavioral, voice, network, score, label)
            appendEvent("${label.uppercase()} score $score/120")
        }
    }

    private val blocklistChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BlocklistManager.ACTION_BLOCKLIST_CHANGED) updateBlocklistCount()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        blocklistManager = BlocklistManager(this)
        guardianManager = GuardianManager(applicationContext)

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            appendEvent("Opened accessibility settings")
        }

        binding.btnOpenUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            appendEvent("Opened usage access settings")
        }

        binding.btnBackendUrl.setOnClickListener {
            showBackendUrlDialog()
        }

        binding.btnManageBlocklist.setOnClickListener {
            BlocklistActivity.start(this)
        }

        binding.btnSetGuardian.setOnClickListener {
            showGuardianDialog()
        }
        binding.btnInformGuardian.setOnClickListener {
            informGuardianNow()
        }

        binding.btnSafeMode.setOnClickListener {
            toggleSafeMode()
        }

        binding.btnDemoMode.setOnClickListener {
            toggleDemoMode()
        }

        binding.btnSimulateScam.setOnClickListener {
            binding.btnSimulateScam.isEnabled = false
            binding.btnSimulateScam.text = "Simulating..."
            updateSignalDisplay(
                callerPts = 22,
                transitionPts = 20,
                confirmPts = 20,
                behavioralPts = 14,
                voicePts = 18,
                networkPts = 12,
                total = 106,
                label = "HIGH_RISK",
            )
            InterventionManager.show(
                context = applicationContext,
                score = 106,
                label = "HIGH_RISK",
                llmUserPrompt = "High-risk demo triggered. This payment pattern may indicate a scam. Pause and verify.",
                signals = listOf(
                    "Unknown/repeated caller context",
                    "Fast call-to-payment transition",
                    "High confirmation pressure",
                ),
            )
            val features = SessionFeatures.fromDemo()
            EventFlusher(applicationContext).flushAndScore(features)
            appendEvent("Demo scam simulation sent")
            Handler(Looper.getMainLooper()).postDelayed({
                binding.btnSimulateScam.isEnabled = true
                binding.btnSimulateScam.text = "TRIGGER HIGH RISK"
            }, 5000)
        }

        SentinelForegroundService.start(this)
        Log.d("SentinelX", "Backend URL in use: ${BackendConfig.getBackendUrl(this)}")
        requestMissingRuntimePermissions()
        syncSafeModeButton()
        updateGuardianStatus()
        updatePermissionDots()
        updateBlocklistCount()
        appendEvent("SentinelX UI ready")
        appendEvent("Backend ${BackendConfig.getBackendUrl(this)}")
    }

    override fun onStart() {
        super.onStart()
        val scoreFilter = IntentFilter(ACTION_SCORE_RESULT)
        val blocklistFilter = IntentFilter(BlocklistManager.ACTION_BLOCKLIST_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scoreReceiver, scoreFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(blocklistChangedReceiver, blocklistFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(scoreReceiver, scoreFilter)
            @Suppress("DEPRECATION")
            registerReceiver(blocklistChangedReceiver, blocklistFilter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(scoreReceiver) }
        runCatching { unregisterReceiver(blocklistChangedReceiver) }
    }

    override fun onResume() {
        super.onResume()
        requestMissingRuntimePermissions()
        updatePermissionDots()
        updateGuardianStatus()
        syncSafeModeButton()  // also calls syncDemoModeButton()
        updateBlocklistCount()
        updateStats()
    }

    private fun updateStats() {
        val statsPrefs = getSharedPreferences("sentinelx_stats", Context.MODE_PRIVATE)
        val installPrefs = getSharedPreferences("sentinelx_prefs", Context.MODE_PRIVATE)

        // Record install day on first run
        if (!installPrefs.contains("install_day")) {
            installPrefs.edit().putLong("install_day", System.currentTimeMillis()).apply()
        }
        val installDay = installPrefs.getLong("install_day", System.currentTimeMillis())
        val daysProtected = ((System.currentTimeMillis() - installDay) / 86_400_000L).coerceAtLeast(1L)

        binding.statCallsAnalyzed.text = statsPrefs.getLong("total_calls_analyzed", 0L).toString()
        binding.statScamBlocked.text = statsPrefs.getLong("scam_attempts_blocked", 0L).toString()
        binding.statBlocklistSize.text = blocklistManager.getBlockedEntries().size.toString()
        binding.statDaysProtected.text = daysProtected.toString()
    }

    private fun updateBlocklistCount() {
        val count = blocklistManager.getBlockedEntries().size
        binding.tvBlocklistCount.text = "$count number${if (count == 1) "" else "s"} blocked"
    }

    private fun updateSignalDisplay(
        callerPts: Int,
        transitionPts: Int,
        confirmPts: Int,
        behavioralPts: Int,
        voicePts: Int,
        networkPts: Int,
        total: Int,
        label: String,
    ) {
        binding.sigCallerTrustVal.text = callerPts.toString()
        binding.sigTransitionVal.text = transitionPts.toString()
        binding.sigConfirmVal.text = confirmPts.toString()
        binding.sigBehavioralVal.text = behavioralPts.toString()
        binding.sigVoiceVal.text = voicePts.toString()
        binding.sigNetworkVal.text = networkPts.toString()

        val clamped = total.coerceIn(0, 120)
        binding.riskProgressBar.max = 120
        binding.riskProgressBar.progress = clamped
        binding.riskScoreDisplay.text = "$clamped / 120"
        binding.riskLabelText.text = label.uppercase()

        val riskColor = when {
            clamped >= 80 -> Color.parseColor("#EF4444")
            clamped >= 40 -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#4ADE80")
        }

        binding.riskScoreDisplay.setTextColor(riskColor)
        binding.riskLabelText.setTextColor(riskColor)

        if (clamped >= 80) {
            binding.statusPill.text = "● HIGH ALERT"
            binding.statusPill.setTextColor(Color.parseColor("#FCA5A5"))
            binding.statusPill.setBackgroundResource(com.sentinelx.R.drawable.bg_status_pill_red)
        } else if (clamped >= 40) {
            binding.statusPill.text = "● WATCH"
            binding.statusPill.setTextColor(Color.parseColor("#FCD34D"))
            binding.statusPill.setBackgroundResource(com.sentinelx.R.drawable.bg_status_pill_amber)
        } else {
            binding.statusPill.text = "● PROTECTED"
            binding.statusPill.setTextColor(Color.parseColor("#4ADE80"))
            binding.statusPill.setBackgroundResource(com.sentinelx.R.drawable.bg_status_pill_green)
        }
    }

    private fun updatePermissionDots() {
        setDot(binding.dotAccessibility, PermissionHealth.isAccessibilityEnabled(this))
        setDot(binding.dotUsage, PermissionHealth.hasUsageStatsPermission(this))
        setDot(binding.dotOverlay, PermissionHealth.canDrawOverlays(this))
        setDot(binding.dotBattery, PermissionHealth.isIgnoringBatteryOptimizations(this))
    }

    private fun setDot(view: View, on: Boolean) {
        view.setBackgroundResource(if (on) com.sentinelx.R.drawable.bg_dot_green else com.sentinelx.R.drawable.bg_dot_red)
    }

    private fun updateGuardianStatus() {
        val prefs = getSharedPreferences("sentinelx_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("guardian_name", "")?.trim().orEmpty()
        binding.guardianStatus.text = if (name.isBlank()) "Not configured" else name
    }

    private fun appendEvent(text: String) {
        val existing = binding.recentEventsLog.text?.toString().orEmpty()
            .lines()
            .filter { it.isNotBlank() && it != "No events yet" }
            .take(5)
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $text"
        binding.recentEventsLog.text = (listOf(line) + existing).take(6).joinToString("\n")
    }

    private fun showGuardianDialog() {
        val prefs = getSharedPreferences("sentinelx_prefs", Context.MODE_PRIVATE)
        val nameInput = EditText(this).apply {
            hint = "Guardian name"
            setText(prefs.getString("guardian_name", ""))
        }
        val phoneInput = EditText(this).apply {
            hint = "Guardian phone"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(prefs.getString("guardian_phone", ""))
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
            addView(nameInput)
            addView(phoneInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Set Guardian")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("guardian_name", nameInput.text.toString().trim())
                    .putString("guardian_phone", phoneInput.text.toString().trim())
                    .apply()
                updateGuardianStatus()
                appendEvent("Guardian details saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBackendUrlDialog() {
        val input = EditText(this).apply {
            hint = "http://127.0.0.1:8000"
            setText(BackendConfig.getBackendUrl(this@MainActivity))
        }

        AlertDialog.Builder(this)
            .setTitle("Set Backend URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                if (!(raw.startsWith("http://") || raw.startsWith("https://"))) {
                    Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BackendConfig.setBackendUrl(this, raw)
                appendEvent("Backend URL updated")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleSafeMode() {
        val prefs = getSharedPreferences("sentinelx_prefs", Context.MODE_PRIVATE)
        val next = !prefs.getBoolean("safe_mode_active", false)
        prefs.edit().putBoolean("safe_mode_active", next).apply()
        syncSafeModeButton()
        appendEvent("Safe mode ${if (next) "enabled" else "disabled"}")
    }

    private fun syncSafeModeButton() {
        val on = getSharedPreferences("sentinelx_prefs", Context.MODE_PRIVATE)
            .getBoolean("safe_mode_active", false)
        if (on) {
            binding.btnSafeMode.text = "Safe Payment Mode: ON"
            binding.btnSafeMode.setBackgroundResource(com.sentinelx.R.drawable.bg_button_dark_toggle_on)
        } else {
            binding.btnSafeMode.text = "Safe Payment Mode: OFF"
            binding.btnSafeMode.setBackgroundResource(com.sentinelx.R.drawable.bg_button_dark_toggle_off)
        }
        if (binding.btnSimulateScam.text.isBlank() || binding.btnSimulateScam.text.contains("SIMULATE", ignoreCase = true)) {
            binding.btnSimulateScam.text = "TRIGGER HIGH RISK"
        }
        syncDemoModeButton()
    }

    private fun toggleDemoMode() {
        val prefs = getSharedPreferences("sentinelx_prefs", Context.MODE_PRIVATE)
        val next = !prefs.getBoolean("demo_mode_enabled", false)
        prefs.edit().putBoolean("demo_mode_enabled", next).apply()
        syncDemoModeButton()
        val msg = if (next) "Demo Mode ON — backend calls intercepted with fake responses" else "Demo Mode OFF"
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        appendEvent("Demo mode ${if (next) "enabled" else "disabled"}")
    }

    private fun syncDemoModeButton() {
        val on = getSharedPreferences("sentinelx_prefs", Context.MODE_PRIVATE)
            .getBoolean("demo_mode_enabled", false)
        binding.btnDemoMode.text = if (on) "Demo Mode: ON" else "Demo Mode: OFF"
        binding.btnDemoMode.setBackgroundResource(
            if (on) com.sentinelx.R.drawable.bg_button_dark_toggle_on
            else com.sentinelx.R.drawable.bg_button_outline_gray,
        )
    }

    private fun requestMissingRuntimePermissions() {
        val required = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
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

    companion object {
        private const val ACTION_SCORE_RESULT = "com.sentinelx.SCORE_RESULT"
    }

    private fun informGuardianNow() {
        if (!guardianManager.hasGuardian()) {
            Toast.makeText(this, "Set guardian first", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), runtimePermsRequestCode)
            Toast.makeText(this, "Grant SMS permission and tap again", Toast.LENGTH_SHORT).show()
            return
        }

        val sent = guardianManager.sendGuardianSms(
            score = 95,
            llmSummary = "Manual emergency alert from SentinelX. Please call immediately and verify current payment activity.",
            threatType = "MANUAL_ALERT",
        )
        if (sent) {
            appendEvent("Guardian SMS sent")
            Snackbar.make(binding.root, "Guardian informed by SMS", Snackbar.LENGTH_SHORT).show()
        } else {
            appendEvent("Guardian SMS failed")
            Snackbar.make(binding.root, "Could not send guardian SMS", Snackbar.LENGTH_SHORT).show()
        }
    }
}
