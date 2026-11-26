// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.mcp

import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class McpClient(
    private val serverName: String,
    private val transport: McpTransport,
    private val onLogsChanged: (List<String>) -> Unit = {}
) {
    private val logs = mutableListOf<String>()

    fun log(msg: String) {
        val snapshot = synchronized(logs) {
            logs += msg
            if (logs.size > 150) logs.removeAt(0)
            logs.toList()
        }
        runCatching { onLogsChanged(snapshot) }
    }

    fun getLogs(): List<String> = synchronized(logs) { logs.toList() }

    suspend fun initialize(initTimeoutMs: Long = 180_000L): Result<List<McpToolInfo>> = runCatching {
        kotlinx.coroutines.coroutineScope {
            val heartbeat = launch {
                var waited = 0
                while (true) {
                    kotlinx.coroutines.delay(15_000L)
                    waited += 15
                    log("Still starting... ${waited}s (first run of an npx/uvx server downloads packages)")
                }
            }
            try {
                transport.connect { log(it) }
                val initParams = buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("capabilities") {
                        putJsonObject("roots") { put("listChanged", false) }
                    }
                    putJsonObject("clientInfo") {
                        put("name", "YSCode")
                        put("version", "1.1.0")
                    }
                }
                val initResp = transport.sendRequest("initialize", initParams, timeoutMs = initTimeoutMs)
                initResp["error"]?.jsonObject?.let { err ->
                    error(err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP initialize failed")
                }
                val serverInfo = initResp["result"]?.jsonObject?.get("serverInfo")?.jsonObject
                log("Initialized '$serverName' (${serverInfo?.get("name")?.jsonPrimitive?.contentOrNull ?: "server"} ${serverInfo?.get("version")?.jsonPrimitive?.contentOrNull.orEmpty()})")
                transport.sendNotification("notifications/initialized")

                val all = mutableListOf<McpToolInfo>()
                var cursor: String? = null
                var pages = 0
                do {
                    val params = buildJsonObject { if (cursor != null) put("cursor", cursor!!) }
                    val toolsResp = transport.sendRequest("tools/list", params, timeoutMs = 60_000L)
                    toolsResp["error"]?.jsonObject?.let { err ->
                        error(err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP tools/list failed")
                    }
                    val result = toolsResp["result"]?.jsonObject
                    val toolsArr = result?.get("tools")?.jsonArray.orEmpty()
                    all += toolsArr.mapNotNull { el ->
                        val obj = el.jsonObject
                        val rawName = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: "MCP tool $rawName"
                        val schema = obj["inputSchema"]?.jsonObject ?: buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {}
                        }
                        val safeServer = McpConfigParser.sanitizeMcpName(serverName)
                        val safeTool = McpConfigParser.sanitizeMcpName(rawName)
                        McpToolInfo(
                            serverName = serverName,
                            toolName = rawName,
                            qualifiedName = "mcp__${safeServer}__$safeTool",
                            description = desc,
                            inputSchema = schema
                        )
                    }
                    cursor = result?.get("nextCursor")?.jsonPrimitive?.contentOrNull
                    pages++
                } while (cursor != null && pages < 10)
                log("Discovered ${all.size} tools from '$serverName'")
                all.toList()
            } finally {
                heartbeat.cancel()
            }
        }
    }

    suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
        timeoutMs: Long = 600_000L
    ): Result<String> = runCatching {
        val params = buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }
        val resp = transport.sendRequest("tools/call", params, timeoutMs = timeoutMs)
        resp["error"]?.jsonObject?.let { err ->
            error(err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP tool call error")
        }
        val result = resp["result"]?.jsonObject ?: return@runCatching resp.toString()
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
        val contentArr = result["content"]?.jsonArray
        val text = if (contentArr != null) {
            contentArr.mapNotNull { item ->
                val obj = item.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                when {
                    obj["text"] != null -> obj["text"]!!.jsonPrimitive.contentOrNull.orEmpty()
                    type == "image" -> "[image ${obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""} omitted]"
                    else -> obj.toString().take(4000)
                }
            }.joinToString("\n")
        } else {
            result.toString()
        }
        if (isError) error(text) else text
    }

    fun close() {
        transport.close()
    }
}
