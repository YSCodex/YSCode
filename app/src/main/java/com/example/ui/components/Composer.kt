// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agent.SlashCommandDef
import com.example.data.PermissionMode
import com.example.llm.LlmContentPart
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.theme.YSCodeStrings

@Composable
fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    isRunning: Boolean,
    mode: PermissionMode,
    yoloConfirmedOnce: Boolean,
    onSelectMode: (PermissionMode) -> Unit,
    onConfirmYolo: () -> Unit,
    slashCommands: List<SlashCommandDef>,
    projectFiles: List<String>,
    attachments: List<LlmContentPart.ImageBase64>,
    onRemoveAttachment: (Int) -> Unit,
    queuedPrompts: List<String>,
    onClearQueue: () -> Unit,
    strings: YSCodeStrings,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onVoiceInput: () -> Unit,
    onExecuteSlashCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalYSCodeColors.current
    val clipboard = LocalClipboardManager.current
    var showPlusMenu by remember { mutableStateOf(false) }

    val showSlashPalette = text.startsWith("/") && !text.contains("\n")
    val slashQuery = if (showSlashPalette) text.lowercase() else ""
    val matchedCommands = remember(slashQuery, slashCommands) {
        if (!showSlashPalette) emptyList()
        else slashCommands.filter { it.name.lowercase().startsWith(slashQuery) }.take(8)
    }

    val lastAtIdx = text.lastIndexOf('@')
    val atToken = if (lastAtIdx >= 0 && (lastAtIdx == 0 || text[lastAtIdx - 1].isWhitespace())) {
        val sub = text.substring(lastAtIdx + 1)
        if (!sub.contains(' ') && !sub.contains('\n')) sub else null
    } else null
    val matchedFiles = remember(atToken, projectFiles) {
        if (atToken == null) emptyList()
        else projectFiles.filter { it.contains(atToken, ignoreCase = true) }.take(8)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Slash command palette popup
        if (matchedCommands.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 220.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(matchedCommands) { cmd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onExecuteSlashCommand(cmd.name)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cmd.name,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = colors.accent,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = colors.border.copy(alpha = 0.4f))
                    }
                }
            }
        }

        // @file fuzzy picker popup
        if (matchedFiles.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(matchedFiles) { path ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val prefix = text.substring(0, lastAtIdx)
                                    onTextChange("$prefix@$path ")
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "@$path",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.info
                            )
                        }
                    }
                }
            }
        }

        // Queued message indicator
        if (queuedPrompts.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(queuedPrompts) { q ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.info.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, colors.info.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Queued: ${q.take(28)}", style = MaterialTheme.typography.labelSmall, color = colors.info)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Clear queue",
                                tint = colors.info,
                                modifier = Modifier.size(14.dp).clickable { onClearQueue() }
                            )
                        }
                    }
                }
            }
        }

        // Attached images strip
        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(attachments.size) { idx ->
                    val att = attachments[idx]
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surface2,
                        border = BorderStroke(1.dp, colors.border)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Image, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(att.fileName, style = MaterialTheme.typography.labelSmall, color = colors.text)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove attachment",
                                tint = colors.textDim,
                                modifier = Modifier.size(14.dp).clickable { onRemoveAttachment(idx) }
                            )
                        }
                    }
                }
            }
        }

        // Main rounded composer pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("chat_composer_input"),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                text = strings.messageHint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textDim,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(
                                onClick = { showPlusMenu = true },
                                modifier = Modifier.testTag("composer_plus_button")
                            ) {
                                Icon(Icons.Outlined.Add, "Attach or insert", tint = colors.textDim)
                            }
                            DropdownMenu(
                                expanded = showPlusMenu,
                                onDismissRequest = { showPlusMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Attach image") },
                                    leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                    onClick = { showPlusMenu = false; onPickImage() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Attach file") },
                                    leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                                    onClick = { showPlusMenu = false; onPickFile() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Voice input") },
                                    leadingIcon = { Icon(Icons.Outlined.Mic, null) },
                                    onClick = { showPlusMenu = false; onVoiceInput() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Paste clipboard") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                                    onClick = {
                                        showPlusMenu = false
                                        clipboard.getText()?.text?.let { clip ->
                                            onTextChange(text + clip)
                                        }
                                    }
                                )
                            }
                        }
                        ModeChip(
                            mode = mode,
                            yoloConfirmedOnce = yoloConfirmedOnce,
                            onSelectMode = onSelectMode,
                            onConfirmYoloFirstTime = onConfirmYolo
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isRunning && text.isBlank()) {
                            Surface(
                                onClick = onStop,
                                shape = CircleShape,
                                color = colors.danger,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(40.dp)
                                    .testTag("composer_stop_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Stop, strings.stopAction, tint = androidx.compose.ui.graphics.Color.White)
                                }
                            }
                        } else {
                            val canSend = text.isNotBlank() || attachments.isNotEmpty()
                            Surface(
                                onClick = { if (canSend) onSend() },
                                shape = CircleShape,
                                color = if (canSend) colors.accent else colors.surface2,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(40.dp)
                                    .testTag("composer_send_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = if (isRunning) "Queue message" else "Send",
                                        tint = if (canSend) androidx.compose.ui.graphics.Color.White else colors.textDim,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (isRunning) {
                                Surface(
                                    onClick = onStop,
                                    shape = CircleShape,
                                    color = colors.danger,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .size(40.dp)
                                        .testTag("composer_stop_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.Stop, strings.stopAction, tint = androidx.compose.ui.graphics.Color.White)
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
