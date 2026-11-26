// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ProviderEntity
import com.example.llm.ModelConfig
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.util.JsonHelper

data class ProviderModelEntry(
    val providerId: String,
    val providerName: String,
    val model: ModelConfig
)

fun filterProviderModels(
    providers: List<ProviderEntity>,
    query: String,
    selectedProviderId: String? = null,
    onlyTools: Boolean = false,
    onlyReasoning: Boolean = false
): List<ProviderModelEntry> {
    val q = query.trim().lowercase()
    return providers
        .asSequence()
        .filter { selectedProviderId == null || it.id == selectedProviderId }
        .flatMap { prov ->
            val models = runCatching {
                JsonHelper.json.decodeFromString<List<ModelConfig>>(prov.modelsJson)
            }.getOrDefault(emptyList())
            val list = models.ifEmpty {
                listOfNotNull(prov.defaultModel.takeIf { it.isNotBlank() }?.let { ModelConfig(it) })
            }
            list.asSequence().map { m -> ProviderModelEntry(prov.id, prov.name, m) }
        }
        .filter { entry ->
            val m = entry.model
            val matchesQuery = q.isEmpty() ||
                m.id.lowercase().contains(q) ||
                m.displayName.lowercase().contains(q) ||
                entry.providerName.lowercase().contains(q)
            val matchesTools = !onlyTools || m.supportsTools
            val matchesReasoning = !onlyReasoning || m.supportsReasoning
            matchesQuery && matchesTools && matchesReasoning
        }
        .toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderEntity>,
    activeProviderId: String?,
    activeModelId: String,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onUseCustomModelId: (providerId: String, customModelId: String) -> Unit,
    onManageProviders: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    var search by remember { mutableStateOf("") }
    var customModelId by remember { mutableStateOf("") }
    var selectedProvFilter by remember { mutableStateOf<String?>(null) }
    var onlyTools by remember { mutableStateOf(false) }
    var onlyReasoning by remember { mutableStateOf(false) }

    val filtered = remember(providers, search, selectedProvFilter, onlyTools, onlyReasoning) {
        filterProviderModels(providers, search, selectedProvFilter, onlyTools, onlyReasoning)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.text)
                TextButton(onClick = { onDismiss(); onManageProviders() }) {
                    Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Providers")
                }
            }

            if (providers.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface2,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No LLM providers configured yet.", style = MaterialTheme.typography.bodyMedium, color = colors.text)
                        Button(
                            onClick = { onDismiss(); onManageProviders() },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Add Provider")
                        }
                    }
                }
            } else {
                // Search models across providers
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) {
                                Icon(Icons.Outlined.Close, "Clear search")
                            }
                        }
                    },
                    placeholder = { Text("Search ${providers.size} provider(s) models...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_picker_search")
                )

                // Direct custom Model ID entry
                val targetProvId = selectedProvFilter
                    ?: activeProviderId?.takeIf { id -> providers.any { it.id == id } }
                    ?: providers.first().id
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customModelId,
                        onValueChange = { customModelId = it },
                        placeholder = { Text("Enter Model ID directly (e.g. openai/gpt-4.1)") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("model_picker_custom_id_input")
                    )
                    Button(
                        onClick = {
                            onUseCustomModelId(targetProvId, customModelId)
                            onDismiss()
                        },
                        enabled = customModelId.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.testTag("model_picker_custom_id_btn")
                    ) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Use")
                    }
                }

                // Filter chips
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (providers.size > 1) {
                        item {
                            FilterChip(
                                selected = selectedProvFilter == null,
                                onClick = { selectedProvFilter = null },
                                label = { Text("All (${providers.size})") }
                            )
                        }
                        items(providers, key = { it.id }) { p ->
                            FilterChip(
                                selected = selectedProvFilter == p.id,
                                onClick = { selectedProvFilter = if (selectedProvFilter == p.id) null else p.id },
                                label = { Text(p.name) }
                            )
                        }
                    }
                    item {
                        FilterChip(
                            selected = onlyTools,
                            onClick = { onlyTools = !onlyTools },
                            label = { Text("Tools") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = onlyReasoning,
                            onClick = { onlyReasoning = !onlyReasoning },
                            label = { Text("Reasoning") }
                        )
                    }
                }

                HorizontalDivider(color = colors.border)

                Text(
                    text = "Showing ${filtered.size} model(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textDim
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 380.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { "${it.providerId}_${it.model.id}" }) { entry ->
                        val isActive = entry.providerId == activeProviderId && entry.model.id == activeModelId
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isActive) colors.accent.copy(alpha = 0.14f) else colors.bg,
                            border = BorderStroke(1.dp, if (isActive) colors.accent else colors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectModel(entry.providerId, entry.model.id)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isActive) {
                                    Icon(Icons.Outlined.CheckCircle, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.model.id,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 13.sp,
                                        color = if (isActive) colors.accent else colors.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${entry.providerName} • ${entry.model.contextWindow / 1000}k ctx" +
                                            (if (entry.model.supportsTools) " • Tools" else "") +
                                            (if (com.example.llm.VisionSupport.effective(entry.model.id, entry.model.supportsVision)) " • Vision" else "") +
                                            (if (entry.model.supportsReasoning) " • Reasoning" else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = "No matching models. Enter a custom Model ID above to use it directly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textDim,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
