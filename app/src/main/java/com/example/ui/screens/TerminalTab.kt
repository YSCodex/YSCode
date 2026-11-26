// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.MainViewModel
import java.io.File

@Composable
fun TerminalTab(
    mainViewModel: MainViewModel
) {
    val colors = LocalYSCodeColors.current
    val clipboard = LocalClipboardManager.current
    val tabs by mainViewModel.terminalTabs.collectAsStateWithLifecycle()
    val selectedId by mainViewModel.selectedTerminalTabId.collectAsStateWithLifecycle()
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val activeProject by mainViewModel.activeProject.collectAsStateWithLifecycle()

    val currentTab = remember(tabs, selectedId) {
        tabs.find { it.id == selectedId } ?: tabs.firstOrNull()
    }
    var commandInput by remember { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }
    var fontScale by remember(settings.terminalFontSize) { mutableFloatStateOf(settings.terminalFontSize.toFloat()) }
    val isProot = remember(tabs.size) { mainViewModel.container.terminalManager.isProotActive() }

    val listState = rememberLazyListState()
    LaunchedEffect(currentTab?.lines?.size) {
        val count = currentTab?.lines?.size ?: 0
        if (count > 0) {
            listState.scrollToItem(count - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
    ) {
        // Tab strip + actions
        Surface(color = colors.surface, border = BorderStroke(1.dp, colors.border)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val active = tab.id == currentTab?.id
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) colors.accent.copy(alpha = 0.16f) else colors.surface2,
                            border = BorderStroke(1.dp, if (active) colors.accent else colors.border),
                            modifier = Modifier.clickable { mainViewModel.container.terminalManager.selectTab(tab.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) colors.accent else colors.text
                                )
                                if (!tab.isAgentMirror) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Close tab",
                                        tint = colors.textDim,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { mainViewModel.container.terminalManager.closeTab(tab.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                IconButton(
                    onClick = {
                        val dir = activeProject?.let { File(it.path) }
                        mainViewModel.container.terminalManager.newTab(dir)
                    },
                    modifier = Modifier.testTag("terminal_new_tab_btn")
                ) {
                    Icon(Icons.Outlined.Add, "New shell tab", tint = colors.text)
                }
                IconButton(
                    onClick = {
                        currentTab?.let { tab ->
                            clipboard.setText(AnnotatedString(mainViewModel.container.terminalManager.getTabText(tab.id)))
                            mainViewModel.emitToast("Copied terminal buffer")
                        }
                    },
                    modifier = Modifier.testTag("terminal_copy_btn")
                ) {
                    Icon(Icons.Outlined.ContentCopy, "Copy output", tint = colors.textDim)
                }
                IconButton(
                    onClick = {
                        currentTab?.let { mainViewModel.container.terminalManager.clearTab(it.id) }
                    },
                    modifier = Modifier.testTag("terminal_clear_btn")
                ) {
                    Icon(Icons.Outlined.DeleteSweep, "Clear", tint = colors.textDim)
                }
            }
        }

        // Runtime status + CWD bar
        Surface(
            color = colors.surface2.copy(alpha = 0.7f),
            border = BorderStroke(0.5.dp, colors.border.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = (if (isProot) colors.success else colors.info).copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, (if (isProot) colors.success else colors.info).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = if (isProot) colors.success else colors.info,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (isProot) "PRoot Linux" else "POSIX + Git/HTTP",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isProot) colors.success else colors.info,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = colors.textDim,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = currentTab?.cwdPath ?: "/root",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (currentTab?.isAgentMirror == true) {
                    Surface(
                        onClick = {
                            val existingShell = tabs.firstOrNull { !it.isAgentMirror }
                            if (existingShell != null) {
                                mainViewModel.container.terminalManager.selectTab(existingShell.id)
                            } else {
                                val dir = activeProject?.let { File(it.path) }
                                mainViewModel.container.terminalManager.newTab(dir)
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = colors.accent.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("terminal_open_shell_btn")
                    ) {
                        Text(
                            text = "Open Shell",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Terminal output viewport with pinch-to-zoom font
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            fontScale = (fontScale * zoom).coerceIn(9f, 24f)
                        }
                    }
                }
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(currentTab?.lines.orEmpty(), key = { it.id }) { line ->
                        val annotated = buildAnnotatedString {
                            line.spans.forEach { span ->
                                val baseColor = span.color ?: colors.text
                                withStyle(
                                    SpanStyle(
                                        color = if (span.isDim) baseColor.copy(alpha = 0.6f) else baseColor,
                                        fontWeight = if (span.isBold) FontWeight.Bold else FontWeight.Normal
                                    )
                                ) {
                                    append(span.text)
                                }
                            }
                        }
                        Text(
                            text = annotated,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = fontScale.sp,
                            lineHeight = (fontScale + 5f).sp
                        )
                    }
                }
            }
        }

        // Quick command presets, extra-keys row, and prompt input for interactive shell tabs
        if (currentTab != null && !currentTab.isAgentMirror) {
            val quickCommands = listOf(
                "ls -la" to "ls -la",
                "git status" to "git status",
                "pkg status" to "pkg status",
                "help" to "help",
                "Setup Alpine" to "pkg bootstrap-alpine"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickCommands.forEach { (label, cmd) ->
                    Surface(
                        onClick = {
                            if (!currentTab.isRunning) {
                                mainViewModel.container.terminalManager.runCommand(currentTab.id, cmd)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.border.copy(alpha = 0.8f)),
                        modifier = Modifier.testTag("terminal_quick_${label.lowercase().replace(' ', '_')}")
                    ) {
                        Text(
                            text = label,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = colors.textDim,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            val extraKeys = listOf("ESC", "TAB", "CTRL+C", "CTRL+L", "↑", "↓", "|", "~", "/", "-")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                extraKeys.forEach { key ->
                    Surface(
                        onClick = {
                            when (key) {
                                "CTRL+C" -> mainViewModel.container.terminalManager.sendInterrupt(currentTab.id)
                                "CTRL+L" -> mainViewModel.container.terminalManager.clearTab(currentTab.id)
                                "TAB" -> commandInput += "  "
                                "ESC" -> commandInput = ""
                                "↑" -> {
                                    val hist = currentTab.history
                                    if (hist.isNotEmpty()) {
                                        val next = if (historyCursor < 0) hist.lastIndex else (historyCursor - 1).coerceAtLeast(0)
                                        historyCursor = next
                                        commandInput = hist[next]
                                    }
                                }
                                "↓" -> {
                                    val hist = currentTab.history
                                    if (historyCursor in hist.indices) {
                                        if (historyCursor >= hist.lastIndex) {
                                            historyCursor = -1
                                            commandInput = ""
                                        } else {
                                            historyCursor++
                                            commandInput = hist[historyCursor]
                                        }
                                    }
                                }
                                else -> commandInput += key
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = colors.surface2,
                        border = BorderStroke(1.dp, colors.border)
                    ) {
                        Text(
                            text = key,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = if (key == "CTRL+C") colors.danger else colors.text,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Surface(
                color = colors.surface2,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${currentTab.cwdPath.substringAfterLast('/').ifBlank { "/" }} $",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp,
                        color = colors.accent
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        textStyle = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 14.sp,
                            color = colors.text
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (commandInput.isNotBlank()) {
                                    mainViewModel.container.terminalManager.runCommand(currentTab.id, commandInput)
                                    commandInput = ""
                                    historyCursor = -1
                                }
                            }
                        ),
                        modifier = Modifier.weight(1f).testTag("terminal_command_input"),
                        decorationBox = { inner ->
                            if (commandInput.isEmpty()) {
                                Text("Enter shell command (git, curl, ls, help)...", fontFamily = JetBrainsMonoFontFamily, fontSize = 13.sp, color = colors.textDim)
                            }
                            inner()
                        }
                    )
                    if (currentTab.isRunning) {
                        IconButton(
                            onClick = { mainViewModel.container.terminalManager.sendInterrupt(currentTab.id) },
                            modifier = Modifier.testTag("terminal_interrupt_btn")
                        ) {
                            Icon(Icons.Outlined.Stop, "Interrupt", tint = colors.danger)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (commandInput.isNotBlank()) {
                                    mainViewModel.container.terminalManager.runCommand(currentTab.id, commandInput)
                                    commandInput = ""
                                    historyCursor = -1
                                }
                            },
                            modifier = Modifier.testTag("terminal_run_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Run command", tint = colors.accent)
                        }
                    }
                }
            }
        }
    }
}
