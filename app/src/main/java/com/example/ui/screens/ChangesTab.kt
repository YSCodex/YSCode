// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.git.TrackedFileChange
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.MainViewModel
import com.example.util.DiffHunk
import com.example.util.DiffLine
import com.example.util.DiffLineType
import com.example.util.HunkStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChangesTab(
    mainViewModel: MainViewModel
) {
    val colors = LocalYSCodeColors.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val changes by mainViewModel.changes.collectAsStateWithLifecycle()
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val gitStatus by mainViewModel.gitStatus.collectAsStateWithLifecycle()

    var selectedFilePath by remember(changes) {
        mutableStateOf(changes.firstOrNull()?.relativePath)
    }
    var showCommitDialog by remember { mutableStateOf(false) }

    val totalAdded = remember(changes) { changes.sumOf { it.diff.addedCount } }
    val totalRemoved = remember(changes) { changes.sumOf { it.diff.removedCount } }

    Column(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        // Header bar
        Surface(color = colors.surface, border = BorderStroke(1.dp, colors.border)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${changes.size} file(s) changed  +$totalAdded −$totalRemoved",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.text
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = colors.surface2
                    ) {
                        Text(
                            text = "git: ${gitStatus.branch}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.info,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            mainViewModel.acceptAllChanges()
                        },
                        enabled = changes.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("changes_accept_all_btn")
                    ) {
                        Icon(Icons.Outlined.DoneAll, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Accept all", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            mainViewModel.revertAllChanges()
                        },
                        enabled = changes.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("changes_revert_all_btn")
                    ) {
                        Icon(Icons.Outlined.Restore, null, tint = colors.danger, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Revert all", color = colors.danger, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = { showCommitDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("changes_commit_btn")
                    ) {
                        Text("Commit", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        if (changes.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyStateCard(
                    icon = Icons.Outlined.Difference,
                    title = "No pending changes",
                    subtitle = "Edits made by the agent appear here for hunk-by-hunk review, accept/revert, and git commit."
                )
            }
        } else {
            val activeChange = changes.find { it.relativePath == selectedFilePath } ?: changes.first()
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isWide = maxWidth >= 620.dp
                Column(modifier = Modifier.fillMaxSize()) {
                    // File selector strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        changes.forEach { ch ->
                            val selected = ch.relativePath == activeChange.relativePath
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) colors.accent.copy(alpha = 0.16f) else colors.surface,
                                border = BorderStroke(1.dp, if (selected) colors.accent else colors.border),
                                modifier = Modifier.clickable { selectedFilePath = ch.relativePath }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = ch.relativePath,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) colors.accent else colors.text
                                    )
                                    Text("+${ch.diff.addedCount}", style = MaterialTheme.typography.labelSmall, color = colors.success)
                                    Text("-${ch.diff.removedCount}", style = MaterialTheme.typography.labelSmall, color = colors.danger)
                                }
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(activeChange.diff.hunks, key = { "${activeChange.relativePath}_${it.id}" }) { hunk ->
                            DiffHunkCard(
                                hunk = hunk,
                                sideBySide = isWide,
                                codeFontSize = settings.codeFontSize,
                                onAcceptHunk = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    mainViewModel.setHunkStatus(activeChange.relativePath, hunk.id, HunkStatus.ACCEPTED)
                                },
                                onRejectHunk = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    mainViewModel.setHunkStatus(activeChange.relativePath, hunk.id, HunkStatus.REJECTED)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCommitDialog) {
        var commitMsg by remember { mutableStateOf("") }
        var pushAfterCommit by remember { mutableStateOf(false) }
        var generating by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCommitDialog = false },
            title = { Text("Git Commit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = commitMsg,
                        onValueChange = { commitMsg = it },
                        label = { Text("Commit message") },
                        placeholder = { Text("feat: describe your changes") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("commit_message_input")
                    )
                    OutlinedButton(
                        onClick = {
                            generating = true
                            scope.launch {
                                commitMsg = mainViewModel.generateAiCommitMessage()
                                generating = false
                            }
                        },
                        enabled = !generating
                    ) {
                        if (generating) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generate with AI")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Push to remote after commit", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = pushAfterCommit, onCheckedChange = { pushAfterCommit = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCommitDialog = false
                        mainViewModel.commitChanges(commitMsg.trim(), pushAfterCommit)
                    },
                    enabled = commitMsg.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Commit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCommitDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DiffHunkCard(
    hunk: DiffHunk,
    sideBySide: Boolean,
    codeFontSize: Int,
    onAcceptHunk: () -> Unit,
    onRejectHunk: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface2)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@ (${hunk.status.name.lowercase()})",
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textDim
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onAcceptHunk, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Check, "Accept hunk", tint = colors.success, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onRejectHunk, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, "Reject hunk", tint = colors.danger, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                if (sideBySide) {
                    val leftLines = hunk.lines.filter { it.type != DiffLineType.ADDED }
                    val rightLines = hunk.lines.filter { it.type != DiffLineType.REMOVED }
                    val maxRows = maxOf(leftLines.size, rightLines.size)
                    for (r in 0 until maxRows) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.width(340.dp)) {
                                leftLines.getOrNull(r)?.let { DiffLineRow(it, codeFontSize) }
                            }
                            Box(modifier = Modifier.width(340.dp)) {
                                rightLines.getOrNull(r)?.let { DiffLineRow(it, codeFontSize) }
                            }
                        }
                    }
                } else {
                    hunk.lines.forEach { line ->
                        DiffLineRow(line, codeFontSize)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, codeFontSize: Int) {
    val colors = LocalYSCodeColors.current
    val bg = when (line.type) {
        DiffLineType.ADDED -> colors.diffAddBg
        DiffLineType.REMOVED -> colors.diffRemoveBg
        DiffLineType.CONTEXT -> Color.Transparent
    }
    val wordHighlight = when (line.type) {
        DiffLineType.ADDED -> colors.diffAddWord
        DiffLineType.REMOVED -> colors.diffRemoveWord
        DiffLineType.CONTEXT -> Color.Transparent
    }
    val prefix = when (line.type) {
        DiffLineType.ADDED -> "+"
        DiffLineType.REMOVED -> "-"
        DiffLineType.CONTEXT -> " "
    }
    val lineNumStr = (line.newLineNumber ?: line.oldLineNumber)?.toString()?.padStart(3) ?: "   "

    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.textDim)) {
            append("$lineNumStr $prefix ")
        }
        line.wordSpans.forEach { span ->
            withStyle(
                SpanStyle(
                    color = colors.text,
                    background = if (span.emphasized) wordHighlight else Color.Transparent
                )
            ) {
                append(span.text)
            }
        }
    }

    Text(
        text = annotated,
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = (codeFontSize - 1).coerceAtLeast(10).sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
