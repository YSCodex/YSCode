// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ProjectEntity
import com.example.data.ProjectTemplate
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.UsageStatsCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.theme.stringsFor
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    onOpenProject: (ProjectEntity) -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    val context = LocalContext.current
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val strings = remember(settings.uiLanguage) { stringsFor(settings.uiLanguage) }
    val projects by mainViewModel.projects.collectAsStateWithLifecycle()
    val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()
    val dailyUsage by mainViewModel.dailyUsage.collectAsStateWithLifecycle()
    val telemetryPrefs = remember { com.example.update.Telemetry(context.applicationContext) }
    var showStatsConsent by remember { mutableStateOf(!telemetryPrefs.consentAsked) }

    var searchQuery by remember { mutableStateOf("") }
    var showNewSheet by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf<ProjectTemplate?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var showPathDialog by remember { mutableStateOf(false) }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            mainViewModel.importProjectZip(uri, "zip-project") { onOpenProject(it) }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            mainViewModel.importProjectFolder(uri) { onOpenProject(it) }
        }
    }

    val filtered = remember(projects, searchQuery) {
        if (searchQuery.isBlank()) projects
        else projects.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.path.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Code, null, tint = colors.accent)
                        Text("YSCode", style = MaterialTheme.typography.titleLarge, color = colors.text)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSkills, modifier = Modifier.testTag("home_skills_btn")) {
                        Icon(Icons.Outlined.Extension, "Skills", tint = colors.textDim)
                    }
                    IconButton(onClick = onOpenMcp, modifier = Modifier.testTag("home_mcp_btn")) {
                        Icon(Icons.Outlined.Hub, "MCP Servers", tint = colors.textDim)
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("home_settings_btn")) {
                        Icon(Icons.Outlined.Settings, "Settings", tint = colors.text)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewSheet = true },
                containerColor = colors.accent,
                contentColor = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.testTag("home_new_project_fab")
            ) {
                Icon(Icons.Outlined.Add, strings.newProject)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            val homeScroll = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .then(if (filtered.isEmpty()) Modifier.verticalScroll(homeScroll) else Modifier)
                    .padding(horizontal = 16.dp)
            ) {
                if (!isOnline) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.warn.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, colors.warn.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.WifiOff, null, tint = colors.warn, modifier = Modifier.size(18.dp))
                            Text(strings.offlineBanner, style = MaterialTheme.typography.bodySmall, color = colors.warn)
                        }
                    }
                }

                com.example.ui.components.UpdateBanner(modifier = Modifier.padding(bottom = 12.dp))
                if (showStatsConsent && settings.onboardingCompleted) {
                    com.example.ui.components.StatsConsentDialog { showStatsConsent = false }
                }
                UsageStatsCard(
                    daily = dailyUsage,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search projects...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("home_search_input")
                )

                Spacer(Modifier.height(12.dp))

                if (filtered.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Outlined.CreateNewFolder,
                        title = strings.noProjectsTitle,
                        subtitle = strings.noProjectsSubtitle,
                        actionLabel = strings.newProject,
                        onAction = { showNewSheet = true },
                        modifier = Modifier.padding(top = 16.dp, bottom = 96.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(filtered, key = { it.id }) { project ->
                            ProjectCard(
                                project = project,
                                onClick = {
                                    mainViewModel.openProject(project)
                                    onOpenProject(project)
                                },
                                onTogglePin = { mainViewModel.togglePinProject(project) },
                                onRename = { renameTarget = project },
                                onExport = { mainViewModel.shareProjectZip(context, project) },
                                onDelete = { mainViewModel.deleteProject(project) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewSheet = false },
            containerColor = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Create or Import Project", style = MaterialTheme.typography.titleMedium, color = colors.text)

                ProjectTemplate.entries.forEach { tpl ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showNewSheet = false
                                showTemplateDialog = tpl
                            }
                            .testTag("template_${tpl.id}")
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CreateNewFolder, null, tint = colors.accent)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(tpl.label, style = MaterialTheme.typography.titleSmall, color = colors.text)
                                Text(tpl.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showNewSheet = false; showCloneDialog = true },
                        modifier = Modifier.weight(1f).testTag("clone_git_option")
                    ) {
                        Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clone Git")
                    }
                    OutlinedButton(
                        onClick = { showNewSheet = false; folderPicker.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Folder")
                    }
                    OutlinedButton(
                        onClick = { showNewSheet = false; zipPicker.launch(arrayOf("application/zip", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Archive, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ZIP")
                    }
                }

                OutlinedButton(
                    onClick = { showNewSheet = false; folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Storage, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pick folder from file manager (opens in place)")
                }
            }
        }
    }

    if (showPathDialog) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val hasAccess by mainViewModel.allFilesAccess.collectAsStateWithLifecycle()
        com.example.ui.components.FolderBrowserDialog(
            hasAccess = hasAccess,
            onGrantAccess = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val i = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    )
                    runCatching { ctx.startActivity(i) }
                }
            },
            onPick = { path ->
                showPathDialog = false
                mainViewModel.openLocalFolder(path) { onOpenProject(it) }
            },
            onDismiss = { showPathDialog = false }
        )
    }

    showTemplateDialog?.let { template ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTemplateDialog = null },
            title = { Text("New ${template.label}") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    placeholder = { Text("my-${template.id}-app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_project_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalName = name.trim().ifBlank { "my-${template.id}-app" }
                        showTemplateDialog = null
                        mainViewModel.createProjectFromTemplate(finalName, template) { onOpenProject(it) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.testTag("confirm_create_project_btn")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTemplateDialog = null }) { Text("Cancel") }
            }
        )
    }

    if (showCloneDialog) {
        var repoUrl by remember { mutableStateOf("") }
        var customName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCloneDialog = false },
            title = { Text("Clone Git Repository") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://github.com/user/repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Folder name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCloneDialog = false
                        mainViewModel.cloneProjectFromGit(repoUrl, customName) { onOpenProject(it) }
                    },
                    enabled = repoUrl.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Clone")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloneDialog = false }) { Text("Cancel") }
            }
        )
    }

    renameTarget?.let { target ->
        var newName by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    mainViewModel.renameProject(target, newName)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectEntity,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    var showMenu by remember { mutableStateOf(false) }
    val dateLabel = remember(project.lastOpenedAt) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(project.lastOpenedAt))
    }
    val inPlace = project.storageMode == ProjectEntity.STORAGE_LOCAL_PATH
    val locationLabel = if (inPlace) {
        project.localPath ?: project.path
    } else {
        "~/projects/${project.path.substringAfterLast('/')}"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, if (project.pinned) colors.accent.copy(alpha = 0.5f) else colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("project_card_${project.id}")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (project.pinned) {
                        Icon(Icons.Outlined.PushPin, "Pinned", tint = colors.accent, modifier = Modifier.size(14.dp))
                    }
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = locationLabel,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (inPlace) "In place • Updated $dateLabel" else "Synced copy • Updated $dateLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (inPlace) colors.accent else colors.textDim
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, "Project options", tint = colors.textDim)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (project.pinned) "Unpin" else "Pin") },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = { showMenu = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                        onClick = { showMenu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Export ZIP") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = { showMenu = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (inPlace) "Remove from YSCode (keeps folder)" else "Delete", color = colors.danger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = colors.danger) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}
