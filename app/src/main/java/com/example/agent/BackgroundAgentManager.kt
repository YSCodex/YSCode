// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.linux.ShellService
import com.example.tools.ToolContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class BackgroundJobStatus {
    RUNNING, COMPLETED, FAILED, CANCELLED
}

data class BackgroundAgentJob(
    val id: String,
    val sessionId: String,
    val projectName: String,
    val projectPath: String,
    val role: String, // "explorer", "coder", "reviewer", "general"
    val prompt: String,
    val status: BackgroundJobStatus = BackgroundJobStatus.RUNNING,
    val stepNote: String = "Starting...",
    val output: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

class BackgroundAgentManager(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeCoroutines = mutableMapOf<String, Job>()

    private val _jobs = MutableStateFlow<List<BackgroundAgentJob>>(emptyList())
    val jobs: StateFlow<List<BackgroundAgentJob>> = _jobs.asStateFlow()

    fun launchBackgroundAgent(
        sessionId: String,
        projectDir: File,
        role: String,
        prompt: String,
        useWakeLock: Boolean,
        runner: suspend (onProgress: (String) -> Unit) -> String
    ): BackgroundAgentJob {
        val jobId = "bg_" + UUID.randomUUID().toString().take(8)
        val initial = BackgroundAgentJob(
            id = jobId,
            sessionId = sessionId,
            projectName = projectDir.name,
            projectPath = projectDir.canonicalPath,
            role = role,
            prompt = prompt
        )
        _jobs.value = listOf(initial) + _jobs.value

        if (useWakeLock) {
            ShellService.start(context, "Background $role agent: ${prompt.take(40)}", true)
        }

        val coroutine = scope.launch {
            try {
                val result = runner { progress ->
                    updateJob(jobId) { it.copy(stepNote = progress) }
                }
                val isErr = result.startsWith("Subagent error:") || result.startsWith("Error:")
                val finalStatus = if (isErr) BackgroundJobStatus.FAILED else BackgroundJobStatus.COMPLETED
                updateJob(jobId) {
                    it.copy(
                        status = finalStatus,
                        stepNote = if (isErr) "Failed" else "Completed",
                        output = result,
                        finishedAt = System.currentTimeMillis()
                    )
                }
                postCompletionNotification(
                    title = if (isErr) "YSCode $role agent failed" else "YSCode $role agent finished",
                    body = result.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: prompt.take(80),
                    jobId = jobId
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                updateJob(jobId) {
                    it.copy(
                        status = BackgroundJobStatus.CANCELLED,
                        stepNote = "Cancelled",
                        finishedAt = System.currentTimeMillis()
                    )
                }
            } catch (t: Throwable) {
                val msg = t.message ?: "Background agent failed"
                updateJob(jobId) {
                    it.copy(
                        status = BackgroundJobStatus.FAILED,
                        stepNote = "Error",
                        output = msg,
                        finishedAt = System.currentTimeMillis()
                    )
                }
                postCompletionNotification("YSCode $role agent failed", msg.take(120), jobId)
            } finally {
                activeCoroutines.remove(jobId)
                if (_jobs.value.none { it.status == BackgroundJobStatus.RUNNING }) {
                    ShellService.stop(context)
                }
            }
        }
        activeCoroutines[jobId] = coroutine
        return initial
    }

    fun cancelJob(jobId: String) {
        activeCoroutines.remove(jobId)?.cancel()
        updateJob(jobId) {
            if (it.status == BackgroundJobStatus.RUNNING) {
                it.copy(
                    status = BackgroundJobStatus.CANCELLED,
                    stepNote = "Cancelled",
                    finishedAt = System.currentTimeMillis()
                )
            } else it
        }
    }

    fun cancelAll() {
        activeCoroutines.keys.toList().forEach { cancelJob(it) }
    }

    fun dismissJob(jobId: String) {
        cancelJob(jobId)
        _jobs.value = _jobs.value.filterNot { it.id == jobId }
    }

    fun clearFinished() {
        _jobs.value = _jobs.value.filter { it.status == BackgroundJobStatus.RUNNING }
    }

    private fun updateJob(jobId: String, transform: (BackgroundAgentJob) -> BackgroundAgentJob) {
        _jobs.value = _jobs.value.map { if (it.id == jobId) transform(it) else it }
    }

    private fun postCompletionNotification(title: String, body: String, jobId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Background Agent Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when background coding, explorer, or reviewer agents finish."
            }
            nm.createNotificationChannel(ch)
        }

        val openIntent = PendingIntent.getActivity(
            context,
            jobId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        runCatching { nm.notify(jobId.hashCode(), notification) }
    }

    companion object {
        private const val ALERT_CHANNEL_ID = "yscode_agent_alerts"
    }
}
