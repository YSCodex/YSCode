// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.agent.AgentActivityState
import com.example.tools.TodoItem
import com.example.ui.theme.LocalYSCodeColors
import kotlinx.coroutines.delay

@Composable
fun TodoChecklistCard(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier
) {
    if (todos.isEmpty()) return
    val colors = LocalYSCodeColors.current
    val completed = todos.count { it.status == "completed" }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier.fillMaxWidth().testTag("todo_card")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tasks", style = MaterialTheme.typography.titleSmall, color = colors.text)
                Text("$completed/${todos.size}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
            }
            LinearProgressIndicator(
                progress = { completed.toFloat() / todos.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = colors.accent,
                trackColor = colors.surface2
            )
            todos.forEach { item ->
                val (icon, tint) = when (item.status) {
                    "completed" -> Icons.Outlined.CheckBox to colors.success
                    "in_progress" -> Icons.Outlined.Pending to colors.accent
                    else -> Icons.Outlined.CheckBoxOutlineBlank to colors.textDim
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, item.status, tint = tint, modifier = Modifier.size(16.dp))
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == "completed") colors.textDim else colors.text
                    )
                }
            }
        }
    }
}

@Composable
fun ActivitySummaryCard(
    activity: AgentActivityState,
    todos: List<TodoItem>,
    activeJobsCount: Int,
    onKillAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalYSCodeColors.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activity.isRunning) {
        while (activity.isRunning) {
            nowMs = System.currentTimeMillis()
            delay(500L)
        }
    }
    val elapsedSec = if (activity.isRunning && activity.startedAtMs > 0L) {
        ((nowMs - activity.startedAtMs) / 1000L).coerceAtLeast(0L)
    } else 0L

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = if (activity.isRunning) "Agent Active: ${activity.currentPhase}" else "Agent Idle",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (activity.isRunning) colors.accent else colors.text
                    )
                    Text(
                        text = "Step ${activity.stepCount}/${activity.maxSteps} • ${elapsedSec}s • ~${activity.contextTokensEstimate} ctx tokens • $activeJobsCount bg jobs",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textDim
                    )
                }
                Button(
                    onClick = onKillAll,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("kill_switch_button")
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Kill Switch", maxLines = 1, softWrap = false)
                }
            }
            if (todos.isNotEmpty()) {
                TodoChecklistCard(todos = todos)
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalYSCodeColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface2,
            border = BorderStroke(1.dp, colors.border)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.padding(16.dp).size(32.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            textAlign = TextAlign.Center
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textDim,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Text(actionLabel.ifBlank { "New project" }, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
