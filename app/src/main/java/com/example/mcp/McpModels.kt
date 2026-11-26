// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.mcp

import com.example.util.JsonHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class McpStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

@Serializable
data class McpServerConfig(
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class McpToolInfo(
    val serverName: String,
    val toolName: String,
    val qualifiedName: String, // mcp__<server>__<tool>
    val description: String,
    val inputSchema: JsonObject,
    val enabled: Boolean = true
)

data class McpServerState(
    val serverId: String,
    val name: String,
    val transport: String,
    val scope: String,
    val enabled: Boolean,
    val status: McpStatus = McpStatus.DISCONNECTED,
    val tools: List<McpToolInfo> = emptyList(),
    val logs: List<String> = emptyList(),
    val lastError: String? = null
)

data class ParsedMcpServerEntry(
    val name: String,
    val transport: String,
    val config: McpServerConfig
)

object McpConfigParser {
    /**
     * Parses standard Claude Desktop / MCP `{"mcpServers": { "name": { ... } }}` JSON or direct map.
     */
    fun parseMcpServersJson(rawJson: String): Result<List<ParsedMcpServerEntry>> = runCatching {
        val root = JsonHelper.parseOrNull(rawJson)?.jsonObject ?: error("Invalid JSON object")
        val serversObj = root["mcpServers"]?.jsonObject ?: root
        if (serversObj.isEmpty()) error("No servers found in mcpServers JSON")

        serversObj.entries.map { (name, el) ->
            val obj = el.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val command = obj["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val transport = obj["transport"]?.jsonPrimitive?.contentOrNull?.lowercase()
                ?: when {
                    url.contains("/sse", ignoreCase = true) -> "sse"
                    url.isNotBlank() -> "http"
                    command.isNotBlank() -> "stdio"
                    else -> error("Server '$name' must specify 'command' or 'url'")
                }
            val args = obj["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val env = obj["env"]?.jsonObject?.entries?.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: "")
            }.orEmpty()
            val headers = obj["headers"]?.jsonObject?.entries?.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: "")
            }.orEmpty()
            ParsedMcpServerEntry(
                name = sanitizeMcpName(name),
                transport = transport,
                config = McpServerConfig(command, args, env, url, headers)
            )
        }
    }

    fun sanitizeMcpName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifEmpty { "server" }
}
