// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Webhook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agent.BackgroundJobStatus
import com.example.agent.HookDefinition
import com.example.ui.components.ActivitySummaryCard
import com.example.ui.components.BackgroundAgentsDialog
import com.example.ui.components.HooksManagerDialog
import com.example.ui.components.TextPreviewDialog
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MoreTab(
    mainViewModel: MainViewModel,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val project by mainViewModel.activeProject.collectAsStateWithLifecycle()
    val activity by mainViewModel.agentActivity.collectAsStateWithLifecycle()
    val todos by mainViewModel.todoItems.collectAsStateWithLifecycle()
    val checkpoints by mainViewModel.checkpoints.collectAsStateWithLifecycle()
    val bgJobs by mainViewModel.backgroundJobs.collectAsStateWithLifecycle()
    val bgAgentJobs by mainViewModel.backgroundAgentJobs.collectAsStateWithLifecycle()
    val activeJobCount by mainViewModel.activeJobCount.collectAsStateWithLifecycle()
    val selfTests by mainViewModel.selfTests.collectAsStateWithLifecycle()
    val diagnostics by mainViewModel.linuxDiagnostics.collectAsStateWithLifecycle()

    var showPhantomGuide by remember { mutableStateOf(false) }
    var showBgAgentsDialog by remember { mutableStateOf(false) }
    var showProjectHooksDialog by remember { mutableStateOf(false) }
    var projectHooks by remember { mutableStateOf<List<HookDefinition>>(emptyList()) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewContent by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.bg),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ActivitySummaryCard(
                activity = activity,
                todos = todos,
                activeJobsCount = activeJobCount + bgAgentJobs.count { it.status == BackgroundJobStatus.RUNNING },
                onKillAll = mainViewModel::killAllProcesses
            )
        }

        // Quick Workspace Navigation
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tools & Extensions", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onOpenSkills, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Extension, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Skills", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = onOpenMcp, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Hub, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("MCP", maxLines = 1, softWrap = false)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onOpenProviders, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Cloud, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Providers", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Settings", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // Background & Multi-Agent Tasks
        item {
            val runningCount = bgAgentJobs.count { it.status == BackgroundJobStatus.RUNNING }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            Text("Background & Multi-Agent Tasks", style = MaterialTheme.typography.titleSmall, color = colors.text)
                        }
                        if (runningCount > 0) {
                            Text("$runningCount active", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                        }
                    }
                    Text(
                        "Run explorer, coder, or reviewer subagents asynchronously with completion notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim
                    )
                    Button(
                        onClick = { showBgAgentsDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.fillMaxWidth().testTag("more_open_bg_agents_btn")
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (bgAgentJobs.isEmpty()) "Launch Background Agent" else "Manage Agents (${bgAgentJobs.size})",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    bgAgentJobs.take(3).forEach { job ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.surface2,
                            border = BorderStroke(1.dp, colors.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        "[${job.role}] ${job.prompt}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${job.status.name} • ${job.stepNote}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (job.status) {
                                            BackgroundJobStatus.RUNNING -> colors.accent
                                            BackgroundJobStatus.COMPLETED -> colors.success
                                            BackgroundJobStatus.FAILED -> colors.danger
                                            BackgroundJobStatus.CANCELLED -> colors.textDim
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (job.status == BackgroundJobStatus.RUNNING) {
                                    IconButton(
                                        onClick = { mainViewModel.cancelBackgroundAgent(job.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Outlined.Stop, "Cancel background agent", tint = colors.danger, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Project Actions & Engineering Tools
        project?.let { proj ->
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Project: ${proj.name}", style = MaterialTheme.typography.titleSmall, color = colors.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { mainViewModel.initProjectMemory() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Description, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Init YSCODE.md", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { mainViewModel.shareProjectZip(context, proj) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Archive, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export ZIP", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        projectHooks = mainViewModel.loadProjectHooks()
                                        showProjectHooksDialog = true
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f).testTag("more_hooks_btn")
                            ) {
                                Icon(Icons.Outlined.Webhook, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Project Hooks", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    mainViewModel.buildRepoMapPreview { map ->
                                        previewTitle = "Repo Map (${proj.name})"
                                        previewContent = map
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f).testTag("more_repomap_btn")
                            ) {
                                Icon(Icons.Outlined.AccountTree, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Repo Map", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    mainViewModel.runProjectAutoTestNow { output ->
                                        previewTitle = "Auto-Test Result"
                                        previewContent = output
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f).testTag("more_autotest_btn")
                            ) {
                                Icon(Icons.Outlined.BugReport, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Run Auto-Tests", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    previewTitle = "Memory (YSCODE.md)"
                                    previewContent = mainViewModel.readProjectMemoryText()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f).testTag("more_view_memory_btn")
                            ) {
                                Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("View Memory", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Background Shell Jobs
        if (bgJobs.isNotEmpty()) {
            item {
                Text("Background Shell Jobs", style = MaterialTheme.typography.titleSmall, color = colors.text)
            }
            items(bgJobs, key = { it.id }) { job ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(job.command, fontFamily = JetBrainsMonoFontFamily, style = MaterialTheme.typography.bodySmall, color = colors.text)
                            Text(
                                text = if (job.isRunning) "${job.id} • RUNNING" else "${job.id} • Exited (${job.exitCode})",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (job.isRunning) colors.success else colors.textDim
                            )
                        }
                        if (job.isRunning) {
                            IconButton(onClick = { mainViewModel.container.prootRunner.killJob(job.id) }) {
                                Icon(Icons.Outlined.Stop, "Kill job", tint = colors.danger)
                            }
                        }
                    }
                }
            }
        }

        // Checkpoints / Undo Timeline
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Checkpoints & Undo", style = MaterialTheme.typography.titleSmall, color = colors.text)
                        if (checkpoints.isNotEmpty()) {
                            TextButton(onClick = { mainViewModel.undoLastCheckpoint() }) {
                                Icon(Icons.Outlined.History, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Undo last")
                            }
                        }
                    }
                    if (checkpoints.isEmpty()) {
                        Text("Automatic file snapshots are created before each agent turn.", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                    } else {
                        checkpoints.take(6).forEach { chk ->
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(chk.createdAt))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(chk.label, style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 1)
                                    Text(timeStr, style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                                }
                                TextButton(onClick = { mainViewModel.restoreCheckpoint(chk) }) {
                                    Text("Restore")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Linux Environment Self-Test & Phantom Process Guide
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Terminal, null, tint = colors.accent)
                        Text("Embedded Linux & Self-Test", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    }
                    diagnostics?.let { diag ->
                        Text(
                            text = "Mode: ${diag.executionMode}\nABIs: ${diag.abi}\nProjects disk: ${diag.projectsBytes / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textDim
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { mainViewModel.runLinuxSelfTest() },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f).testTag("linux_selftest_btn")
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Run Self-Test", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { showPhantomGuide = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.BatteryAlert, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Android 12+ Guide", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    if (selfTests.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface2, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selfTests.forEach { item ->
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        imageVector = if (item.passed) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = if (item.passed) colors.success else colors.warn,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "${item.command} → ${item.output.lines().firstOrNull() ?: ""}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = colors.text
                                        )
                                        if (!item.passed && item.note.isNotBlank()) {
                                            Text(item.note, style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPhantomGuide) {
        AlertDialog(
            onDismissRequest = { showPhantomGuide = false },
            title = { Text("Android 12+ Phantom Process Killer") },
            text = {
                Text(
                    """
                    On Android 12+, the OS may kill background child processes (like node/python servers inside PRoot) if total child processes exceed 32 or high CPU is used in background.

                    How YSCode mitigates this:
                    1. Foreground Service + WakeLock keeps active agent runs alive.
                    2. On Android 14+, Developer Options -> "Disable child process restrictions" prevents phantom kills.
                    3. Battery Settings -> Set YSCode to "Unrestricted" for long builds.
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showPhantomGuide = false }) { Text("Got it") }
            }
        )
    }

    if (showBgAgentsDialog) {
        BackgroundAgentsDialog(
            jobs = bgAgentJobs,
            onLaunch = { role, prompt -> mainViewModel.launchBackgroundAgent(role, prompt) },
            onCancel = { mainViewModel.cancelBackgroundAgent(it) },
            onInjectIntoChat = { mainViewModel.injectBackgroundResult(it) },
            onDismissJob = { mainViewModel.dismissBackgroundAgent(it) },
            onDismiss = { showBgAgentsDialog = false }
        )
    }

    if (showProjectHooksDialog) {
        HooksManagerDialog(
            initialHooks = projectHooks,
            title = "Project Lifecycle Hooks (.yscode/hooks.json)",
            onSave = { updated -> mainViewModel.saveProjectHooks(updated) },
            onDismiss = { showProjectHooksDialog = false }
        )
    }

    previewTitle?.let { title ->
        TextPreviewDialog(
            title = title,
            content = previewContent,
            onDismiss = { previewTitle = null }
        )
    }
}
