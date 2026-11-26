// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SkillMetaEntity
import com.example.skills.SkillParser
import com.example.skills.StarterSkills
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.ConfigViewModel
import com.example.ui.viewmodel.MainViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    mainViewModel: MainViewModel,
    configViewModel: ConfigViewModel,
    onBack: () -> Unit,
    onCreateWithAgent: (String) -> Unit
) {
    val colors = LocalYSCodeColors.current
    val activeProject by mainViewModel.activeProject.collectAsStateWithLifecycle()
    val skills by configViewModel.skills.collectAsStateWithLifecycle()
    val pendingCandidate by configViewModel.pendingSkillInstall.collectAsStateWithLifecycle()

    val projectDir = remember(activeProject) { activeProject?.path?.let { File(it) } }

    LaunchedEffect(projectDir) {
        configViewModel.rescanSkills(projectDir)
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0=Installed, 1=Discover
    var scopeFilter by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }

    var editingSkill by remember { mutableStateOf<SkillMetaEntity?>(null) }
    var editingMarkdown by remember { mutableStateOf("") }

    var showInstallSheet by remember { mutableStateOf(false) }
    var showGitHubDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showAgentCreateDialog by remember { mutableStateOf(false) }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) configViewModel.prepareSkillFromZip(uri)
    }

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("skills_back_btn")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                    }
                },
                title = { Text("Skills", color = colors.text) },
                actions = {
                    IconButton(onClick = { configViewModel.rescanSkills(projectDir) }) {
                        Icon(Icons.Outlined.Refresh, "Rescan skills", tint = colors.textDim)
                    }
                    IconButton(
                        onClick = { showInstallSheet = true },
                        modifier = Modifier.testTag("skills_add_btn")
                    ) {
                        Icon(Icons.Outlined.Add, "Install or create skill", tint = colors.accent)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = colors.surface,
                contentColor = colors.accent
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Installed (${skills.size})") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Discover") })
            }

            if (selectedTab == 0) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search skills...") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("all" to "All", "global" to "Global", "project" to "Project").forEach { (id, label) ->
                            FilterChip(
                                selected = scopeFilter == id,
                                onClick = { scopeFilter = id },
                                label = { Text(label) }
                            )
                        }
                    }

                    val filtered = remember(skills, scopeFilter, searchQuery) {
                        skills.filter { s ->
                            (scopeFilter == "all" || s.scope == scopeFilter) &&
                                (searchQuery.isBlank() ||
                                    s.name.contains(searchQuery, ignoreCase = true) ||
                                    s.description.contains(searchQuery, ignoreCase = true))
                        }
                    }

                    if (filtered.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.Extension, null, tint = colors.accent, modifier = Modifier.size(44.dp))
                                Text("No skills match your filter", style = MaterialTheme.typography.titleMedium, color = colors.text)
                                Button(onClick = { showInstallSheet = true }) {
                                    Text("Install or Create Skill")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filtered, key = { it.path }) { skill ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surface,
                                    border = BorderStroke(1.dp, colors.border),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            editingSkill = skill
                                            editingMarkdown = runCatching { File(skill.path).readText() }.getOrDefault("")
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = skill.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.text
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (skill.scope == "project") colors.accent.copy(alpha = 0.16f) else colors.surface2
                                                ) {
                                                    Text(
                                                        text = skill.scope,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (skill.scope == "project") colors.accent else colors.textDim,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = skill.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.textDim,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (skill.allowedTools.isNotBlank()) {
                                                Text(
                                                    text = "Tools: ${skill.allowedTools}",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontSize = 11.sp,
                                                    color = colors.textDim.copy(alpha = 0.75f)
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = skill.enabled,
                                            onCheckedChange = { configViewModel.toggleSkill(skill.path, it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Discover Tab
                LazyColumn(
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.border)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Add Custom Skill", style = MaterialTheme.typography.titleSmall, color = colors.text)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = { showGitHubDialog = true }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Outlined.Link, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("GitHub URL")
                                    }
                                    OutlinedButton(
                                        onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Outlined.UploadFile, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Local ZIP")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = { showPasteDialog = true }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Outlined.Code, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Paste SKILL.md")
                                    }
                                    Button(
                                        onClick = { showAgentCreateDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Ask Agent")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("Bundled Starter Skills", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    }

                    items(StarterSkills.bundled.entries.toList(), key = { it.key }) { (name, md) ->
                        val parsed = remember(md) { SkillParser.parse(md, name).getOrNull() }
                        val alreadyInstalled = skills.any { it.name == name }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.border)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(name, style = MaterialTheme.typography.titleSmall, color = colors.text)
                                    Text(
                                        parsed?.description.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textDim
                                    )
                                }
                                OutlinedButton(onClick = { configViewModel.prepareSkillFromMarkdown(md) }) {
                                    Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (alreadyInstalled) "Reinstall" else "Preview")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Install Options Dialog
    if (showInstallSheet) {
        AlertDialog(
            onDismissRequest = { showInstallSheet = false },
            title = { Text("Install or Create Skill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showInstallSheet = false; showGitHubDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Link, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Install from GitHub URL")
                    }
                    OutlinedButton(
                        onClick = {
                            showInstallSheet = false
                            zipPicker.launch(arrayOf("application/zip", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.UploadFile, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import Skill .zip")
                    }
                    OutlinedButton(
                        onClick = { showInstallSheet = false; showPasteDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Code, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Paste SKILL.md Markdown")
                    }
                    Button(
                        onClick = { showInstallSheet = false; showAgentCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create with Agent")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInstallSheet = false }) { Text("Close") }
            }
        )
    }

    if (showGitHubDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGitHubDialog = false },
            title = { Text("Install Skill from GitHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter a GitHub repo or subfolder URL containing SKILL.md:",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text("https://github.com/user/skill-repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        configViewModel.prepareSkillFromGitHub(url)
                        showGitHubDialog = false
                    },
                    enabled = url.isNotBlank()
                ) { Text("Fetch & Preview") }
            },
            dismissButton = {
                TextButton(onClick = { showGitHubDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPasteDialog) {
        var raw by remember {
            mutableStateOf(
                """
                ---
                name: my-custom-skill
                description: Describe when YSCode should use this skill.
                allowed-tools: read_file, grep, bash
                ---
                # Instructions
                1. Step one
                2. Step two
                """.trimIndent()
            )
        }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Create Skill from Markdown") },
            text = {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                )
            },
            confirmButton = {
                Button(onClick = {
                    configViewModel.prepareSkillFromMarkdown(raw)
                    showPasteDialog = false
                }) { Text("Preview & Install") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAgentCreateDialog) {
        var goal by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAgentCreateDialog = false },
            title = { Text("Create Skill with Agent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Describe what workflow you want YSCode to turn into a reusable skill:", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        placeholder = { Text("e.g. Deploy FastAPI with pytest check") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAgentCreateDialog = false
                        onCreateWithAgent("Use the skill-creator skill to build and register a reusable skill for: $goal")
                    },
                    enabled = goal.isNotBlank()
                ) { Text("Send to Chat") }
            },
            dismissButton = {
                TextButton(onClick = { showAgentCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Skill Detail & Editor Dialog
    editingSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { editingSkill = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = {
                        configViewModel.deleteSkill(skill.path)
                        editingSkill = null
                    }) {
                        Icon(Icons.Outlined.Delete, "Delete skill", tint = colors.danger)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Path: ${skill.path}", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                    OutlinedTextField(
                        value = editingMarkdown,
                        onValueChange = { editingMarkdown = it },
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    configViewModel.saveEditedSkill(skill.path, editingMarkdown, skill.scope, skill.projectId)
                    editingSkill = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingSkill = null }) { Text("Cancel") }
            }
        )
    }

    // Pending Install Approval Preview
    pendingCandidate?.let { candidate ->
        var scopeChoice by remember { mutableStateOf("global") }
        val previewMd = remember(candidate) {
            runCatching { File(candidate.stagingDir, "SKILL.md").readText() }.getOrDefault("")
        }
        AlertDialog(
            onDismissRequest = { configViewModel.dismissSkillCandidate() },
            title = { Text("Install Skill: ${candidate.parsed.name}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Source: ${candidate.sourceLabel}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                    Text(candidate.parsed.description, style = MaterialTheme.typography.bodySmall, color = colors.text)
                    if (candidate.parsed.allowedTools.isNotEmpty()) {
                        Text("Allowed tools: ${candidate.parsed.allowedTools.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = colors.warn)
                    }
                    Text("Files (${candidate.files.size}): ${candidate.files.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = colors.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = scopeChoice == "global",
                            onClick = { scopeChoice = "global" },
                            label = { Text("Global (~/.yscode)") }
                        )
                        FilterChip(
                            selected = scopeChoice == "project",
                            enabled = projectDir != null,
                            onClick = { scopeChoice = "project" },
                            label = { Text("Project Only") }
                        )
                    }
                    HorizontalDivider(color = colors.border)
                    Surface(color = colors.surface2, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = previewMd.take(2500),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = colors.text,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { configViewModel.confirmSkillInstall(scopeChoice, projectDir) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) { Text("Approve & Install") }
            },
            dismissButton = {
                TextButton(onClick = { configViewModel.dismissSkillCandidate() }) { Text("Cancel") }
            }
        )
    }
}
