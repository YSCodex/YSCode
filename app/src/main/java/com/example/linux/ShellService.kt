// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.YSCodeApplication

class ShellService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                (application as? YSCodeApplication)?.container?.let { container ->
                    container.prootRunner.killAll()
                    container.agentLoop.stop()
                }
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: "Agent / shell job running locally"
                val useWakeLock = intent?.getBooleanExtra(EXTRA_WAKELOCK, true) ?: true
                val notification = buildNotification(statusText)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                if (useWakeLock) acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YSCode::AgentJobWakeLock").apply {
            setReferenceCounted(false)
            acquire(20 * 60 * 1000L) // max 20 min safety cap
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YSCode Active Jobs",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when YSCode agent or background shell commands are running."
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ShellService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("YSCode Running")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop All", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "yscode_shell_service"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.example.linux.ACTION_START"
        const val ACTION_STOP_ALL = "com.example.linux.ACTION_STOP_ALL"
        const val ACTION_STOP_SERVICE = "com.example.linux.ACTION_STOP_SERVICE"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_WAKELOCK = "extra_wakelock"

        fun start(context: Context, status: String, useWakeLock: Boolean) {
            val intent = Intent(context, ShellService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_WAKELOCK, useWakeLock)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ShellService::class.java))
            }
        }
    }
}
