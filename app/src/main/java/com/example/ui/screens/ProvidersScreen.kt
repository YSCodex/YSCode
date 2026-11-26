// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.llm.ModelConfig
import com.example.llm.ProviderPreset
import com.example.llm.ProviderPresets
import com.example.llm.ProviderType
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.ConfigViewModel
import com.example.util.JsonHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    configViewModel: ConfigViewModel,
    onBack: () -> Unit,
    onEditProvider: (providerId: String?, presetId: String?) -> Unit,
    onOpenCodex: () -> Unit = {},
    onOpenAntigravity: () -> Unit = {}
) {
    val colors = LocalYSCodeColors.current
    val providers by configViewModel.providers.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("providers_back_btn")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                    }
                },
                title = { Text("LLM Providers", color = colors.text) },
                actions = {
                    IconButton(onClick = { onEditProvider(null, null) }, modifier = Modifier.testTag("providers_add_btn")) {
                        Icon(Icons.Outlined.Add, "Add Custom Provider", tint = colors.accent)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenCodex() }
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Use your plan: ChatGPT (Codex)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.text)
                        Text("Sign in with ChatGPT Plus/Pro, switch accounts and see plan limits. No API key needed.", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                    }
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenAntigravity() }
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Use your plan: Antigravity (Google)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.text)
                        Text("Install the official agy CLI, sign in with Google, switch accounts and check usage.", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                    }
                }
            }
            if (providers.isNotEmpty()) {
                item {
                    Text("Configured Providers", style = MaterialTheme.typography.titleSmall, color = colors.text)
                }
                items(providers, key = { it.id }) { p ->
                    val models = remember(p.modelsJson) {
                        runCatching { JsonHelper.json.decodeFromString<List<ModelConfig>>(p.modelsJson) }.getOrDefault(emptyList())
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, if (p.isDefault) colors.accent else colors.border),
                        modifier = Modifier.fillMaxWidth().clickable { onEditProvider(p.id, null) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Outlined.Key, null, tint = if (p.isDefault) colors.accent else colors.textDim)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.text)
                                    if (p.isDefault) {
                                        Surface(color = colors.accent.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
                                            Text("Default", style = MaterialTheme.typography.labelSmall, color = colors.accent, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Text(p.baseUrl, style = MaterialTheme.typography.bodySmall, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${p.type} • ${models.size} models • default: ${p.defaultModel.ifBlank { "none" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textDim.copy(alpha = 0.75f)
                                )
                            }
                            IconButton(onClick = { configViewModel.deleteProvider(p) }) {
                                Icon(Icons.Outlined.Delete, "Delete provider", tint = colors.danger)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text("Quick-Add Presets", style = MaterialTheme.typography.titleSmall, color = colors.text)
            }

            items(ProviderPresets.all.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.border),
                            modifier = Modifier.weight(1f).clickable { onEditProvider(null, preset.id) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(preset.name, style = MaterialTheme.typography.titleSmall, color = colors.text)
                                Text(preset.type.label, style = MaterialTheme.typography.labelSmall, color = colors.accent)
                                Text(preset.baseUrl, style = MaterialTheme.typography.labelSmall, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderEditScreen(
    configViewModel: ConfigViewModel,
    providerId: String?,
    presetId: String?,
    onBack: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    val providers by configViewModel.providers.collectAsStateWithLifecycle()
    val testResult by configViewModel.testResult.collectAsStateWithLifecycle()
    val isTesting by configViewModel.isTesting.collectAsStateWithLifecycle()

    val existing = remember(providers, providerId) { providers.find { it.id == providerId } }
    val preset: ProviderPreset? = remember(presetId) { ProviderPresets.all.find { it.id == presetId } }

    LaunchedEffect(Unit) {
        configViewModel.clearTestResult()
    }

    var name by remember(existing, preset) { mutableStateOf(existing?.name ?: preset?.name ?: "Custom Provider") }
    var type by remember(existing, preset) {
        mutableStateOf(
            existing?.let { runCatching { ProviderType.valueOf(it.type) }.getOrNull() }
                ?: preset?.type
                ?: ProviderType.OPENAI_COMPAT
        )
    }
    var baseUrl by remember(existing, preset) { mutableStateOf(existing?.baseUrl ?: preset?.baseUrl ?: "https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var headersText by remember(existing) {
        val map = existing?.let {
            runCatching { JsonHelper.json.decodeFromString<Map<String, String>>(it.headersJson) }.getOrDefault(emptyMap())
        }.orEmpty()
        mutableStateOf(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var models by remember(existing, preset) {
        val initial = existing?.let {
            runCatching { JsonHelper.json.decodeFromString<List<ModelConfig>>(it.modelsJson) }.getOrNull()
        } ?: preset?.defaultModels ?: listOf(ModelConfig("gpt-4o", "GPT-4o"))
        mutableStateOf(initial)
    }
    var defaultModel by remember(existing, preset) {
        mutableStateOf(existing?.defaultModel ?: preset?.defaultModels?.firstOrNull()?.id ?: "gpt-4o")
    }
    var setAsDefault by remember(existing) { mutableStateOf(existing?.isDefault ?: providers.isEmpty()) }
    var showAddModelDialog by remember { mutableStateOf(false) }

    // Search, filter & quick custom Model ID entry
    var modelSearch by remember { mutableStateOf("") }
    var quickModelId by remember { mutableStateOf("") }
    var filterTools by remember { mutableStateOf(false) }
    var filterReasoning by remember { mutableStateOf(false) }
    var filterVision by remember { mutableStateOf(false) }

    val filteredModels = remember(models, modelSearch, filterTools, filterReasoning, filterVision, defaultModel) {
        val q = modelSearch.trim().lowercase()
        models
            .filter { m ->
                val matchesQ = q.isEmpty() || m.id.lowercase().contains(q) || m.displayName.lowercase().contains(q)
                val matchesT = !filterTools || m.supportsTools
                val matchesR = !filterReasoning || m.supportsReasoning
                val matchesV = !filterVision || com.example.llm.VisionSupport.effective(m.id, m.supportsVision)
                matchesQ && matchesT && matchesR && matchesV
            }
            .sortedByDescending { it.id == defaultModel }
    }

    fun parsedHeaders(): Map<String, String> = headersText.lines().mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
    }.toMap()

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                    }
                },
                title = { Text(if (existing == null) "Add Provider" else "Edit ${existing.name}", color = colors.text) },
                actions = {
                    TextButton(
                        onClick = {
                            configViewModel.saveProvider(
                                existingId = existing?.id,
                                name = name,
                                type = type,
                                baseUrl = baseUrl,
                                apiKeyPlain = apiKey,
                                headers = parsedHeaders(),
                                models = models,
                                defaultModel = defaultModel,
                                setAsDefault = setAsDefault
                            )
                            onBack()
                        },
                        modifier = Modifier.testTag("provider_save_btn")
                    ) {
                        Text("Save", color = colors.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Protocol Type", style = MaterialTheme.typography.labelLarge, color = colors.text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProviderType.entries.filter { it != ProviderType.CODEX && it != ProviderType.AGY }.forEach { t ->
                            FilterChip(
                                selected = type == t,
                                onClick = { type = t },
                                label = { Text(t.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(
                            if (existing != null) "API Key (leave blank to keep Keystore key)"
                            else "API Key (${preset?.hint ?: "optional for LAN"})"
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Toggle key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text("Custom Headers (Header-Name=Value per line)") },
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = setAsDefault, onCheckedChange = { setAsDefault = it })
                    Text("Set as default active provider", style = MaterialTheme.typography.bodyMedium, color = colors.text)
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            configViewModel.testProviderConnection(
                                type = type,
                                baseUrl = baseUrl,
                                apiKeyPlain = apiKey,
                                existingSecretAlias = existing?.secretAlias,
                                headers = parsedHeaders(),
                                modelId = defaultModel.ifBlank { models.firstOrNull()?.id },
                                onModelsFetched = { fetched ->
                                    val merged = (models + fetched).distinctBy { it.id }
                                    models = merged
                                    if (defaultModel.isBlank()) defaultModel = merged.firstOrNull()?.id.orEmpty()
                                }
                            )
                        },
                        enabled = !isTesting,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTesting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test & Fetch Models")
                        }
                    }
                    OutlinedButton(onClick = { showAddModelDialog = true }) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Advanced Add")
                    }
                }
            }

            testResult?.let { res ->
                item {
                    val displayMsg = if (res.message.contains("ms)")) {
                        res.message
                    } else {
                        "${res.message} (${res.latencyMs}ms)"
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = (if (res.success) colors.success else colors.danger).copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = displayMsg,
                            color = if (res.success) colors.success else colors.danger,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            item {
                HorizontalDivider(color = colors.border)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Models (${filteredModels.size}/${models.size}) — active: ${defaultModel.ifBlank { "none" }}",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.text
                    )

                    // Direct quick model ID entry
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = quickModelId,
                            onValueChange = { quickModelId = it },
                            placeholder = { Text("Type Model ID directly (e.g. openai/gpt-4.1)") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("provider_quick_model_input")
                        )
                        Button(
                            onClick = {
                                val idClean = quickModelId.trim()
                                val cfg = ModelConfig(id = idClean, displayName = idClean)
                                models = listOf(cfg) + models.filterNot { it.id == idClean }
                                defaultModel = idClean
                                quickModelId = ""
                            },
                            enabled = quickModelId.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.testTag("provider_quick_model_btn")
                        ) {
                            Text("Set ID")
                        }
                    }

                    // Search & filter models
                    OutlinedTextField(
                        value = modelSearch,
                        onValueChange = { modelSearch = it },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (modelSearch.isNotEmpty()) {
                                IconButton(onClick = { modelSearch = "" }) {
                                    Icon(Icons.Outlined.Close, "Clear search")
                                }
                            }
                        },
                        placeholder = { Text("Search models by ID or name...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_model_search")
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = filterTools, onClick = { filterTools = !filterTools }, label = { Text("Tools") })
                        FilterChip(selected = filterReasoning, onClick = { filterReasoning = !filterReasoning }, label = { Text("Reasoning") })
                        FilterChip(selected = filterVision, onClick = { filterVision = !filterVision }, label = { Text("Vision") })
                    }
                }
            }

            items(filteredModels, key = { it.id }) { m ->
                val isDef = m.id == defaultModel
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDef) colors.accent.copy(alpha = 0.12f) else colors.surface,
                    border = BorderStroke(1.dp, if (isDef) colors.accent else colors.border),
                    modifier = Modifier.fillMaxWidth().clickable { defaultModel = m.id }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDef) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.id, fontFamily = JetBrainsMonoFontFamily, fontSize = 13.sp, color = colors.text)
                            Text(
                                "${m.contextWindow / 1000}k ctx • tools:${m.supportsTools} • vision:${com.example.llm.VisionSupport.effective(m.id, m.supportsVision)} • reasoning:${m.supportsReasoning}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textDim
                            )
                        }
                        if (models.size > 1) {
                            IconButton(onClick = {
                                models = models.filterNot { it.id == m.id }
                                if (defaultModel == m.id) defaultModel = models.firstOrNull()?.id.orEmpty()
                            }) {
                                Icon(Icons.Outlined.Delete, "Remove model", tint = colors.textDim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddModelDialog) {
        var modelId by remember { mutableStateOf("") }
        var ctxWindowText by remember { mutableStateOf("128000") }
        var supportsTools by remember { mutableStateOf(true) }
        var supportsVision by remember { mutableStateOf(true) }
        var supportsReasoning by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddModelDialog = false },
            title = { Text("Add Model ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text("Model ID (e.g. qwen2.5-coder:32b)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ctxWindowText,
                        onValueChange = { ctxWindowText = it },
                        label = { Text("Context Window Tokens") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = supportsTools, onCheckedChange = { supportsTools = it })
                        Text("Supports Tool Calling")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = supportsVision, onCheckedChange = { supportsVision = it })
                        Text("Supports Vision / Images")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = supportsReasoning, onCheckedChange = { supportsReasoning = it })
                        Text("Reasoning / Thinking Model")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idClean = modelId.trim()
                        val ctx = ctxWindowText.toIntOrNull()?.coerceAtLeast(4096) ?: 128_000
                        val cfg = ModelConfig(
                            id = idClean,
                            displayName = idClean,
                            contextWindow = ctx,
                            supportsTools = supportsTools,
                            supportsVision = supportsVision,
                            supportsReasoning = supportsReasoning
                        )
                        models = listOf(cfg) + models.filterNot { it.id == idClean }
                        defaultModel = idClean
                        showAddModelDialog = false
                    },
                    enabled = modelId.isNotBlank()
                ) { Text("Add & Select") }
            },
            dismissButton = {
                TextButton(onClick = { showAddModelDialog = false }) { Text("Cancel") }
            }
        )
    }
}
