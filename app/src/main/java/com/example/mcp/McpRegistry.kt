// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.mcp

import com.example.data.McpServerDao
import com.example.data.McpServerEntity
import com.example.data.SecretStore
import com.example.data.SettingsStore
import com.example.linux.LinuxEnvironment
import com.example.linux.ProotRunner
import com.example.util.JsonHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class McpRegistry(
    private val dao: McpServerDao,
    private val secretStore: SecretStore,
    private val settingsStore: SettingsStore,
    private val env: LinuxEnvironment,
    private val runner: ProotRunner
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, McpClient>()

    private val _serverStates = MutableStateFlow<List<McpServerState>>(emptyList())
    val serverStates: StateFlow<List<McpServerState>> = _serverStates.asStateFlow()

    suspend fun refreshFromDb(workingDir: File? = null) {
        val entities = dao.getAll()
        val currentMap = _serverStates.value.associateBy { it.serverId }
        _serverStates.value = entities.map { e ->
            currentMap[e.id]?.copy(enabled = e.enabled, name = e.name, transport = e.transport, scope = e.scope)
                ?: McpServerState(
                    serverId = e.id,
                    name = e.name,
                    transport = e.transport,
                    scope = e.scope,
                    enabled = e.enabled
                )
        }
        entities.filter { it.enabled }.forEach { entity ->
            if (!clients.containsKey(entity.id)) {
                // connect in parallel so one slow npx server does not block the others
                scope.launch { connectServer(entity, workingDir ?: env.projectsDir) }
            }
        }
    }

    suspend fun addOrUpdateServer(
        id: String? = null,
        name: String,
        transport: String,
        scopeStr: String,
        projectId: String? = null,
        config: McpServerConfig,
        secretEnvValues: Map<String, String> = emptyMap(),
        secretHeaderValues: Map<String, String> = emptyMap(),
        workingDir: File? = null
    ): McpServerEntity {
        val serverId = id ?: "mcp_" + UUID.randomUUID().toString().take(8)
        secretEnvValues.forEach { (k, v) ->
            if (v.isNotBlank()) secretStore.putSecret("mcp_${serverId}_env_$k", v)
        }
        secretHeaderValues.forEach { (k, v) ->
            if (v.isNotBlank()) secretStore.putSecret("mcp_${serverId}_hdr_$k", v)
        }
        val entity = McpServerEntity(
            id = serverId,
            scope = scopeStr,
            projectId = projectId,
            name = name.trim(),
            transport = transport.lowercase(),
            configJson = JsonHelper.json.encodeToString(config),
            enabled = true
        )
        dao.upsert(entity)
        connectServer(entity, workingDir ?: env.projectsDir)
        return entity
    }

    suspend fun removeServer(serverId: String) {
        clients.remove(serverId)?.close()
        dao.deleteById(serverId)
        _serverStates.value = _serverStates.value.filterNot { it.serverId == serverId }
    }

    suspend fun toggleServer(serverId: String, enabled: Boolean, workingDir: File? = null) {
        val entity = dao.getById(serverId) ?: return
        val updated = entity.copy(enabled = enabled)
        dao.upsert(updated)
        if (enabled) {
            connectServer(updated, workingDir ?: env.projectsDir)
        } else {
            clients.remove(serverId)?.close()
            updateState(serverId) { it.copy(enabled = false, status = McpStatus.DISCONNECTED, tools = emptyList()) }
        }
    }

    suspend fun toggleTool(serverId: String, toolName: String, enabled: Boolean) {
        val entity = dao.getById(serverId) ?: return
        val disabled = runCatching {
            JsonHelper.json.decodeFromString<List<String>>(entity.disabledToolsJson).toMutableSet()
        }.getOrDefault(mutableSetOf())
        if (enabled) disabled.remove(toolName) else disabled.add(toolName)
        dao.upsert(entity.copy(disabledToolsJson = JsonHelper.json.encodeToString(disabled.toList())))
        updateState(serverId) { state ->
            state.copy(tools = state.tools.map { t -> if (t.toolName == toolName) t.copy(enabled = enabled) else t })
        }
    }

    fun restartServer(serverId: String, workingDir: File? = null) {
        scope.launch {
            val entity = dao.getById(serverId) ?: return@launch
            clients.remove(serverId)?.close()
            connectServer(entity, workingDir ?: env.projectsDir)
        }
    }

    fun getAllEnabledTools(): List<McpToolInfo> {
        return _serverStates.value
            .filter { it.enabled && it.status == McpStatus.CONNECTED }
            .flatMap { it.tools }
            .filter { it.enabled }
    }

    suspend fun callQualifiedTool(qualifiedName: String, args: JsonObject): Result<String> {
        val state = _serverStates.value.firstOrNull { st ->
            st.enabled && st.tools.any { it.qualifiedName == qualifiedName && it.enabled }
        } ?: return Result.failure(IllegalStateException("MCP tool '$qualifiedName' is not connected"))
        val tool = state.tools.first { it.qualifiedName == qualifiedName }
        val client = clients[state.serverId]
            ?: return Result.failure(IllegalStateException("MCP client for '${state.name}' unavailable"))
        return client.callTool(tool.toolName, args)
    }

    fun shutdownAll() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private suspend fun connectServer(entity: McpServerEntity, workingDir: File) {
        clients.remove(entity.id)?.close()
        updateState(entity.id, fallbackEntity = entity) {
            it.copy(status = McpStatus.CONNECTING, lastError = null)
        }

        val config = runCatching {
            JsonHelper.json.decodeFromString<McpServerConfig>(entity.configJson)
        }.getOrElse {
            updateState(entity.id, entity) { s -> s.copy(status = McpStatus.ERROR, lastError = "Invalid server config") }
            return
        }

        val settings = settingsStore.current()
        if ((entity.transport == "http" || entity.transport == "sse") && !settings.allowMcpHttp) {
            updateState(entity.id, entity) { s ->
                s.copy(status = McpStatus.ERROR, lastError = "Blocked by Network Policy (Allow MCP HTTP is off)")
            }
            return
        }

        val resolvedEnv = config.env.mapValues { (k, v) ->
            secretStore.getSecret("mcp_${entity.id}_env_$k") ?: v
        }.filterValues { it.isNotBlank() }
        val resolvedHeaders = config.headers.mapValues { (k, v) ->
            secretStore.getSecret("mcp_${entity.id}_hdr_$k") ?: v
        }.filterValues { it.isNotBlank() }

        if (entity.transport !in setOf("stdio", "http", "sse")) {
            updateState(entity.id, entity) { s -> s.copy(status = McpStatus.ERROR, lastError = "Unsupported transport: ${entity.transport}") }
            return
        }

        val disabledTools = runCatching {
            JsonHelper.json.decodeFromString<List<String>>(entity.disabledToolsJson).toSet()
        }.getOrDefault(emptySet())

        var lastError: Throwable? = null
        var lastLogs: List<String> = emptyList()
        for (attempt in 1..2) {
            val transport: McpTransport = if (entity.transport == "stdio") {
                env.syncSharedStorageBinds()
                StdioMcpTransport(
                    runner = runner,
                    workingDir = workingDir,
                    command = config.command,
                    args = config.args,
                    env = resolvedEnv,
                    virtualWorkingDir = env.toVirtualPath(workingDir)
                )
            } else {
                HttpMcpTransport(config.url, resolvedHeaders)
            }
            val client = McpClient(entity.name, transport) { logs ->
                updateState(entity.id, entity) { st -> st.copy(logs = logs) }
            }
            clients[entity.id] = client

            if (entity.transport == "stdio") {
                val problem = ensureCommandAvailable(config.command, workingDir) { client.log(it) }
                if (problem != null) {
                    clients.remove(entity.id)?.close()
                    updateState(entity.id, entity) { s ->
                        s.copy(status = McpStatus.ERROR, tools = emptyList(), logs = client.getLogs(), lastError = problem)
                    }
                    return
                }
            }

            val initResult = client.initialize(if (entity.transport == "stdio") 180_000L else 30_000L)
            lastLogs = client.getLogs()
            if (initResult.isSuccess) {
                val tools = initResult.getOrThrow().map { t -> t.copy(enabled = t.toolName !in disabledTools) }
                updateState(entity.id, entity) { s ->
                    s.copy(status = McpStatus.CONNECTED, tools = tools, logs = client.getLogs(), lastError = null)
                }
                return
            }
            lastError = initResult.exceptionOrNull()
            clients.remove(entity.id)?.close()
            // Retry once only when the process died immediately (transient npx/network failure); never for timeouts.
            val msg = lastError?.message.orEmpty()
            val retryable = attempt == 1 && entity.transport == "stdio" && msg.contains("closed", ignoreCase = true)
            if (!retryable) break
            delay(1_500L)
        }

        val reason = when {
            lastError is kotlinx.coroutines.TimeoutCancellationException ->
                "Timed out waiting for the server to answer. Check the logs; the first run of npx/uvx servers can take several minutes on a phone. Tap restart to try again."
            else -> lastError?.message ?: "Failed to connect"
        }
        updateState(entity.id, entity) { s ->
            s.copy(status = McpStatus.ERROR, tools = emptyList(), logs = lastLogs, lastError = reason)
        }
    }

    /** Makes sure the server's launcher exists in the Linux environment (auto-installs node/python/uv). */
    private suspend fun ensureCommandAvailable(command: String, workingDir: File, log: (String) -> Unit): String? {
        val base = command.trim().substringBefore(' ')
        if (base.isBlank()) return "Empty command"
        val lower = base.substringAfterLast('/').lowercase()
        if (lower == "docker" || lower == "podman") {
            return "'$lower' is not available on Android. Use an npx/uvx/HTTP variant of this server instead " +
                "(for GitHub: HTTP url https://api.githubcopilot.com/mcp/ with an Authorization: Bearer <token> header)."
        }
        if (!env.hasProotAndRootfs()) return null
        val quoted = "'" + base.replace("'", "'\\''") + "'"
        val check = runner.exec("command -v $quoted >/dev/null 2>&1 && echo OK", workingDir, timeoutMs = 15_000L, mirrorToAgentTerminal = false)
        if (check.stdout.contains("OK")) return null
        val install = when (lower) {
            "npx", "node", "npm" -> "apk add --no-cache nodejs npm"
            "python", "python3", "pip", "pip3" -> "apk add --no-cache python3 py3-pip"
            "uvx", "uv" -> "apk add --no-cache uv 2>/dev/null || pip install --break-system-packages uv"
            "git" -> "apk add --no-cache git"
            else -> null
        } ?: return "Command '$base' was not found in the Linux environment. Install it first (pkg install <name>) or use another server."
        log("'$base' is missing - installing automatically...")
        val res = runner.exec(install, workingDir, timeoutMs = 300_000L, mirrorToAgentTerminal = false)
        return if (res.exitCode == 0) null else "Could not install '$base': ${res.stdout.takeLast(300)}"
    }

    private fun updateState(
        serverId: String,
        fallbackEntity: McpServerEntity? = null,
        transform: (McpServerState) -> McpServerState
    ) {
        val current = _serverStates.value.toMutableList()
        val idx = current.indexOfFirst { it.serverId == serverId }
        if (idx >= 0) {
            current[idx] = transform(current[idx])
        } else if (fallbackEntity != null) {
            current += transform(
                McpServerState(
                    serverId = fallbackEntity.id,
                    name = fallbackEntity.name,
                    transport = fallbackEntity.transport,
                    scope = fallbackEntity.scope,
                    enabled = fallbackEntity.enabled
                )
            )
        }
        _serverStates.value = current
    }
}
