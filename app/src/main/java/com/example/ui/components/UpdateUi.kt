// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.YSCodeApplication
import com.example.ui.theme.LocalYSCodeColors
import com.example.update.Telemetry
import com.example.update.UpdateManager
import com.example.util.CrashReporter
import kotlinx.coroutines.launch

/** "Check for updates" dialog: check, show notes, download with progress, verify, install. */
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val mgr = remember { UpdateManager(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateManager.CheckResult?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { state = mgr.check() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Updates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = state) {
                    null -> Text("Checking for updates...")
                    is UpdateManager.CheckResult.UpToDate -> Text("You have the latest version (${CrashReporter.versionName(ctx)}).")
                    is UpdateManager.CheckResult.NotReleased -> Text("No public release is available yet.")
                    is UpdateManager.CheckResult.Error -> Text(s.message)
                    is UpdateManager.CheckResult.Available -> {
                        Text("Version ${s.release.versionName} is available (you have ${CrashReporter.versionName(ctx)}).")
                        if (s.release.notes.isNotBlank()) Text(s.release.notes.take(1200), style = MaterialTheme.typography.bodySmall)
                        if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                message?.let { Text(it, color = LocalYSCodeColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val s = state
            if (s is UpdateManager.CheckResult.Available) {
                Button(enabled = progress < 0f || progress >= 1f, onClick = {
                    message = null
                    progress = 0f
                    scope.launch {
                        message = mgr.downloadAndInstall(s.release) { progress = it }
                        if (message != null) progress = -1f
                    }
                }) { Text("Download and install") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (state is UpdateManager.CheckResult.Available) TextButton(onClick = onDismiss) { Text("Later") }
        }
    )
}

/** Feedback / bug report to the developer (only sent when the user presses Send). */
@Composable
fun FeedbackDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val telemetry = remember { Telemetry(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("bug") }
    var text by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var withDiag by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send feedback") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("bug" to "Bug", "idea" to "Idea", "other" to "Other").forEach { (k, label) ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(4000) }, label = { Text("What happened / your idea") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6
                )
                OutlinedTextField(
                    value = contact, onValueChange = { contact = it.take(200) }, label = { Text("Contact (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = withDiag, onCheckedChange = { withDiag = it })
                    Text("Include diagnostic report (app/Android version, Linux status; no chats or code)", style = MaterialTheme.typography.bodySmall)
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (sent) LocalYSCodeColors.current.success else LocalYSCodeColors.current.danger) }
            }
        },
        confirmButton = {
            if (sent) TextButton(onClick = onDismiss) { Text("Close") }
            else Button(enabled = !sending && text.trim().length >= 5, onClick = {
                sending = true
                result = null
                scope.launch {
                    val diag = if (withDiag) {
                        runCatching { CrashReporter.buildReport(ctx, (ctx.applicationContext as YSCodeApplication).container.linuxEnv) }.getOrNull()
                    } else null
                    val err = telemetry.sendFeedback(kind, text.trim(), contact.trim(), diag)
                    sending = false
                    sent = err == null
                    result = err ?: "Thank you! Your feedback was sent."
                }
            }) { Text(if (sending) "Sending..." else "Send") }
        },
        dismissButton = { if (!sent) TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One-time question: share anonymous usage stats? Default stays OFF unless the user agrees. */
@Composable
fun StatsConsentDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val telemetry = remember { Telemetry(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { telemetry.consentAsked = true; onDone() },
        title = { Text("Help improve YSCode?") },
        text = {
            Text(
                "Share anonymous usage stats once a day: a random ID, app version, Android version and CPU type. " +
                    "Never your chats, code, files or API keys. You can change this any time in Settings."
            )
        },
        confirmButton = {
            Button(onClick = {
                telemetry.enabled = true
                telemetry.consentAsked = true
                scope.launch { telemetry.pingIfDue() }
                onDone()
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = { telemetry.enabled = false; telemetry.consentAsked = true; onDone() }) { Text("No thanks") } }
    )
}

/** Banner shown on Home when a newer version is known. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mgr = remember { UpdateManager(ctx.applicationContext) }
    var release by remember { mutableStateOf(mgr.cachedAvailable()) }
    var showDialog by remember { mutableStateOf(false) }
    val colors = LocalYSCodeColors.current
    val r = release ?: return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.accent),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Update ${r.versionName} is available", style = MaterialTheme.typography.titleSmall, color = colors.text, modifier = Modifier.weight(1f))
            TextButton(onClick = { mgr.dismiss(r); release = null }) { Text("Later") }
            Button(onClick = { showDialog = true }) { Text("Update") }
        }
    }
    if (showDialog) UpdateDialog { showDialog = false }
}
