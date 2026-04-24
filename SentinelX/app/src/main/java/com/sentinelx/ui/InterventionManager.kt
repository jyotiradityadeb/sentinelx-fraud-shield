package com.sentinelx.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.sentinelx.R

object InterventionManager {
    private const val CHANNEL_ID = "sentinelx_alerts"
    private const val NOTIFICATION_ID = 2201
    private const val OVERLAY_AUTO_DISMISS_MS = 60_000L
    private const val GUARDIAN_WINDOW_MS = 120_000L

    @Volatile
    private var currentOverlay: View? = null

    @Volatile
    private var currentWindowManager: WindowManager? = null

    private val handler = Handler(Looper.getMainLooper())

    fun show(
        context: Context,
        score: Int,
        label: String,
        llmUserPrompt: String,
        signals: List<String> = emptyList(),
    ) {
        val normalizedLabel = label.uppercase()
        if (normalizedLabel == "HIGH_RISK" || normalizedLabel == "SUSPICIOUS" || score >= 40) {
            AlertActivity.start(
                context = context.applicationContext,
                score = score.coerceAtLeast(40),
                label = if (normalizedLabel == "HIGH_RISK" || score >= 80) "HIGH_RISK" else "SUSPICIOUS",
                prompt = llmUserPrompt,
                signals = if (signals.isEmpty()) "-" else signals.joinToString(" | "),
            )
        }
        when {
            score >= 95 -> showTier3(context, score, llmUserPrompt, signals)
            score >= 80 || label == "HIGH_RISK" -> showTier2(context, score, llmUserPrompt)
            score >= 40 || label == "SUSPICIOUS" -> showTier1(context, score)
            else -> Log.d("SentinelX", "No intervention required. score=$score label=$label")
        }
    }

    fun dismissCurrentOverlay() {
        handler.post {
            val overlay = currentOverlay ?: return@post
            val wm = currentWindowManager ?: return@post
            runCatching { wm.removeViewImmediate(overlay) }
            currentOverlay = null
            currentWindowManager = null
        }
    }

    private fun showTier1(context: Context, score: Int) {
        AlertActivity.start(
            context = context.applicationContext,
            score = score.coerceAtLeast(40),
            label = "SUSPICIOUS",
            prompt = "Unusual payment behavior detected. Verify caller and recipient before paying.",
            signals = "-",
        )
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("SentinelX: Unusual Activity Detected")
            .setContentText("Score: $score/120. Proceed with caution.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        Log.d("SentinelX", "Tier1 intervention shown: notification")
    }

    private fun showTier2(context: Context, score: Int, prompt: String) {
        if (!Settings.canDrawOverlays(context)) {
            showTier1(context, score)
            Log.d("SentinelX", "Tier2 fallback to notification (overlay permission missing)")
            return
        }
        showOverlay(context, score, prompt, emptyList(), critical = false)
        vibrate(context, longArrayOf(0L, 200L))
        Log.d("SentinelX", "Tier2 intervention shown: overlay dialog")
    }

    private fun showTier3(context: Context, score: Int, prompt: String, signals: List<String>) {
        if (!Settings.canDrawOverlays(context)) {
            showTier1(context, score)
            Log.d("SentinelX", "Tier3 fallback to notification (overlay permission missing)")
            return
        }
        showOverlay(context, score, prompt, signals, critical = true)
        vibrate(context, longArrayOf(0L, 300L, 100L, 300L, 100L, 300L))
        Log.d("SentinelX", "Tier3 intervention shown: full-screen overlay")
    }

    private fun showOverlay(
        context: Context,
        score: Int,
        prompt: String,
        signals: List<String>,
        critical: Boolean,
    ) {
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(WindowManager::class.java)
        dismissCurrentOverlay()

        val overlay = LayoutInflater.from(appContext).inflate(R.layout.overlay_high_risk, null, false)
        overlay.findViewById<TextView>(R.id.overlayRiskTitle).text = if (critical) "HIGH RISK" else "SentinelX Alert"
        overlay.findViewById<TextView>(R.id.overlayRiskScore).text = "Score: $score/120"
        overlay.findViewById<TextView>(R.id.overlayRiskMessage).text = prompt
        overlay.findViewById<TextView>(R.id.overlaySignalSummary).text =
            if (signals.isEmpty()) "Signal summary unavailable." else signals.joinToString(" | ")
        val countdownText = overlay.findViewById<TextView>(R.id.overlayCountdown)
        startCountdown(countdownText)

        overlay.findViewById<View>(R.id.btnCancelPayment).setOnClickListener {
            vibrate(appContext, longArrayOf(0L, 200L))
            dismissCurrentOverlay()
        }
        overlay.findViewById<View>(R.id.btnOverrideRisk).setOnClickListener {
            Log.d("SentinelX", "User override selected for risk overlay")
            dismissCurrentOverlay()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching {
            wm.addView(overlay, params)
            currentOverlay = overlay
            currentWindowManager = wm
            handler.postDelayed({ dismissCurrentOverlay() }, OVERLAY_AUTO_DISMISS_MS)
        }.onFailure {
            Log.w("SentinelX", "Overlay show failed: ${it.message}")
            AlertActivity.start(
                context = appContext,
                score = score,
                label = if (critical) "HIGH_RISK" else "SUSPICIOUS",
                prompt = prompt,
                signals = if (signals.isEmpty()) "-" else signals.joinToString(" | "),
            )
            showTier1(appContext, score)
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SentinelX Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "SentinelX payment risk alerts"
        }
        manager.createNotificationChannel(channel)
    }

    private fun vibrate(context: Context, pattern: LongArray) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun startCountdown(countdownView: TextView) {
        val startTs = System.currentTimeMillis()
        val ticker = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTs
                val remaining = (GUARDIAN_WINDOW_MS - elapsed).coerceAtLeast(0L)
                val sec = (remaining / 1000L).toInt()
                val mm = sec / 60
                val ss = sec % 60
                countdownView.text = "Guardian window: %02d:%02d".format(mm, ss)
                if (remaining > 0L && currentOverlay != null) {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.post(ticker)
    }
}
