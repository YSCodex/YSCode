// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.StreamingToolState
import com.example.tools.ToolDiffInfo
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.util.DiffEngine
import com.example.util.DiffLineType

@Composable
fun ToolCard(
    tool: StreamingToolState,
    onViewDiff: (String) -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalYSCodeColors.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember(tool.id) { mutableStateOf(false) }

    val borderColor = when (tool.status) {
        "error", "denied" -> colors.danger.copy(alpha = 0.7f)
        "running" -> colors.accent.copy(alpha = 0.6f)
        else -> colors.border
    }

    val icon = when {
        tool.name == "bash" || tool.name == "bash_output" || tool.name == "kill_job" -> Icons.Outlined.Terminal
        tool.name in setOf("edit_file", "multi_edit", "write_file") -> Icons.Outlined.Edit
        tool.name in setOf("glob", "grep", "web_search") -> Icons.Outlined.Search
        tool.name == "web_fetch" -> Icons.Outlined.Language
        tool.name == "repo_map" -> Icons.Outlined.AccountTree
        tool.name == "memory_write" -> Icons.Outlined.BookmarkAdded
        tool.name in setOf("task", "parallel_tasks") -> Icons.Outlined.Hub
        tool.name == "update_steps" -> Icons.Outlined.Checklist
        tool.name in setOf("read_file", "list_dir") -> Icons.Outlined.Description
        else -> Icons.Outlined.Folder
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag("tool_card_${tool.name}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tool.name,
                    tint = if (tool.status == "error" || tool.status == "denied") colors.danger else colors.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (tool.name == "update_steps") "plan updated" else tool.argsPreview.replace("\n", " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                ToolStatusBadge(status = tool.status, durationMs = tool.durationMs, summary = tool.summary)
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = colors.textDim,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Live 5-line preview for running bash
            if (tool.name == "bash" && tool.status == "running" && tool.outputPreview.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface2)
                        .padding(8.dp)
                ) {
                    Text(
                        text = tool.outputPreview,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = colors.textDim
                    )
                }
            }

            // Inline mini diff for write/edit cards
            tool.diff?.let { diffInfo ->
                MiniDiffPreview(
                    diffInfo = diffInfo,
                    onViewDiff = { onViewDiff(diffInfo.relativePath) }
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surface2)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = tool.fullOutput.ifBlank { tool.argsPreview },
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            color = colors.text
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (tool.name == "bash") {
                            TextButton(onClick = onOpenTerminal) {
                                Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open in terminal")
                            }
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(tool.fullOutput.ifBlank { tool.argsPreview }))
                        }) {
                            Icon(Icons.Outlined.ContentCopy, "Copy output", tint = colors.textDim, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStatusBadge(status: String, durationMs: Long, summary: String) {
    val colors = LocalYSCodeColors.current
    if (status == "running") {
        val pulse = rememberInfiniteTransition(label = "pulse")
        val alpha by pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "dotAlpha"
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(colors.accent)
            )
            Text("running", style = MaterialTheme.typography.labelSmall, color = colors.accent)
        }
    } else {
        val color = if (status == "done") colors.success else colors.danger
        val icon = if (status == "done") Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline
        val label = buildString {
            if (summary.isNotBlank()) append(summary) else append(status)
            if (durationMs > 0) append(" ${String.format("%.1fs", durationMs / 1000.0)}")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, status, tint = color, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun MiniDiffPreview(diffInfo: ToolDiffInfo, onViewDiff: () -> Unit) {
    val colors = LocalYSCodeColors.current
    val previewLines = remember(diffInfo) {
        DiffEngine.computeDiff(diffInfo.relativePath, diffInfo.before, diffInfo.after, contextLines = 1)
            .hunks.flatMap { it.lines }
            .filter { it.type != DiffLineType.CONTEXT }
            .take(6)
    }
    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface2)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${diffInfo.relativePath}  +${diffInfo.addedLines} -${diffInfo.removedLines}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onViewDiff, modifier = Modifier.testTag("mini_diff_view_btn")) {
                Icon(Icons.Outlined.Difference, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("View diff", style = MaterialTheme.typography.labelSmall)
            }
        }
        previewLines.forEach { line ->
            val bg = if (line.type == DiffLineType.ADDED) colors.diffAddBg else colors.diffRemoveBg
            val prefix = if (line.type == DiffLineType.ADDED) "+" else "-"
            val fg = if (line.type == DiffLineType.ADDED) colors.success else colors.danger
            Text(
                text = "$prefix ${line.text}",
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.sp,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun ReadGroupCard(
    items: List<StreamingToolState>,
    modifier: Modifier = Modifier
) {
    val colors = LocalYSCodeColors.current
    var expanded by remember { mutableStateOf(false) }

    val reads = items.count { it.name == "read_file" }
    val searches = items.count { it.name in setOf("glob", "grep", "list_dir", "repo_map") }
    val title = buildString {
        append("Explored ")
        val parts = mutableListOf<String>()
        if (reads > 0) parts += "$reads file${if (reads > 1) "s" else ""}"
        if (searches > 0) parts += "$searches search${if (searches > 1) "es" else ""}"
        append(parts.joinToString(", ").ifBlank { "${items.size} steps" })
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier
            .fillMaxWidth()
            .testTag("read_group_card")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, "Explored", tint = colors.info, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = colors.textDim,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items.forEach { t ->
                        ToolCard(
                            tool = t,
                            onViewDiff = {},
                            onOpenTerminal = {}
                        )
                    }
                }
            }
        }
    }
}
