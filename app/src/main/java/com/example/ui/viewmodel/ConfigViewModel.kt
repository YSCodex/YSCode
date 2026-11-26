// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.AppSettings
import com.example.data.McpServerEntity
import com.example.data.PermissionRule
import com.example.data.ProviderEntity
import com.example.data.SkillMetaEntity
import com.example.llm.ConnectionTestResult
import com.example.llm.ModelConfig
import com.example.llm.ProviderPreset
import com.example.llm.ProviderType
import com.example.mcp.McpConfigParser
import com.example.mcp.McpServerConfig
import com.example.skills.SkillInstallCandidate
import com.example.util.JsonHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ConfigViewModel(
    val container: AppContainer
) : ViewModel() {

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    val settings: StateFlow<AppSettings> = container.settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val providers: StateFlow<List<ProviderEntity>> = container.database.providerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val skills: StateFlow<List<SkillMetaEntity>> = container.skillRepository.observeSkills()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mcpServers = container.mcpRegistry.serverStates

    private val _testResult = MutableStateFlow<ConnectionTestResult?>(null)
    val testResult: StateFlow<ConnectionTestResult?> = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _pendingSkillInstall = MutableStateFlow<SkillInstallCandidate?>(null)
    val pendingSkillInstall: StateFlow<SkillInstallCandidate?> = _pendingSkillInstall.asStateFlow()

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            container.settingsStore.update(transform)
        }
    }

    fun saveProvider(
        existingId: String?,
        name: String,
        type: ProviderType,
        baseUrl: String,
        apiKeyPlain: String,
        headers: Map<String, String>,
        models: List<ModelConfig>,
        defaultModel: String,
        setAsDefault: Boolean
    ) {
        viewModelScope.launch {
            val id = existingId ?: "prov_" + UUID.randomUUID().toString().take(8)
            val alias = "provider_key_$id"
            if (apiKeyPlain.isNotBlank()) {
                container.secretStore.putSecret(alias, apiKeyPlain.trim())
            }
            if (setAsDefault) {
                container.database.providerDao().clearDefaults()
            }
            val resolvedDefault = defaultModel.ifBlank { models.firstOrNull()?.id.orEmpty() }
            val entity = ProviderEntity(
                id = id,
                name = name.trim().ifBlank { type.label },
                type = type.name,
                baseUrl = baseUrl.trim(),
                secretAlias = alias,
                headersJson = JsonHelper.json.encodeToString(headers),
                modelsJson = JsonHelper.json.encodeToString(models),
                defaultModel = resolvedDefault,
                isDefault = setAsDefault || providers.value.isEmpty()
            )
            container.database.providerDao().upsert(entity)
            if (entity.isDefault || settings.value.activeProviderId.isBlank()) {
                container.settingsStore.update { it.copy(activeProviderId = entity.id) }
            }
            _events.tryEmit("Saved provider ${entity.name}")
        }
    }

    fun savePresetQuick(preset: ProviderPreset, apiKey: String, customBaseUrl: String = preset.baseUrl) {
        saveProvider(
            existingId = null,
            name = preset.name,
            type = preset.type,
            baseUrl = customBaseUrl,
            apiKeyPlain = apiKey,
            headers = emptyMap(),
            models = preset.defaultModels,
            defaultModel = preset.defaultModels.firstOrNull()?.id.orEmpty(),
            setAsDefault = true
        )
    }

    /** Creates or refreshes the "ChatGPT Plan (Codex)" provider after a sign-in, with the models the plan offers. */
    fun syncCodexProvider() {
        viewModelScope.launch {
            val models = com.example.llm.CodexClient(container.codexAuth).fetchModels().getOrElse { com.example.llm.CodexClient.fallbackModels }
            val existing = container.database.providerDao().getById("prov_codex")
            val keep = existing?.defaultModel?.takeIf { d -> models.any { it.id == d } }
            saveProvider(
                existingId = "prov_codex",
                name = "ChatGPT Plan (Codex)",
                type = ProviderType.CODEX,
                baseUrl = com.example.llm.CodexAuth.BASE_URL,
                apiKeyPlain = "",
                headers = emptyMap(),
                models = models,
                defaultModel = keep ?: models.first().id,
                setAsDefault = existing == null && providers.value.none { it.isDefault }
            )
        }
    }

    /** Creates or refreshes the "Antigravity (agy)" provider with the models `agy models` reports. */
    fun syncAgyProvider() {
        viewModelScope.launch {
            val models = com.example.llm.AgyClient(container.agyManager).fetchModels().getOrElse { emptyList() }
                .ifEmpty { listOf(ModelConfig("default", "Antigravity default model", 1_000_000, true, false, true)) }
            val existing = container.database.providerDao().getById("prov_agy")
            val keep = existing?.defaultModel?.takeIf { d -> models.any { it.id == d } }
            saveProvider(
                existingId = "prov_agy",
                name = "Antigravity (agy)",
                type = ProviderType.AGY,
                baseUrl = "agy://local",
                apiKeyPlain = "",
                headers = emptyMap(),
                models = models,
                defaultModel = keep ?: models.first().id,
                setAsDefault = false
            )
        }
    }

    fun deleteProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            container.secretStore.deleteSecret(provider.secretAlias)
            container.database.providerDao().deleteById(provider.id)
            _events.tryEmit("Deleted ${provider.name}")
        }
    }

    fun testProviderConnection(
        type: ProviderType,
        baseUrl: String,
        apiKeyPlain: String,
        existingSecretAlias: String?,
        headers: Map<String, String>,
        modelId: String?,
        onModelsFetched: (List<ModelConfig>) -> Unit = {}
    ) {
        viewModelScope.launch {
            _isTesting.value = true
            _testResult.value = null
            val resolvedKey = apiKeyPlain.ifBlank {
                container.secretStore.getSecret(existingSecretAlias).orEmpty()
            }
            val tempEntity = ProviderEntity(
                id = "temp",
                name = "Test",
                type = type.name,
                baseUrl = baseUrl.trim(),
                secretAlias = "",
                headersJson = JsonHelper.json.encodeToString(headers)
            )
            val client = when (type) {
                ProviderType.OPENAI_COMPAT -> com.example.llm.OpenAiClient(tempEntity.baseUrl, resolvedKey, headers)
                ProviderType.ANTHROPIC -> com.example.llm.AnthropicClient(tempEntity.baseUrl, resolvedKey, headers)
                ProviderType.GEMINI -> com.example.llm.GeminiClient(tempEntity.baseUrl, resolvedKey, headers)
                ProviderType.CODEX -> com.example.llm.CodexClient(container.codexAuth)
                ProviderType.AGY -> com.example.llm.AgyClient(container.agyManager)
            }
            val res = client.testConnection(modelId)
            _testResult.value = res
            if (res.success && res.discoveredModels.isNotEmpty()) {
                onModelsFetched(res.discoveredModels)
            }
            _isTesting.value = false
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    // --- Skills ---
    fun rescanSkills(projectDir: File?) {
        viewModelScope.launch { container.skillRepository.rescan(projectDir) }
    }

    fun toggleSkill(path: String, enabled: Boolean) {
        viewModelScope.launch { container.skillRepository.toggleSkill(path, enabled) }
    }

    fun deleteSkill(path: String) {
        viewModelScope.launch {
            container.skillRepository.deleteSkill(path)
            _events.tryEmit("Skill deleted")
        }
    }

    fun saveEditedSkill(skillFilePath: String, markdown: String, scope: String, projectId: String?) {
        viewModelScope.launch {
            val dir = File(skillFilePath).parentFile ?: return@launch
            container.skillRepository.saveSkillContent(dir, markdown, scope, projectId).fold(
                onSuccess = { _events.tryEmit("Saved skill ${it.name}") },
                onFailure = { _events.tryEmit("Invalid SKILL.md: ${it.message}") }
            )
        }
    }

    fun prepareSkillFromGitHub(url: String) {
        viewModelScope.launch {
            _events.tryEmit("Downloading skill from GitHub...")
            container.skillInstaller.prepareFromGitHubUrl(url).fold(
                onSuccess = { _pendingSkillInstall.value = it },
                onFailure = { _events.tryEmit("GitHub import failed: ${it.message}") }
            )
        }
    }

    fun prepareSkillFromZip(uri: Uri) {
        viewModelScope.launch {
            container.skillInstaller.prepareFromZipUri(uri).fold(
                onSuccess = { _pendingSkillInstall.value = it },
                onFailure = { _events.tryEmit("ZIP import failed: ${it.message}") }
            )
        }
    }

    fun prepareSkillFromMarkdown(markdown: String) {
        viewModelScope.launch {
            container.skillInstaller.prepareFromMarkdown(markdown).fold(
                onSuccess = { _pendingSkillInstall.value = it },
                onFailure = { _events.tryEmit("Markdown invalid: ${it.message}") }
            )
        }
    }

    fun confirmSkillInstall(scope: String, projectDir: File?) {
        val candidate = _pendingSkillInstall.value ?: return
        viewModelScope.launch {
            container.skillInstaller.commitInstall(candidate, scope, projectDir).fold(
                onSuccess = {
                    _pendingSkillInstall.value = null
                    _events.tryEmit("Installed skill '${it.name}'")
                },
                onFailure = { _events.tryEmit("Install failed: ${it.message}") }
            )
        }
    }

    fun dismissSkillCandidate() {
        _pendingSkillInstall.value = null
    }

    // --- MCP ---
    fun addMcpServer(
        name: String,
        transport: String,
        command: String,
        argsText: String,
        url: String,
        envMap: Map<String, String>,
        headersMap: Map<String, String>,
        scope: String,
        projectDir: File?
    ) {
        viewModelScope.launch {
            val args = argsText.split(Regex("\\s+")).filter { it.isNotBlank() }
            container.mcpRegistry.addOrUpdateServer(
                name = McpConfigParser.sanitizeMcpName(name),
                transport = transport,
                scopeStr = scope,
                projectId = if (scope == "project") projectDir?.name else null,
                config = McpServerConfig(
                    command = command.trim(),
                    args = args,
                    env = envMap.mapValues { "***" },
                    url = url.trim(),
                    headers = headersMap.mapValues { "***" }
                ),
                secretEnvValues = envMap,
                secretHeaderValues = headersMap,
                workingDir = projectDir
            )
            _events.tryEmit("Added MCP server '$name'")
        }
    }

    fun importMcpJson(rawJson: String, scope: String, projectDir: File?) {
        viewModelScope.launch {
            McpConfigParser.parseMcpServersJson(rawJson).fold(
                onSuccess = { entries ->
                    entries.forEach { entry ->
                        container.mcpRegistry.addOrUpdateServer(
                            name = entry.name,
                            transport = entry.transport,
                            scopeStr = scope,
                            projectId = if (scope == "project") projectDir?.name else null,
                            config = entry.config,
                            secretEnvValues = entry.config.env,
                            secretHeaderValues = entry.config.headers,
                            workingDir = projectDir
                        )
                    }
                    _events.tryEmit("Imported ${entries.size} MCP server(s)")
                },
                onFailure = { _events.tryEmit("JSON parse error: ${it.message}") }
            )
        }
    }

    fun toggleMcpServer(serverId: String, enabled: Boolean, projectDir: File?) {
        viewModelScope.launch { container.mcpRegistry.toggleServer(serverId, enabled, projectDir) }
    }

    fun toggleMcpTool(serverId: String, toolName: String, enabled: Boolean) {
        viewModelScope.launch { container.mcpRegistry.toggleTool(serverId, toolName, enabled) }
    }

    fun restartMcpServer(serverId: String, projectDir: File?) {
        container.mcpRegistry.restartServer(serverId, projectDir)
    }

    fun removeMcpServer(serverId: String) {
        viewModelScope.launch { container.mcpRegistry.removeServer(serverId) }
    }

    // --- Backup / Git token ---
    fun saveGitToken(token: String) {
        container.secretStore.putSecret(settings.value.gitTokenAlias, token.trim())
        _events.tryEmit("Saved Git token in Android Keystore")
    }

    fun exportAppBackup(destUri: Uri) {
        viewModelScope.launch {
            container.backupManager.exportBackupToUri(destUri).fold(
                onSuccess = { _events.tryEmit("Exported .yscode backup") },
                onFailure = { _events.tryEmit("Backup failed: ${it.message}") }
            )
        }
    }

    fun restoreAppBackup(srcUri: Uri) {
        viewModelScope.launch {
            container.backupManager.restoreBackupFromUri(srcUri).fold(
                onSuccess = { _events.tryEmit(it) },
                onFailure = { _events.tryEmit("Restore failed: ${it.message}") }
            )
        }
    }

    fun addPermissionRule(pattern: String, action: String) {
        if (pattern.isBlank()) return
        updateSettings { s ->
            val rule = PermissionRule("rule_" + UUID.randomUUID().toString().take(8), pattern.trim(), action)
            s.copy(permissionRules = s.permissionRules + rule)
        }
    }

    fun removePermissionRule(id: String) {
        updateSettings { s -> s.copy(permissionRules = s.permissionRules.filterNot { it.id == id }) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConfigViewModel(container) as T
        }
    }
}
