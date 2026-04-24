package com.sentinelx.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.RingtoneManager
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
import java.lang.ref.WeakReference

object InterventionManager {
    private const val CHANNEL_ID = "sentinelx_alerts"
    private const val BLOCKLIST_CHANNEL_ID = "sentinelx_blocklist_alerts"
    private const val NOTIFICATION_ID = 2201
    private const val BLOCKLIST_NOTIFICATION_ID = 2202
    private const val OVERLAY_AUTO_DISMISS_MS = 60_000L
    private const val GUARDIAN_WINDOW_MS = 120_000L

    @Volatile
    private var currentOverlayRef: WeakReference<View>? = null

    private val currentOverlay: View?
        get() = currentOverlayRef?.get()

    @Volatile
    private var currentWindowManager: WindowManager? = null

    private val handler = Handler(Looper.getMainLooper())

    fun show(context: Context, score: Int, label: String, llmUserPrompt: String, signals: List<String> = emptyList()) {
        when {
            score >= 80 || label.uppercase() == "HIGH_RISK" -> showTier3(context, score, llmUserPrompt, signals)
            score >= 40 || label.uppercase() == "SUSPICIOUS" -> showTier2(context, score, llmUserPrompt)
            else -> Log.d("SentinelX", "No intervention score=$score")
        }
    }

    fun dismissCurrentOverlay() {
        handler.post {
            val overlay = currentOverlayRef?.get() ?: return@post
            val wm = currentWindowManager ?: return@post
            runCatching { wm.removeViewImmediate(overlay) }
            currentOverlayRef?.clear()
            currentOverlayRef = null
            currentWindowManager = null
        }
    }

    private fun showTier1(context: Context, score: Int) {
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
            AlertActivity.start(
                context = context.applicationContext,
                score = score.coerceAtLeast(40),
                label = "SUSPICIOUS",
                prompt = prompt,
                signals = "-",
            )
            Log.d("SentinelX", "Tier2 fallback to AlertActivity (overlay permission missing)")
            return
        }
        showOverlay(context, score, prompt, emptyList(), critical = false)
        Log.d("SentinelX", "Tier2 intervention shown: overlay dialog")
    }

    private fun showTier3(context: Context, score: Int, prompt: String, signals: List<String>) {
        AlertActivity.start(
            context = context.applicationContext,
            score = score.coerceAtLeast(80),
            label = "HIGH_RISK",
            prompt = prompt,
            signals = if (signals.isEmpty()) "-" else signals.joinToString(" | "),
        )
        vibrate(context, longArrayOf(0L, 300L, 100L, 300L, 100L, 300L))
        Log.d("SentinelX", "Tier3 intervention shown: AlertActivity full-screen")
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
        overlay.findViewById<TextView>(R.id.overlayRiskTitle).text = if (critical) "PAYMENT BLOCKED" else "PAYMENT WARNING"
        overlay.findViewById<TextView>(R.id.overlayRiskScore).text = "$score / 120  ·  ${if (critical) "HIGH RISK" else "SUSPICIOUS"}"
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
            Log.d("SentinelX", "User override - launching recovery guide")
            dismissCurrentOverlay()
            RecoveryGuideActivity.start(appContext)
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
            // FLAG_NOT_FOCUSABLE removed so touch events (Cancel button) work correctly
            // FLAG_TURN_SCREEN_ON and FLAG_SHOW_WHEN_LOCKED ensure alert wakes the screen
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching {
            wm.addView(overlay, params)
            currentOverlayRef = WeakReference(overlay)
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

    fun showBlocklistAlert(context: Context, number: String, displayName: String = number) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(NotificationManager::class.java)
        ensureBlocklistChannel(nm)

        val blockIntent = Intent("com.sentinelx.BLOCK_AND_REPORT").apply {
            setPackage(appContext.packageName)
            putExtra("number", number)
        }
        val safeIntent = Intent("com.sentinelx.MARK_SAFE").apply {
            setPackage(appContext.packageName)
            putExtra("number", number)
        }
        val blockPi = PendingIntent.getBroadcast(
            appContext, 3001, blockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val safePi = PendingIntent.getBroadcast(
            appContext, 3002, safeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreenIntent = Intent(appContext, AlertActivity::class.java).apply {
            putExtra("score", 95)
            putExtra("label", "HIGH_RISK")
            putExtra("prompt", "BLOCKED NUMBER $displayName is calling. Do NOT answer.")
            putExtra("signals", "Personal blocklist match")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPi = PendingIntent.getActivity(
            appContext, 3003, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, BLOCKLIST_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("SCAMMER CALLING")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "Blocked number $displayName is calling right now.\n\n" +
                            "Do NOT answer. This number is on your personal blocklist.",
                    ),
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Block & Report", blockPi)
            .addAction(android.R.drawable.ic_menu_info_details, "It's Safe", safePi)
            .setFullScreenIntent(fullScreenPi, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0L, 500L, 200L, 500L))
            .setAutoCancel(false)
            .build()
        nm.notify(BLOCKLIST_NOTIFICATION_ID, notification)
        vibrate(appContext, longArrayOf(0L, 500L, 200L, 500L))
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

    private fun ensureBlocklistChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val channel = NotificationChannel(
            BLOCKLIST_CHANNEL_ID,
            "SentinelX Scammer Alerts",
            NotificationManager.IMPORTANCE_MAX,
        ).apply {
            description = "Alerts when a blocked scammer number calls"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 500L, 200L, 500L)
            setSound(alarmUri, null)
            enableLights(true)
            lightColor = Color.RED
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
