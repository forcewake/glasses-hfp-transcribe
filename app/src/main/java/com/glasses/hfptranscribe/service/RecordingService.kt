package com.glasses.hfptranscribe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.glasses.hfptranscribe.DebugWav
import com.glasses.hfptranscribe.GlassesApp
import com.glasses.hfptranscribe.MainActivity
import com.glasses.hfptranscribe.R
import com.glasses.hfptranscribe.RecordPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val runtime = (application as GlassesApp).graph.runtime
        startAsForeground(runtime.state.value.statusText.ifBlank { "Preparing microphone" })
        acquireWakeLock()
        val app = application as GlassesApp
        when (intent?.action) {
            ACTION_STOP -> runtime.stop()
            else -> if (!DebugWav.startFromService(app, intent, ::updateNotification)) {
                runtime.start(::updateNotification)
            }
        }
        observe()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as GlassesApp).graph.runtime.stop()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        if (observing) return
        observing = true
        val runtime = (application as GlassesApp).graph.runtime
        scope.launch {
            var active = false
            runtime.state.collect { state ->
                if (state.phase == RecordPhase.PREPARING || state.phase == RecordPhase.RECORDING) {
                    active = true
                }
                val text = when (state.phase) {
                    RecordPhase.RECORDING -> "Recording · ${state.inputLabel}"
                    RecordPhase.PREPARING -> state.statusText.ifBlank { "Preparing microphone" }
                    RecordPhase.ERROR -> state.error ?: "Recording stopped"
                    RecordPhase.IDLE -> state.statusText.ifBlank { "Idle" }
                }
                updateNotification(text)
                if (active && state.phase != RecordPhase.PREPARING && state.phase != RecordPhase.RECORDING) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Glasses Transcript")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_mic, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shown the whole time the microphone is recording"
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glasses:record").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.glasses.hfptranscribe.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 41
    }
}
