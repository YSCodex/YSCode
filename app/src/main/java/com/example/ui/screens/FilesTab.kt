// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import android.content.Intent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.highlightCode
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.MainViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesTab(
    mainViewModel: MainViewModel,
    onAskAgentAboutFile: (String) -> Unit
) {
    val colors = LocalYSCodeColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val project by mainViewModel.activeProject.collectAsStateWithLifecycle()
    val gitStatus by mainViewModel.gitStatus.collectAsStateWithLifecycle()
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()

    val rootDir = remember(project) { project?.let { File(it.path) } }
    var currentDir by remember(rootDir) { mutableStateOf(rootDir) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var openedFile by remember { mutableStateOf<File?>(null) }
    var showCreateDialog by remember { mutableStateOf<Boolean?>(null) } // true=folder, false=file
    var showWebPreview by remember { mutableStateOf<File?>(null) }

    if (openedFile != null && rootDir != null) {
        BackHandler { openedFile = null }
        FileEditorView(
            file = openedFile!!,
            projectDir = rootDir,
            codeFontSize = settings.codeFontSize,
            onBack = { openedFile = null; refreshTick++ },
            onSaved = {
                mainViewModel.refreshProjectContext(rootDir)
                mainViewModel.emitToast("Saved ${openedFile?.name}")
            },
            onAskAgent = { relPath ->
                openedFile = null
                onAskAgentAboutFile(relPath)
            },
            onWebPreview = { htmlFile -> showWebPreview = htmlFile }
        )
    } else {
        val entries = remember(currentDir, refreshTick, searchQuery) {
            val dir = currentDir ?: return@remember emptyList()
            if (!dir.exists()) return@remember emptyList()
            if (searchQuery.isNotBlank() && rootDir != null) {
                rootDir.walkTopDown()
                    .onEnter { it == rootDir || it.name !in setOf(".git", "node_modules", "build") }
                    .filter { it.name.contains(searchQuery, ignoreCase = true) }
                    .take(100)
                    .toList()
            } else {
                dir.listFiles()
                    ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    .orEmpty()
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(colors.bg)) {
            // Toolbar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentDir != null && rootDir != null && currentDir != rootDir) {
                    IconButton(onClick = { currentDir = currentDir?.parentFile ?: rootDir }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Up", tint = colors.text)
                    }
                }
                Text(
                    text = if (rootDir != null && currentDir != null) {
                        "/${currentDir!!.relativeTo(rootDir).path}".replace("//", "/")
                    } else "/",
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showCreateDialog = false }, modifier = Modifier.testTag("files_new_file_btn")) {
                    Icon(Icons.Outlined.Add, "New file", tint = colors.text)
                }
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Outlined.CreateNewFolder, "New folder", tint = colors.text)
                }
                IconButton(onClick = {
                    refreshTick++
                    rootDir?.let { dir ->
                        mainViewModel.refreshProjectContext(dir)
                        // Linked local folder: pull outside changes and push project changes, then refresh the list.
                        mainViewModel.syncLinkedFolder(dir) { refreshTick++ }
                    }
                }) {
                    Icon(Icons.Outlined.Refresh, "Refresh", tint = colors.textDim)
                }
            }

            if (selectedFiles.isNotEmpty()) {
                Surface(color = colors.surface2, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${selectedFiles.size} selected", style = MaterialTheme.typography.labelMedium, color = colors.text)
                        Row {
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(selectedFiles.joinToString("\n")))
                                mainViewModel.emitToast("Copied path(s)")
                                selectedFiles = emptySet()
                            }) {
                                Icon(Icons.Outlined.ContentCopy, "Copy path", tint = colors.text)
                            }
                            IconButton(onClick = {
                                val first = selectedFiles.firstOrNull()?.let { File(it) }
                                if (first != null && first.isFile) {
                                    runCatching {
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", first)
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "*/*"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Share ${first.name}"))
                                    }
                                }
                                selectedFiles = emptySet()
                            }) {
                                Icon(Icons.Outlined.Share, "Share", tint = colors.text)
                            }
                            IconButton(onClick = {
                                selectedFiles.forEach { File(it).deleteRecursively() }
                                selectedFiles = emptySet()
                                refreshTick++
                                rootDir?.let { mainViewModel.refreshProjectContext(it) }
                            }) {
                                Icon(Icons.Outlined.Delete, "Delete", tint = colors.danger)
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter files...") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )

            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = {
                    refreshTick++
                    rootDir?.let { mainViewModel.refreshProjectContext(it) }
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(entries, key = { it.absolutePath }) { file ->
                        val rel = if (rootDir != null) {
                            runCatching { file.relativeTo(rootDir).path.replace(File.separatorChar, '/') }.getOrDefault(file.name)
                        } else file.name
                        val badge = gitStatus.changedFiles[rel]
                        val isSelected = file.absolutePath in selectedFiles

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) colors.accent.copy(alpha = 0.16f) else colors.surface,
                            border = BorderStroke(1.dp, if (isSelected) colors.accent else colors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectedFiles.isNotEmpty()) {
                                            selectedFiles = if (isSelected) selectedFiles - file.absolutePath else selectedFiles + file.absolutePath
                                        } else if (file.isDirectory) {
                                            currentDir = file
                                            searchQuery = ""
                                        } else {
                                            openedFile = file
                                        }
                                    },
                                    onLongClick = {
                                        selectedFiles = if (isSelected) selectedFiles - file.absolutePath else selectedFiles + file.absolutePath
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = if (file.isDirectory) colors.accent else colors.info,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = if (searchQuery.isNotBlank()) rel else file.name,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (badge != null) {
                                    val badgeColor = when (badge) {
                                        "A", "?" -> colors.success
                                        else -> colors.warn
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = badgeColor.copy(alpha = 0.18f)
                                    ) {
                                        Text(
                                            text = badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = badgeColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                } else if (file.isFile) {
                                    Text(
                                        text = "${file.length()}B",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textDim
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showCreateDialog?.let { isFolder ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = null },
            title = { Text(if (isFolder) "New Folder" else "New File") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val base = currentDir ?: rootDir
                    if (base != null && name.isNotBlank()) {
                        val target = File(base, name.trim())
                        if (isFolder) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            target.createNewFile()
                            openedFile = target
                        }
                        refreshTick++
                        rootDir?.let { mainViewModel.refreshProjectContext(it) }
                    }
                    showCreateDialog = null
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = null }) { Text("Cancel") }
            }
        )
    }

    showWebPreview?.let { htmlFile ->
        AlertDialog(
            onDismissRequest = { showWebPreview = null },
            title = { Text("Live Web Preview — ${htmlFile.name}") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                this.settings.javaScriptEnabled = true
                                this.settings.allowFileAccess = true
                                loadUrl("file://${htmlFile.absolutePath}")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showWebPreview = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun FileEditorView(
    file: File,
    projectDir: File,
    codeFontSize: Int,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onAskAgent: (String) -> Unit,
    onWebPreview: (File) -> Unit
) {
    val colors = LocalYSCodeColors.current
    val relPath = remember(file, projectDir) {
        runCatching { file.relativeTo(projectDir).path.replace(File.separatorChar, '/') }.getOrDefault(file.name)
    }
    var content by remember(file) {
        mutableStateOf(runCatching { file.readText() }.getOrElse { "// Unable to read file: ${it.message}" })
    }
    var isEditing by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var showFind by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(colors.bg).imePadding()) {
        Surface(color = colors.surface, border = BorderStroke(1.dp, colors.border)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                }
                Text(
                    text = relPath,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (file.extension.lowercase() in setOf("html", "htm")) {
                    IconButton(onClick = { onWebPreview(file) }) {
                        Icon(Icons.Outlined.Preview, "Preview HTML", tint = colors.info)
                    }
                }
                IconButton(onClick = { showFind = !showFind }) {
                    Icon(Icons.Outlined.Search, "Find", tint = colors.textDim)
                }
                IconButton(onClick = { onAskAgent(relPath) }) {
                    Icon(Icons.Outlined.AutoAwesome, "Ask agent about this file", tint = colors.accent)
                }
                if (isEditing) {
                    IconButton(onClick = {
                        file.writeText(content)
                        isEditing = false
                        onSaved()
                    }, modifier = Modifier.testTag("file_editor_save_btn")) {
                        Icon(Icons.Outlined.Save, "Save", tint = colors.success)
                    }
                } else {
                    IconButton(onClick = { isEditing = true }, modifier = Modifier.testTag("file_editor_edit_btn")) {
                        Icon(Icons.Outlined.Edit, "Edit", tint = colors.text)
                    }
                }
            }
        }

        if (showFind) {
            OutlinedTextField(
                value = findText,
                onValueChange = { findText = it },
                placeholder = { Text("Find in file...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        val lines = remember(content) { content.lines() }
        if (isEditing) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(10.dp).verticalScroll(rememberScrollState())) {
                BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = codeFontSize.sp,
                        lineHeight = (codeFontSize + 6).sp,
                        color = colors.text
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxSize().testTag("file_editor_textfield")
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()),
                contentPadding = PaddingValues(10.dp)
            ) {
                items(lines.size) { idx ->
                    val line = lines[idx]
                    val matchesFind = findText.isNotBlank() && line.contains(findText, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (matchesFind) colors.warn.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
                    ) {
                        Text(
                            text = "${idx + 1}".padStart(4) + "  ",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = codeFontSize.sp,
                            color = colors.textDim.copy(alpha = 0.6f)
                        )
                        Text(
                            text = highlightCode(line, colors.text, colors.accent, colors.info, colors.success, colors.textDim),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = codeFontSize.sp
                        )
                    }
                }
            }
        }
    }
}
