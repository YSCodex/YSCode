// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.PermissionMode
import com.example.llm.ModelConfig
import com.example.ui.components.HooksManagerDialog
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.UiLanguage
import com.example.ui.viewmodel.ConfigViewModel
import com.example.ui.viewmodel.MainViewModel
import com.example.util.JsonHelper
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    configViewModel: ConfigViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenPermissions: () -> Unit = {}
) {
    val colors = LocalYSCodeColors.current
    val settings by configViewModel.settings.collectAsStateWithLifecycle()
    val providers by configViewModel.providers.collectAsStateWithLifecycle()
    val mcpServers by configViewModel.mcpServers.collectAsStateWithLifecycle()
    val linuxDiag by mainViewModel.linuxDiagnostics.collectAsStateWithLifecycle()
    val allFilesAccess by mainViewModel.allFilesAccess.collectAsStateWithLifecycle()
    val selfTests by mainViewModel.selfTests.collectAsStateWithLifecycle()
    val appContext = LocalContext.current

    LaunchedEffect(Unit) { mainViewModel.refreshLinuxDiagnostics() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mainViewModel.refreshLinuxDiagnostics() }

    var search by remember { mutableStateOf("") }
    fun matches(vararg keywords: String): Boolean =
        search.isBlank() || keywords.any { it.contains(search.trim(), ignoreCase = true) }

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showEnvDialog by remember { mutableStateOf(false) }
    var showPhantomDialog by remember { mutableStateOf(false) }
    var showResetRootfsConfirm by remember { mutableStateOf(false) }
    var showGitDialog by remember { mutableStateOf(false) }
    var showDenylistDialog by remember { mutableStateOf(false) }
    var showGlobalHooksDialog by remember { mutableStateOf(false) }

    val backupExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) configViewModel.exportAppBackup(uri)
    }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) configViewModel.restoreAppBackup(uri)
    }
    val rootfsImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mainViewModel.importRootfsTarGz(uri)
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { mainViewModel.refreshLinuxDiagnostics() }

    val builtInTools = remember { configViewModel.container.toolRegistry.allBuiltInDefinitions() }

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_btn")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                    }
                },
                title = { Text("Settings", color = colors.text) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text("Search settings (providers, agent, hooks, autotest, memory, linux, git)...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 1. Providers & Fallback
            if (matches("providers", "models", "fallback", "llm", "api key")) {
                item {
                    SettingsSectionCard("Providers & Fallback") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = onOpenProviders,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Key, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Manage Providers (${providers.size})")
                            }
                            OutlinedButton(onClick = onOpenSkills) { Text("Skills") }
                            OutlinedButton(onClick = onOpenMcp) { Text("MCP") }
                        }

                        if (providers.isNotEmpty()) {
                            Text("Fallback Provider (failover on rate limit/outage)", style = MaterialTheme.typography.labelMedium, color = colors.textDim)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = settings.fallbackProviderId.isBlank(),
                                    onClick = { configViewModel.updateSettings { it.copy(fallbackProviderId = "", fallbackModel = "") } },
                                    label = { Text("None") }
                                )
                                providers.forEach { p ->
                                    val models = runCatching { JsonHelper.json.decodeFromString<List<ModelConfig>>(p.modelsJson) }.getOrDefault(emptyList())
                                    FilterChip(
                                        selected = settings.fallbackProviderId == p.id,
                                        onClick = {
                                            configViewModel.updateSettings {
                                                it.copy(
                                                    fallbackProviderId = p.id,
                                                    fallbackModel = p.defaultModel.ifBlank { models.firstOrNull()?.id.orEmpty() }
                                                )
                                            }
                                        },
                                        label = { Text(p.name) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Agent Control
            if (matches("agent", "steps", "tokens", "temperature", "reasoning", "compact", "subagent", "autotest", "test", "hooks", "memory", "repomap", "treesitter")) {
                item {
                    SettingsSectionCard("Agent Control & Automation") {
                        Text("Max tool steps per turn: ${settings.maxSteps}", style = MaterialTheme.typography.bodySmall, color = colors.text)
                        Slider(
                            value = settings.maxSteps.toFloat(),
                            onValueChange = { v -> configViewModel.updateSettings { it.copy(maxSteps = v.roundToInt()) } },
                            valueRange = 5f..200f
                        )

                        Text("Max output tokens: ${settings.maxTokensPerTurn}", style = MaterialTheme.typography.bodySmall, color = colors.text)
                        Slider(
                            value = settings.maxTokensPerTurn.toFloat(),
                            onValueChange = { v -> configViewModel.updateSettings { it.copy(maxTokensPerTurn = (v / 256).roundToInt() * 256) } },
                            valueRange = 1024f..32768f
                        )

                        Text("Temperature: ${"%.2f".format(settings.temperature)}", style = MaterialTheme.typography.bodySmall, color = colors.text)
                        Slider(
                            value = settings.temperature.toFloat(),
                            onValueChange = { v -> configViewModel.updateSettings { it.copy(temperature = (v * 20).roundToInt() / 20.0) } },
                            valueRange = 0f..1.5f
                        )

                        Text("Auto-compact context at ${(settings.autoCompactThreshold * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = colors.text)
                        Slider(
                            value = settings.autoCompactThreshold.toFloat(),
                            onValueChange = { v -> configViewModel.updateSettings { it.copy(autoCompactThreshold = (v * 20).roundToInt() / 20.0) } },
                            valueRange = 0.50f..0.95f
                        )

                        Text("Reasoning Effort", style = MaterialTheme.typography.labelMedium, color = colors.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("low", "medium", "high").forEach { eff ->
                                FilterChip(
                                    selected = settings.reasoningEffort == eff,
                                    onClick = { configViewModel.updateSettings { it.copy(reasoningEffort = eff) } },
                                    label = { Text(eff.replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }

                        SettingSwitchRow(
                            title = "Enable Subagents (`task` & `parallel_tasks`)",
                            subtitle = "Delegate explorer, coder & reviewer subagents in parallel or background",
                            checked = settings.subagentsEnabled,
                            onCheckedChange = { c -> configViewModel.updateSettings { it.copy(subagentsEnabled = c) } }
                        )

                        OutlinedTextField(
                            value = settings.subagentModelOverride,
                            onValueChange = { m -> configViewModel.updateSettings { it.copy(subagentModelOverride = m) } },
                            label = { Text("Subagent Model Override (blank = active model)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(color = colors.border)

                        SettingSwitchRow(
                            title = "Auto-Test loop after code edits",
                            subtitle = "Automatically run detected project tests after edits and feed failures back to fix",
                            checked = settings.autoTestAfterEdit,
                            onCheckedChange = { c -> configViewModel.updateSettings { it.copy(autoTestAfterEdit = c) } }
                        )

                        if (settings.autoTestAfterEdit) {
                            Text(
                                "Auto-Test max fix retries: ${settings.autoTestMaxRetries}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.text
                            )
                            Slider(
                                value = settings.autoTestMaxRetries.toFloat(),
                                onValueChange = { v -> configViewModel.updateSettings { it.copy(autoTestMaxRetries = v.roundToInt()) } },
                                valueRange = 0f..5f,
                                steps = 4
                            )
                            OutlinedTextField(
                                value = settings.customTestCommand,
                                onValueChange = { cmd -> configViewModel.updateSettings { it.copy(customTestCommand = cmd) } },
                                label = { Text("Custom test command override (blank = auto-detect)") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        SettingSwitchRow(
                            title = "Auto-Memory (YSCODE.md)",
                            subtitle = "Automatically save user preferences & verified commands into YSCODE.md",
                            checked = settings.autoMemoryEnabled,
                            onCheckedChange = { c -> configViewModel.updateSettings { it.copy(autoMemoryEnabled = c) } }
                        )

                        SettingSwitchRow(
                            title = "Use tree-sitter CLI in Repo Map (when installed)",
                            subtitle = "Pairs fast Kotlin AST symbol indexing with PRoot tree-sitter tags when present",
                            checked = settings.useTreeSitterRepoMap,
                            onCheckedChange = { c -> configViewModel.updateSettings { it.copy(useTreeSitterRepoMap = c) } }
                        )

                        OutlinedButton(
                            onClick = { showGlobalHooksDialog = true },
                            modifier = Modifier.fillMaxWidth().testTag("settings_global_hooks_btn")
                        ) {
                            Text("Global Lifecycle Hooks (${settings.hooks.size})")
                        }
                    }
                }
            }

            // 3. Permissions & Safety
            if (matches("permission", "rules", "mode", "yolo", "protected", "denylist", "safety")) {
                item {
                    SettingsSectionCard("Permissions & Safety") {
                        Text("Default Permission Mode", style = MaterialTheme.typography.labelMedium, color = colors.textDim)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PermissionMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.defaultMode == mode,
                                    onClick = { configViewModel.updateSettings { it.copy(defaultMode = mode) } },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Permission Rules (${settings.permissionRules.size})", style = MaterialTheme.typography.titleSmall, color = colors.text)
                            TextButton(onClick = { showAddRuleDialog = true }) {
                                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                                Text("Add Rule")
                            }
                        }

                        settings.permissionRules.forEach { rule ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${rule.pattern}  →  ${rule.action}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 12.sp,
                                    color = when (rule.action) {
                                        "deny" -> colors.danger
                                        "allow" -> colors.success
                                        else -> colors.text
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { configViewModel.removePermissionRule(rule.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Outlined.Delete, "Delete rule", tint = colors.textDim, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        OutlinedButton(onClick = { showDenylistDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Edit Protected Paths & Command Denylist")
                        }
                    }
                }
            }

            // 4. Tools & Capabilities
            if (matches("tools", "bash", "web_fetch", "web_search", "mcp", "capabilities")) {
                item {
                    SettingsSectionCard("Tools & Network Policy") {
                        SettingSwitchRow(
                            title = "Allow web_search & web_fetch tools",
                            subtitle = "Built-in DuckDuckGo web_search + web_fetch URL reading into context",
                            checked = settings.allowWebFetch,
                            onCheckedChange = { c -> configViewModel.updateSettings { it.copy(allowWebFetch = c) } }
                        )
                        SettingSwitchRow(
                            title = "Allow HTTP/SSE MCP Servers",
                            subtitle = "Permit connections to remote MCP endpoints",
                            checked = settings.allowMcpHttp,
                            onCheckedChange = { c -> configViewModel.updateSettings { it.copy(allowMcpHttp = c) } }
                        )
                        SettingSwitchRow(
                            title = "WakeLock during active runs",
                            subtitle = "Keep CPU awake while builds or agent turns run",
                            checked = settings.wakeLockDuringJobs,
                            onCheckedChange = { c -> configViewModel.updateSettings { it.copy(wakeLockDuringJobs = c) } }
                        )
                        HorizontalDivider(color = colors.border)
                        Text("Built-in Tools", style = MaterialTheme.typography.titleSmall, color = colors.text)
                        builtInTools.forEach { tool ->
                            val enabled = tool.name !in settings.disabledTools
                            SettingSwitchRow(
                                title = tool.name,
                                subtitle = tool.description.take(80),
                                checked = enabled,
                                onCheckedChange = { on ->
                                    configViewModel.updateSettings { s ->
                                        val next = if (on) s.disabledTools - tool.name else s.disabledTools + tool.name
                                        s.copy(disabledTools = next)
                                    }
                                }
                            )
                        }
                        val mcpTools = mcpServers.flatMap { it.tools }
                        if (mcpTools.isNotEmpty()) {
                            HorizontalDivider(color = colors.border)
                            Text("MCP Tools", style = MaterialTheme.typography.titleSmall, color = colors.text)
                            mcpTools.forEach { t ->
                                val enabled = t.qualifiedName !in settings.disabledTools
                                SettingSwitchRow(
                                    title = t.qualifiedName,
                                    subtitle = t.description.take(70),
                                    checked = enabled,
                                    onCheckedChange = { on ->
                                        configViewModel.updateSettings { s ->
                                            val next = if (on) s.disabledTools - t.qualifiedName else s.disabledTools + t.qualifiedName
                                            s.copy(disabledTools = next)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Embedded Linux & Terminal
            if (matches("linux", "proot", "terminal", "rootfs", "self-test", "env", "storage")) {
                item {
                    SettingsSectionCard("Terminal & Embedded Linux") {
                        Text("Terminal font size: ${settings.terminalFontSize}sp", style = MaterialTheme.typography.bodySmall, color = colors.text)
                        Slider(
                            value = settings.terminalFontSize.toFloat(),
                            onValueChange = { v -> configViewModel.updateSettings { it.copy(terminalFontSize = v.roundToInt()) } },
                            valueRange = 9f..22f
                        )

                        linuxDiag?.let { d ->
                            Surface(color = colors.surface2, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Mode: ${d.executionMode}", style = MaterialTheme.typography.bodySmall, color = colors.text)
                                    Text(
                                        "Rootfs: ${d.rootfsBytes / 1024} KB • Projects: ${d.projectsBytes / 1024} KB • PRoot: ${d.prootAvailable}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textDim
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { showEnvDialog = true }, modifier = Modifier.weight(1f)) {
                                Text("Env Vars (${settings.customEnvVars.size})")
                            }
                            OutlinedButton(
                                onClick = { rootfsImporter.launch(arrayOf("application/gzip", "application/x-gzip", "*/*")) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.UploadFile, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Import rootfs")
                            }
                        }

                        OutlinedButton(
                            onClick = onOpenPermissions,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Storage, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (allFilesAccess) "Permissions: all files access granted" else "Permissions (files, notifications, background)")
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { mainViewModel.repairLinux() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Repair")
                            }
                            OutlinedButton(onClick = { showResetRootfsConfirm = true }, modifier = Modifier.weight(1f)) {
                                Text("Reset Rootfs", color = colors.danger)
                            }
                        }

                        Button(
                            onClick = { mainViewModel.runLinuxSelfTest() },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Run Linux Self-Test")
                        }

                        if (selfTests.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.surface2, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                selfTests.forEach { item ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(
                                            if (item.passed) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                                            null,
                                            tint = if (item.passed) colors.success else colors.warn,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            "${item.command} → ${item.output.lines().firstOrNull().orEmpty()}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            color = colors.text
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Git Identity & Appearance & Language
            if (matches("git", "appearance", "theme", "amoled", "dark", "light", "font", "language", "hinglish", "english")) {
                item {
                    SettingsSectionCard("Git, Appearance & Language") {
                        OutlinedButton(onClick = { showGitDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Git Identity (${settings.gitUserName} <${settings.gitUserEmail}>) & PAT Token")
                        }

                        Text("Theme", style = MaterialTheme.typography.labelMedium, color = colors.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { configViewModel.updateSettings { it.copy(themeMode = mode) } },
                                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SettingSwitchRow(
                                title = "Material You Dynamic Color",
                                subtitle = "Use wallpaper accent colors (Android 12+)",
                                checked = settings.dynamicColor,
                                onCheckedChange = { c -> configViewModel.updateSettings { it.copy(dynamicColor = c) } }
                            )
                        }

                        Text("Code & Chat Font Size: ${settings.codeFontSize}sp", style = MaterialTheme.typography.bodySmall, color = colors.text)
                        Slider(
                            value = settings.codeFontSize.toFloat(),
                            onValueChange = { v -> configViewModel.updateSettings { it.copy(codeFontSize = v.roundToInt()) } },
                            valueRange = 10f..20f
                        )

                        Text("Language / Bhasha", style = MaterialTheme.typography.labelMedium, color = colors.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.uiLanguage == UiLanguage.ENGLISH,
                                onClick = { configViewModel.updateSettings { it.copy(uiLanguage = UiLanguage.ENGLISH) } },
                                label = { Text("English") }
                            )
                            FilterChip(
                                selected = settings.uiLanguage == UiLanguage.HINGLISH,
                                onClick = { configViewModel.updateSettings { it.copy(uiLanguage = UiLanguage.HINGLISH) } },
                                label = { Text("Hinglish") }
                            )
                        }
                    }
                }
            }

            // 7. Data Backup & Help & About
            if (matches("backup", "export", "restore", "phantom", "about", "privacy")) {
                item {
                    SettingsSectionCard("Backup, Reliability & About") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { backupExporter.launch("yscode-backup-${System.currentTimeMillis() / 1000}.yscode") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Archive, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export .yscode")
                            }
                            OutlinedButton(
                                onClick = { backupImporter.launch(arrayOf("application/zip", "*/*")) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.Restore, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Restore Backup")
                            }
                        }
                        Text(
                            "Backups include projects, sessions, settings, skills, and configs — API keys and tokens stay protected in Android Keystore and are never exported.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textDim
                        )

                        OutlinedButton(onClick = { showPhantomDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.BatteryAlert, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Android 12+ Phantom Process Killer Guide")
                        }

                        val diagCtx = androidx.compose.ui.platform.LocalContext.current
                        OutlinedButton(
                            onClick = {
                                val env = (diagCtx.applicationContext as com.example.YSCodeApplication).container.linuxEnv
                                val report = com.example.util.CrashReporter.buildReport(diagCtx, env)
                                val cm = diagCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("YSCode diagnostics", report))
                                android.widget.Toast.makeText(diagCtx, "Diagnostic report copied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Archive, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy diagnostic report (for bug reports)")
                        }

                        val telem = remember { com.example.update.Telemetry(diagCtx.applicationContext) }
                        var statsOn by remember { mutableStateOf(telem.enabled) }
                        var showUpdateDialog by remember { mutableStateOf(false) }
                        var showFeedbackDialog by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { showUpdateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Check for updates")
                        }
                        OutlinedButton(onClick = { showFeedbackDialog = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Send feedback or report a bug")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Share anonymous usage stats", style = MaterialTheme.typography.titleSmall, color = colors.text)
                                Text(
                                    "Off by default. Daily: random ID, app and Android version, CPU type. Never chats, code or keys.",
                                    style = MaterialTheme.typography.bodySmall, color = colors.textDim
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = statsOn,
                                onCheckedChange = { statsOn = it; telem.enabled = it; telem.consentAsked = true }
                            )
                        }
                        if (showUpdateDialog) com.example.ui.components.UpdateDialog { showUpdateDialog = false }
                        if (showFeedbackDialog) com.example.ui.components.FeedbackDialog { showFeedbackDialog = false }

                        Surface(color = colors.surface2, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("YSCode v${com.example.util.CrashReporter.versionName(diagCtx)} • 100% On-Device", style = MaterialTheme.typography.titleSmall, color = colors.text)
                                Text(
                                    "Zero telemetry, zero cloud accounts. Connections occur strictly to your configured LLM endpoints, MCP servers, and GitHub URLs.",
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

    if (showAddRuleDialog) {
        var pattern by remember { mutableStateOf("Bash(pytest:*)") }
        var action by remember { mutableStateOf("allow") }
        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            title = { Text("Add Permission Rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = { Text("Pattern (e.g. Bash(npm test:*), Write(src/**))") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("allow", "ask", "deny").forEach { a ->
                            FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { configViewModel.addPermissionRule(pattern, action); showAddRuleDialog = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddRuleDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDenylistDialog) {
        var pathsText by remember { mutableStateOf(settings.protectedPaths.joinToString("\n")) }
        var denyText by remember { mutableStateOf(settings.commandDenylist.joinToString("\n")) }
        AlertDialog(
            onDismissRequest = { showDenylistDialog = false },
            title = { Text("Protected Paths & Denylist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pathsText,
                        onValueChange = { pathsText = it },
                        label = { Text("Protected Paths (one per line)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = denyText,
                        onValueChange = { denyText = it },
                        label = { Text("Blocked Command Substrings (one per line)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    configViewModel.updateSettings {
                        it.copy(
                            protectedPaths = pathsText.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() },
                            commandDenylist = denyText.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }
                        )
                    }
                    showDenylistDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showDenylistDialog = false }) { Text("Cancel") } }
        )
    }

    if (showEnvDialog) {
        var envText by remember {
            mutableStateOf(settings.customEnvVars.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
        AlertDialog(
            onDismissRequest = { showEnvDialog = false },
            title = { Text("Custom Shell Env Vars") },
            text = {
                OutlinedTextField(
                    value = envText,
                    onValueChange = { envText = it },
                    label = { Text("KEY=VALUE per line") },
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val map = envText.lines().mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
                    }.toMap()
                    configViewModel.updateSettings { it.copy(customEnvVars = map) }
                    showEnvDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showEnvDialog = false }) { Text("Cancel") } }
        )
    }

    if (showGitDialog) {
        var gitName by remember { mutableStateOf(settings.gitUserName) }
        var gitEmail by remember { mutableStateOf(settings.gitUserEmail) }
        var gitPat by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGitDialog = false },
            title = { Text("Git Identity & PAT") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = gitName, onValueChange = { gitName = it }, label = { Text("git user.name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = gitEmail, onValueChange = { gitEmail = it }, label = { Text("git user.email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = gitPat,
                        onValueChange = { gitPat = it },
                        label = { Text("GitHub PAT (leave blank to keep existing)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    configViewModel.updateSettings { it.copy(gitUserName = gitName.trim(), gitUserEmail = gitEmail.trim()) }
                    if (gitPat.isNotBlank()) configViewModel.saveGitToken(gitPat)
                    showGitDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showGitDialog = false }) { Text("Cancel") } }
        )
    }

    if (showResetRootfsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetRootfsConfirm = false },
            title = { Text("Reset Embedded Rootfs?") },
            text = { Text("Rebuilds the Linux root filesystem directories and starter skills. Your project files will be preserved.") },
            confirmButton = {
                Button(
                    onClick = { mainViewModel.resetLinux(); showResetRootfsConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger)
                ) { Text("Reset Rootfs") }
            },
            dismissButton = { TextButton(onClick = { showResetRootfsConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showPhantomDialog) {
        AlertDialog(
            onDismissRequest = { showPhantomDialog = false },
            title = { Text("Android 12+ Phantom Process Killer") },
            text = {
                Text(
                    "Android 12+ limits total background child processes to 32 across apps.\n\n" +
                        "Recommended steps:\n" +
                        "1. Set YSCode Battery usage to Unrestricted in Android Settings.\n" +
                        "2. On Android 14+, enable Developer Options -> Disable child process restrictions.\n" +
                        "3. Keep 'WakeLock during active runs' enabled above."
                )
            },
            confirmButton = { TextButton(onClick = { showPhantomDialog = false }) { Text("Got it") } }
        )
    }

    if (showGlobalHooksDialog) {
        HooksManagerDialog(
            initialHooks = settings.hooks,
            title = "Global Lifecycle Hooks",
            onSave = { updated ->
                configViewModel.updateSettings { it.copy(hooks = updated) }
            },
            onDismiss = { showGlobalHooksDialog = false }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalYSCodeColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.text)
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalYSCodeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.textDim)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
