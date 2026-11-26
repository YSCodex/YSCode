// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.BackgroundAgentJob
import com.example.agent.BackgroundJobStatus
import com.example.agent.HookDefinition
import com.example.agent.HookEvent
import com.example.agent.HookPresets
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HooksManagerDialog(
    initialHooks: List<HookDefinition>,
    title: String = "Project Lifecycle Hooks",
    onSave: (List<HookDefinition>) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    var hooks by remember(initialHooks) { mutableStateOf(initialHooks) }
    var newName by remember { mutableStateOf("") }
    var newEvent by remember { mutableStateOf(HookEvent.PostToolUse) }
    var newMatcher by remember { mutableStateOf("write_file|edit_file|multi_edit") }
    var newCommand by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "PreToolUse hooks exit 2 to block a tool. PostToolUse runs auto-formatters/linters. Stop runs when a turn completes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textDim
                )

                Text("Quick Presets", style = MaterialTheme.typography.labelMedium, color = colors.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HookPresets.starterTemplates.forEach { preset ->
                        OutlinedButton(
                            onClick = {
                                if (hooks.none { it.name == preset.name }) {
                                    hooks = hooks + preset.copy(id = "hook_" + UUID.randomUUID().toString().take(8))
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(preset.name, fontSize = 11.sp)
                        }
                    }
                }

                if (hooks.isNotEmpty()) {
                    HorizontalDivider(color = colors.border)
                    hooks.forEach { h ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.surface2,
                            border = BorderStroke(1.dp, colors.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${h.event.name}: ${h.name}", style = MaterialTheme.typography.labelLarge, color = colors.text)
                                    Text("Tools: ${h.toolMatcher}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                                    Text(
                                        h.command,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 11.sp,
                                        color = colors.textDim,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = h.enabled,
                                    onCheckedChange = { on ->
                                        hooks = hooks.map { if (it.id == h.id) it.copy(enabled = on) else it }
                                    }
                                )
                                IconButton(onClick = { hooks = hooks.filterNot { it.id == h.id } }) {
                                    Icon(Icons.Outlined.Delete, "Delete hook", tint = colors.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.border)
                Text("Add Custom Hook", style = MaterialTheme.typography.labelMedium, color = colors.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HookEvent.entries.forEach { ev ->
                        FilterChip(
                            selected = newEvent == ev,
                            onClick = { newEvent = ev },
                            label = { Text(ev.name, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Hook name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newMatcher,
                    onValueChange = { newMatcher = it },
                    label = { Text("Tool matcher (e.g. bash, edit_file|write_file, *)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newCommand,
                    onValueChange = { newCommand = it },
                    label = { Text("Shell command (uses \$YSCODE_FILE / \$YSCODE_TOOL)") },
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        hooks = hooks + HookDefinition(
                            id = "hook_" + UUID.randomUUID().toString().take(8),
                            name = newName.trim().ifBlank { "Custom Hook" },
                            event = newEvent,
                            toolMatcher = newMatcher.trim().ifBlank { "*" },
                            command = newCommand.trim()
                        )
                        newName = ""
                        newCommand = ""
                    },
                    enabled = newCommand.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Hook to List")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(hooks); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                modifier = Modifier.testTag("hooks_save_btn")
            ) {
                Text("Save Hooks")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundAgentsDialog(
    jobs: List<BackgroundAgentJob>,
    onLaunch: (role: String, prompt: String) -> Unit,
    onCancel: (String) -> Unit,
    onInjectIntoChat: (BackgroundAgentJob) -> Unit,
    onDismissJob: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    var role by remember { mutableStateOf("explorer") }
    var prompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background & Multi-Agent Tasks") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Launch an async explorer, coder, or reviewer agent. You'll get a notification when done.", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("explorer", "coder", "reviewer").forEach { r ->
                        FilterChip(
                            selected = role == r,
                            onClick = { role = r },
                            label = { Text(r.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = { Text("Task prompt (e.g. Find all auth bugs or implement feature)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onLaunch(role, prompt)
                        prompt = ""
                    },
                    enabled = prompt.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Run in Background")
                }

                if (jobs.isNotEmpty()) {
                    HorizontalDivider(color = colors.border)
                    jobs.forEach { job ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.surface2,
                            border = BorderStroke(1.dp, colors.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("[${job.role}] ${job.status.name}", style = MaterialTheme.typography.labelLarge, color = when (job.status) {
                                        BackgroundJobStatus.RUNNING -> colors.accent
                                        BackgroundJobStatus.COMPLETED -> colors.success
                                        BackgroundJobStatus.FAILED -> colors.danger
                                        BackgroundJobStatus.CANCELLED -> colors.textDim
                                    })
                                    if (job.status == BackgroundJobStatus.RUNNING) {
                                        IconButton(onClick = { onCancel(job.id) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Outlined.Stop, "Cancel", tint = colors.danger, modifier = Modifier.size(16.dp))
                                        }
                                    } else {
                                        IconButton(onClick = { onDismissJob(job.id) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Outlined.Delete, "Remove", tint = colors.textDim, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                Text(job.prompt, style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(job.stepNote, style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                                if (job.output.isNotBlank()) {
                                    Text(
                                        job.output.take(260),
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 11.sp,
                                        color = colors.textDim,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(colors.bg, RoundedCornerShape(6.dp))
                                            .padding(6.dp)
                                    )
                                    TextButton(onClick = { onInjectIntoChat(job); onDismiss() }) {
                                        Text("Insert result into Chat")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun TextPreviewDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = content.ifBlank { "(empty)" },
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
