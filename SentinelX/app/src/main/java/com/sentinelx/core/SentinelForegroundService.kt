package com.sentinelx.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sentinelx.ui.InterventionManager
import com.sentinelx.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SentinelForegroundService : Service() {
    private lateinit var callStateMonitor: CallStateMonitor
    private lateinit var appUsageAnalyzer: AppUsageAnalyzer
    private lateinit var voiceStressAnalyzer: VoiceStressAnalyzer
    private lateinit var eventFlusher: EventFlusher
    private lateinit var callerTrustClassifier: CallerTrustClassifier

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            2001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openA11yIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val openA11yPendingIntent = PendingIntent.getActivity(
            this,
            2002,
            openA11yIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val accessibilityState = if (PermissionHealth.isAccessibilityEnabled(this)) "ON" else "OFF"
        val contentText = if (accessibilityState == "ON") {
            "Protecting your payments in real time"
        } else {
            "Accessibility is OFF. Tap Enable to restore protection."
        }
        lastStatusText = contentText
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("SentinelX Active")
            .setContentText(lastStatusText)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "Enable Accessibility", openA11yPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        appUsageAnalyzer = AppUsageAnalyzer(applicationContext)
        voiceStressAnalyzer = VoiceStressAnalyzer()
        eventFlusher = EventFlusher(applicationContext)
        callerTrustClassifier = CallerTrustClassifier(applicationContext)
        callStateMonitor = CallStateMonitor(
            context = applicationContext,
            onCallEnd = { event ->
                Log.d("SentinelX", "Call ended ${event.number}, duration=${event.durationMs}")
                val stressEvent = voiceStressAnalyzer.stopAnalyzing()
                Log.d("SentinelX", "Voice session avg=${stressEvent.avgScore}, peak=${stressEvent.peakScore}")
            },
            onStateChanged = { state ->
                if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    voiceStressAnalyzer.startAnalyzing()
                }
                if (state == TelephonyManager.CALL_STATE_IDLE) {
                    voiceStressAnalyzer.stopAnalyzing()
                }
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    // On API 31+ the number is not delivered in the callback, poll call log
                    scope.launch {
                        delay(300L)
                        var number: String? = null
                        repeat(6) {
                            if (number.isNullOrBlank()) {
                                number = callStateMonitor.getRecentCallInfo(maxAgeSeconds = 5L)?.number
                                    ?: callStateMonitor.lastCallerNumber
                                if (number.isNullOrBlank()) delay(500L)
                            }
                        }
                        if (number.isNullOrBlank()) return@launch
                        val trustLabels = callerTrustClassifier.classify(number!!)
                        if (trustLabels.contains(CallerTrustClassifier.CallerTrust.SCAMMER_MARKED)) {
                            Log.d("SentinelX", "BLOCKED NUMBER calling: $number — alerting during ring")
                            InterventionManager.show(
                                context = applicationContext,
                                score = 95,
                                label = "HIGH_RISK",
                                llmUserPrompt = "⚠️ INCOMING CALL FROM BLOCKED NUMBER: $number\n\nThis number is on your scammer blocklist. Do NOT answer.",
                                signals = listOf("Number is in your personal blocklist", "Blocked caller alert"),
                            )
                            updateLiveStatus(applicationContext, "🚨 BLOCKED NUMBER CALLING: $number")
                        }
                    }
                }
            },
        )

        callStateMonitor.startMonitoring()
        eventFlusher.startFlushing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP_ACTION -> {
                stopSelf()
                return START_NOT_STICKY
            }
            START_ACTION -> {
                // already initialized in onCreate
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        callStateMonitor.stopMonitoring()
        eventFlusher.stopFlushing()
        voiceStressAnalyzer.stopAnalyzing()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SentinelX Protection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "SentinelX real-time payment protection"
            setSound(null, null)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val START_ACTION = "com.sentinelx.action.START"
        const val STOP_ACTION = "com.sentinelx.action.STOP"
        private const val CHANNEL_ID = "sentinelx_protection"
        private const val NOTIFICATION_ID = 101
        @Volatile
        private var lastStatusText: String = "Protecting your payments in real time"

        fun start(context: Context) {
            val intent = Intent(context, SentinelForegroundService::class.java).apply {
                action = START_ACTION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SentinelForegroundService::class.java).apply {
                action = STOP_ACTION
            }
            context.startService(intent)
        }

        fun updateLiveStatus(context: Context, status: String) {
            lastStatusText = status
            val appContext = context.applicationContext
            val openAppIntent = Intent(appContext, MainActivity::class.java)
            val openAppPendingIntent = PendingIntent.getActivity(
                appContext,
                2001,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val openA11yIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val openA11yPendingIntent = PendingIntent.getActivity(
                appContext,
                2002,
                openA11yIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("SentinelX Active")
                .setContentText(lastStatusText)
                .setContentIntent(openAppPendingIntent)
                .addAction(0, "Enable Accessibility", openA11yPendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .build()
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
