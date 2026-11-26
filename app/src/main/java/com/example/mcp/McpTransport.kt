// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.mcp

import com.example.linux.ProotRunner
import com.example.llm.HttpClients
import com.example.util.JsonHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface McpTransport {
    suspend fun connect(onLog: (String) -> Unit)
    suspend fun sendRequest(method: String, params: JsonObject? = null, timeoutMs: Long = 20_000L): JsonObject
    suspend fun sendNotification(method: String, params: JsonObject? = null)
    fun close()
}

class StdioMcpTransport(
    private val runner: ProotRunner,
    private val workingDir: File,
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val virtualWorkingDir: String = workingDir.absolutePath
) : McpTransport {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val idGen = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    @Volatile private var closed = false

    override suspend fun connect(onLog: (String) -> Unit) = withContext(Dispatchers.IO) {
        val fullCmd = (listOf(command) + args).joinToString(" ") { shellQuote(it) }
        onLog("Spawning stdio MCP: $fullCmd")
        val defaultEnv = mapOf(
            "CI" to "1",
            "NPM_CONFIG_UPDATE_NOTIFIER" to "false",
            "NPM_CONFIG_FUND" to "false",
            "NPM_CONFIG_AUDIT" to "false",
            "npm_config_yes" to "true",
            "PYTHONUNBUFFERED" to "1",
            "PIP_BREAK_SYSTEM_PACKAGES" to "1"
        )
        val proc = runner.spawnInteractiveProcess(fullCmd, workingDir, defaultEnv + env)
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

        Thread {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String? = null
                    while (!closed && reader.readLine().also { line = it } != null) {
                        val txt = line!!.trim()
                        if (txt.isEmpty()) continue
                        val obj = JsonHelper.parseOrNull(txt)?.jsonObject
                        if (obj != null) {
                            val method = obj["method"]?.jsonPrimitive?.contentOrNull
                            val id = obj["id"]?.jsonPrimitive?.intOrNull
                            if (method != null) {
                                // server -> client request or notification
                                if (obj.containsKey("id")) answerServerRequest(obj, method)
                            } else if (id != null) {
                                pending.remove(id)?.complete(obj)
                            }
                        } else {
                            onLog("[stdout] $txt")
                        }
                    }
                }
            }.onFailure { err ->
                if (!closed) onLog("Stdio reader ended: ${err.message}")
            }
            pending.values.forEach { it.completeExceptionally(IllegalStateException("MCP stdio process closed")) }
            pending.clear()
        }.start()

        Thread {
            runCatching {
                BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { errReader ->
                    var line: String? = null
                    while (!closed && errReader.readLine().also { line = it } != null) {
                        onLog("[stderr] ${line!!}")
                    }
                }
            }
        }.start()
    }

    override suspend fun sendRequest(method: String, params: JsonObject?, timeoutMs: Long): JsonObject {
        val id = idGen.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        withContext(Dispatchers.IO) {
            val w = writer ?: error("MCP stdio transport not connected")
            synchronized(w) {
                w.write(payload.toString())
                w.newLine()
                w.flush()
            }
        }
        return withTimeout(timeoutMs) { deferred.await() }
    }

    /** Replies to requests initiated by the server (ping, roots/list); everything else is "method not found". */
    private fun answerServerRequest(req: JsonObject, method: String) {
        val idEl = req["id"] ?: return
        val reply = when (method) {
            "ping" -> buildJsonObject {
                put("jsonrpc", "2.0"); put("id", idEl); put("result", buildJsonObject {})
            }
            "roots/list" -> buildJsonObject {
                put("jsonrpc", "2.0"); put("id", idEl)
                put("result", buildJsonObject { put("roots", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("uri", "file://$virtualWorkingDir"); put("name", workingDir.name) })
                }) })
            }
            else -> buildJsonObject {
                put("jsonrpc", "2.0"); put("id", idEl)
                put("error", buildJsonObject { put("code", -32601); put("message", "Method not found: $method") })
            }
        }
        runCatching {
            val w = writer ?: return
            synchronized(w) { w.write(reply.toString()); w.newLine(); w.flush() }
        }
    }

    override suspend fun sendNotification(method: String, params: JsonObject?) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        withContext(Dispatchers.IO) {
            val w = writer ?: return@withContext
            synchronized(w) {
                w.write(payload.toString())
                w.newLine()
                w.flush()
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private fun shellQuote(arg: String): String =
        if (arg.all { it.isLetterOrDigit() || it in "-_./=:" }) arg
        else "'${arg.replace("'", "'\\''")}'"
}

class HttpMcpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap()
) : McpTransport {

    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val idGen = AtomicInteger(1)
    private var sessionId: String? = null

    override suspend fun connect(onLog: (String) -> Unit) {
        onLog("Configured HTTP/SSE MCP endpoint: $url")
    }

    override suspend fun sendRequest(method: String, params: JsonObject?, timeoutMs: Long): JsonObject =
        withContext(Dispatchers.IO) {
            val id = idGen.getAndIncrement()
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            }
            val reqBuilder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(mediaType))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2024-11-05")
            sessionId?.let { reqBuilder.header("Mcp-Session-Id", it) }
            headers.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }

            HttpClients.streamingClient.newCall(reqBuilder.build()).execute().use { resp ->
                resp.header("Mcp-Session-Id")?.let { sessionId = it }
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${raw.take(300)}")
                }
                val contentType = resp.header("Content-Type").orEmpty()
                if (contentType.contains("text/event-stream", ignoreCase = true) || raw.trimStart().startsWith("data:")) {
                    parseFirstSseJson(raw) ?: error("No JSON-RPC response in SSE stream")
                } else {
                    JsonHelper.parseOrNull(raw)?.jsonObject ?: error("Invalid JSON-RPC response: ${raw.take(200)}")
                }
            }
        }

    override suspend fun sendNotification(method: String, params: JsonObject?) {
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            }
            val reqBuilder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(mediaType))
                .header("Content-Type", "application/json")
            sessionId?.let { reqBuilder.header("Mcp-Session-Id", it) }
            headers.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }
            runCatching { HttpClients.streamingClient.newCall(reqBuilder.build()).execute().close() }
        }
    }

    override fun close() {
        sessionId = null
    }

    private fun parseFirstSseJson(sseBody: String): JsonObject? {
        val dataLines = mutableListOf<String>()
        for (line in sseBody.lines()) {
            if (line.startsWith("data:")) {
                dataLines += line.removePrefix("data:").trim()
            } else if (line.isBlank() && dataLines.isNotEmpty()) {
                val joined = dataLines.joinToString("\n")
                JsonHelper.parseOrNull(joined)?.jsonObject?.let { return it }
                dataLines.clear()
            }
        }
        if (dataLines.isNotEmpty()) {
            return JsonHelper.parseOrNull(dataLines.joinToString("\n"))?.jsonObject
        }
        return null
    }
}
