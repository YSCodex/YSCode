// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * "Provider" that hands the user's message to the official Antigravity CLI (agy, headless stream-json). agy runs its
 * own agent in the project folder with the user's Google account; YSCode shows what it says and does.
 */
class AgyClient(private val agy: AgyManager) : LlmClient {

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val state = agy.checkCached()
        if (!state.installed) { emit(LlmEvent.Error("Antigravity CLI is not installed. Open Providers > Antigravity and tap Install.")); return@flow }
        if (!state.signedIn) { emit(LlmEvent.Error("Not signed in to Antigravity. Open Providers > Antigravity and tap Sign in.")); return@flow }

        val prompt = request.messages.lastOrNull { it.role == "user" && it.plainText().isNotBlank() }?.plainText()?.take(100_000)
        if (prompt == null) { emit(LlmEvent.Done("stop")); return@flow }

        val cwd = request.projectDir ?: File("/")
        val conv = request.sessionId?.let { agy.conversationFor(it) }
        val cmd = buildString {
            append("agy -p ").append(AgyManager.shQuote(prompt))
            append(" --output-format stream-json --print-timeout 30m")
            request.model.takeIf { it.isNotBlank() && it != "default" }?.let { append(" --model ").append(AgyManager.shQuote(it)) }
            if (conv != null) append(" --conversation ").append(AgyManager.shQuote(conv))
            append(if (agy.autoApprove) " --dangerously-skip-permissions" else " --mode accept-edits")
        }

        val raw = ArrayList<String>()
        var emitted = false
        val process = agy.spawn(cmd, cwd)
        val stderr = StringBuilder()
        val errThread = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } } }.also { it.isDaemon = true; it.start() }
        var finalText: String? = null
        var failure: String? = null
        val shownText = StringBuilder()
        val announced = HashSet<Long>()
        val finishedTools = HashSet<Long>()
        val turnTag = java.util.UUID.randomUUID().toString().take(6)
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    raw += line
                    val obj = JsonHelper.parseOrNull(line)?.let { runCatching { it.jsonObject }.getOrNull() }
                    if (obj == null) { emit(LlmEvent.TextDelta(line + "\n")); emitted = true; continue }
                    (obj.findString("conversation_id", "conversationId")
                        ?: obj.values.firstNotNullOfOrNull { (it as? JsonObject)?.findString("conversation_id", "conversationId") })
                        ?.let { id -> request.sessionId?.let { agy.rememberConversation(it, id) } }
                    val event = (obj.findString("event") ?: "").lowercase()
                    when (event) {
                        "init" -> {}
                        "step_update" -> {
                            val su = (obj["step_update"] as? JsonObject) ?: obj
                            val stype = (su.findString("step_type") ?: "").lowercase()
                            val delta = su.findString("text_delta")
                            val idx = su.findLong("step_index") ?: -1L
                            val toolInfo = su["tool_info"] as? JsonObject
                            val toolName = toolInfo?.findString("name") ?: su.findString("tool_name")
                            val state = (su.findString("state") ?: "ACTIVE").uppercase()
                            val thought = su.findString("thought", "thinking")
                            when {
                                stype == "user_input" || stype == "system_message" -> {}
                                thought != null || stype == "thought" || stype.contains("think") || stype.contains("reason") ->
                                    (thought ?: delta)?.let { emit(LlmEvent.ReasoningDelta(it)) }
                                stype == "tool" || toolInfo != null || toolName != null -> {
                                    val params = toolInfo?.get("parameters") as? JsonObject
                                    val (shownName, args) = mapTool(toolName ?: stype, params)
                                    val extId = "ext_${turnTag}_$idx"
                                    if (announced.add(idx)) emit(LlmEvent.ToolCallStart(extId, shownName))
                                    val ended = state == "DONE" || state.contains("ERR") || state.contains("FAIL") || state.contains("CANCEL")
                                    if (ended && finishedTools.add(idx)) {
                                        val out = (toolInfo?.findString("output") ?: su.findString("tool_output") ?: "")
                                            .lines().filterNot { it.trim().startsWith("Created At", true) }.joinToString("\n").trim().take(3000)
                                        emit(LlmEvent.ExternalTool(extId, shownName, args.toString(), out.ifBlank { "ok" }, state != "DONE"))
                                    }
                                }
                                stype == "agent_response" -> if (delta != null) {
                                    shownText.append(delta)
                                    emit(LlmEvent.TextDelta(delta))
                                    if (delta.isNotBlank()) emitted = true
                                }
                                else -> {}
                            }
                        }
                        "result" -> {
                            val r = (obj["result"] as? JsonObject) ?: obj
                            finalText = r.findString("response", "text", "output") ?: finalText
                            val status = r.findString("status")
                            if (status != null && !status.equals("SUCCESS", true)) failure = "agy finished with status $status" + (r.findString("error", "message")?.let { ": $it" } ?: "")
                            (r["usage"] as? JsonObject)?.let { u ->
                                val i = u.findLong("input_tokens", "inputTokens", "prompt_tokens") ?: 0L
                                val o = (u.findLong("output_tokens", "outputTokens", "completion_tokens") ?: 0L) + (u.findLong("thinking_tokens") ?: 0L)
                                if (i + o > 0) emit(LlmEvent.Usage(i, o))
                            }
                        }
                        else -> {
                            val type = (obj.findString("type", "kind") ?: "").lowercase()
                            when {
                                type.contains("think") || type.contains("reason") -> obj.textOf()?.let { emit(LlmEvent.ReasoningDelta(it)) }
                                type.contains("tool") || type.contains("function") || type.contains("command") -> {}
                                else -> obj.textOf()?.let { if (it.isNotEmpty()) { emit(LlmEvent.TextDelta(it)); emitted = true } }
                            }
                        }
                    }
                }
            }
            val code = withContext(Dispatchers.IO) { process.waitFor() }
            errThread.join(500)
            if (!emitted) {
                val tail = finalText
                if (!tail.isNullOrBlank()) { emit(LlmEvent.TextDelta(tail)); emitted = true }
            }
            agy.lastRun = raw.takeLast(300) + stderr.lines().filter { it.isNotBlank() }.map { "stderr: $it" }.takeLast(40)
            if (failure != null && !emitted) {
                emit(LlmEvent.Error(failure!!))
            } else if (!emitted) {
                agy.invalidateCheck()
                val why = stderr.toString().trim().takeLast(600).ifBlank { "agy returned no output (exit code $code). Open Providers > Antigravity > Diagnostics." }
                emit(LlmEvent.Error(why))
            } else {
                // agy's own dev server dies when agy exits. If it promised a localhost address that nothing answers,
                // serve the project's site from the app so the link works.
                val ports = Regex("(?:localhost|127\\.0\\.0\\.1):(\\d{2,5})").findAll(shownText.toString() + " " + (finalText ?: ""))
                    .map { it.groupValues[1].toInt() }.distinct().take(2).toList()
                if (ports.isNotEmpty() && cwd.isDirectory && com.example.preview.PreviewServer.findWebRoot(cwd) != null) {
                    val served = ports.filter { !com.example.preview.PreviewServer.isReachable(it) }
                        .map { com.example.preview.PreviewServer.serve(cwd, it) }
                    if (served.isNotEmpty()) {
                        emit(LlmEvent.TextDelta("\n\n_The server agy started stops when agy finishes, so YSCode is serving this project's site at " +
                            served.joinToString(" and ") { "http://localhost:$it" } + ". Open it with the globe button above._"))
                    }
                }
                emit(LlmEvent.Done("stop"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { process.destroyForcibly() }
            throw e
        } catch (e: Throwable) {
            runCatching { process.destroyForcibly() }
            emit(LlmEvent.Error(e.message ?: "agy failed", retryable = false))
        }
    }.flowOn(Dispatchers.IO)

    private fun JsonObject.findString(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] ?: continue
            (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** Maps an Antigravity tool call onto the tool names YSCode already knows, so the chat shows normal tool cards. */
    private fun mapTool(raw: String, p: JsonObject?): Pair<String, JsonObject> {
        fun str(k: String) = (p?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun pick(vararg parts: String): String? {
            val keys = p?.keys ?: return null
            for (part in parts) keys.firstOrNull { it.lowercase().contains(part) && str(it) != null }?.let { return str(it) }
            return null
        }
        fun rel(v: String?) = v?.replace(Regex("^/root/projects/[^/]+/?"), "")?.ifBlank { "." }
        val l = raw.lowercase()
        fun obj(vararg kv: Pair<String, String?>) =
            kotlinx.serialization.json.buildJsonObject { kv.forEach { (k, v) -> if (v != null) put(k, v) } }
        return when {
            l.contains("run_command") || l == "command" || l.contains("shell") -> "bash" to obj("command" to pick("commandline", "command"), "cwd" to rel(pick("cwd")))
            l.contains("replace") || l.contains("edit") || l.contains("patch") -> "edit_file" to obj("path" to rel(pick("targetfile", "absolutepath", "path", "file")))
            l.contains("write") || l.contains("create_file") -> "write_file" to obj("path" to rel(pick("targetfile", "absolutepath", "path", "file")))
            l.contains("list_dir") || l.contains("list_directory") || l == "ls" -> "list_dir" to obj("path" to rel(pick("directory", "path", "dir")))
            l.contains("grep") || l.contains("search") || l.contains("find") -> "grep" to obj("pattern" to pick("query", "pattern", "regex", "search"), "path" to rel(pick("path", "directory", "dir")))
            l.contains("view") || l.contains("read") || l.contains("open") -> "read_file" to obj("path" to rel(pick("absolutepath", "path", "file", "target")))
            else -> raw to (p ?: kotlinx.serialization.json.JsonObject(emptyMap()))
        }
    }

    private fun JsonObject.findLong(vararg keys: String): Long? {
        for (k in keys) (this[k] as? JsonPrimitive)?.longOrNull?.let { return it }
        return null
    }

    /** Best-effort text of an event: a string field, or text blocks inside content/message. */
    private fun JsonObject.textOf(): String? {
        findString("delta", "text", "content", "response", "message", "output")?.let { return it }
        for (k in listOf("content", "message", "delta")) {
            val el: JsonElement = this[k] ?: continue
            when (el) {
                is JsonArray -> el.mapNotNull { runCatching { it.jsonObject.findString("text") }.getOrNull() }.joinToString("").takeIf { it.isNotEmpty() }?.let { return it }
                is JsonObject -> el.textOf()?.let { return it }
                else -> {}
            }
        }
        return null
    }

    override suspend fun fetchModels(): Result<List<ModelConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val lines = agy.models()
            if (lines.isEmpty()) error("agy returned no models. Is it signed in?")
            lines.map { l ->
                val tokens = l.split(Regex("\\s+"), limit = 2)
                val slug = tokens[0].trim('`', '*', '-', '•').takeIf { Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{2,}$").matches(it) } ?: l
                ModelConfig(slug, l.take(60), 1_000_000, supportsTools = true, supportsVision = false, supportsReasoning = true)
            }
        }
    }

    override suspend fun testConnection(modelId: String?): ConnectionTestResult = withContext(Dispatchers.IO) {
        val s = agy.check()
        when {
            !s.installed -> ConnectionTestResult(false, "Antigravity CLI is not installed.")
            !s.signedIn -> ConnectionTestResult(false, "agy ${s.version} is installed, but not signed in.")
            else -> ConnectionTestResult(true, "agy ${s.version}, signed in.")
        }
    }
}
