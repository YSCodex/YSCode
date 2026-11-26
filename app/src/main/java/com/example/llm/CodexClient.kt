// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * LLM client for models included in a ChatGPT plan (Codex backend, Responses API). It is used like any other provider:
 * YSCode's own agent loop sends the tools and runs them, the plan only supplies the model.
 */
class CodexClient(private val auth: CodexAuth) : LlmClient {

    companion object {
        val fallbackModels = listOf(
            ModelConfig("gpt-5.3-codex", "GPT-5.3 Codex", 272_000, true, true, true),
            ModelConfig("gpt-5.2-codex", "GPT-5.2 Codex", 272_000, true, true, true),
            ModelConfig("gpt-5.2", "GPT-5.2", 272_000, true, true, true),
            ModelConfig("gpt-5.1-codex-max", "GPT-5.1 Codex Max", 272_000, true, true, true),
            ModelConfig("gpt-5.1-codex-mini", "GPT-5.1 Codex Mini", 272_000, true, true, true)
        )
        private fun safeCallId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifEmpty { "call_0" }
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val body = buildBody(request).toString()
        val calls = HashMap<String, String>()       // item id -> call id
        val names = HashMap<String, String>()       // call id -> tool name
        val args = HashMap<String, StringBuilder>() // call id -> arguments so far
        val ended = HashSet<String>()
        var sawToolCall = false
        var finished = false
        var failed = false
        var retryAuth = false

        try {
            for (attempt in 0..1) {
                retryAuth = false
                val acc = try {
                    auth.validAccount(forceRefresh = attempt == 1)
                } catch (e: Exception) {
                    emit(LlmEvent.Error(e.message ?: "Not signed in to ChatGPT"))
                    return@flow
                }
                val req = auth.requestBuilder("${CodexAuth.BASE_URL}/responses", acc)
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(mediaType))
                    .build()

                executeSseStream(
                    client = HttpClients.streamingClient,
                    request = req,
                    onHttpError = { code, errBody ->
                        if (code == 401 && attempt == 0) {
                            retryAuth = true
                        } else {
                            emit(LlmEvent.Error(friendlyHttpError(code, errBody), httpCode = code, retryable = code in 500..599))
                            failed = true
                        }
                    },
                    onFrame = { frame ->
                        val o = JsonHelper.parseOrNull(frame.data)?.jsonObject ?: return@executeSseStream
                        when (o["type"]?.jsonPrimitive?.contentOrNull ?: frame.event) {
                            "response.output_text.delta" ->
                                o["delta"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) emit(LlmEvent.TextDelta(it)) }
                            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                                o["delta"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) emit(LlmEvent.ReasoningDelta(it)) }
                            "response.output_item.added" -> {
                                val item = o["item"]?.jsonObject
                                if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                                    val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: item["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: itemId
                                    val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    calls[itemId] = callId; names[callId] = name; args[callId] = StringBuilder()
                                    sawToolCall = true
                                    emit(LlmEvent.ToolCallStart(callId, name))
                                }
                            }
                            "response.function_call_arguments.delta" -> {
                                val itemId = o["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val callId = calls[itemId] ?: itemId
                                val d = o["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                if (d.isNotEmpty() && args.containsKey(callId)) {
                                    args.getValue(callId).append(d)
                                    emit(LlmEvent.ToolCallDelta(callId, d))
                                }
                            }
                            "response.output_item.done" -> {
                                val item = o["item"]?.jsonObject
                                if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                                    val itemId = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: calls[itemId] ?: itemId
                                    if (ended.add(callId)) {
                                        val full = item["arguments"]?.jsonPrimitive?.contentOrNull ?: args[callId]?.toString().orEmpty()
                                        emit(LlmEvent.ToolCallEnd(callId, names[callId] ?: item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), full.ifBlank { "{}" }))
                                    }
                                }
                            }
                            "response.completed", "response.incomplete" -> {
                                val resp = o["response"]?.jsonObject
                                val usage = resp?.get("usage")?.jsonObject
                                if (usage != null) {
                                    emit(LlmEvent.Usage(
                                        usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                                        usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                                    ))
                                }
                                val incomplete = o["type"]?.jsonPrimitive?.contentOrNull == "response.incomplete"
                                finished = true
                                emit(LlmEvent.Done(if (incomplete) "length" else if (sawToolCall) "tool_calls" else "stop"))
                            }
                            "response.failed", "error" -> {
                                val err = o["response"]?.jsonObject?.get("error")?.jsonObject ?: o["error"]?.jsonObject
                                val msg = err?.get("message")?.jsonPrimitive?.contentOrNull
                                    ?: o["message"]?.jsonPrimitive?.contentOrNull ?: "The model reported an error"
                                failed = true; finished = true
                                emit(LlmEvent.Error(msg, retryable = false))
                            }
                        }
                    }
                )
                if (!retryAuth) break
            }
            if (!finished && !failed) emit(LlmEvent.Done(if (sawToolCall) "tool_calls" else "stop"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(LlmEvent.Error(e.message ?: "Network error", retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    private fun friendlyHttpError(code: Int, body: String): String {
        val root = JsonHelper.parseOrNull(body)?.let { runCatching { it.jsonObject }.getOrNull() }
        val err = root?.get("error")?.let { runCatching { it.jsonObject }.getOrNull() }
        val type = err?.get("type")?.jsonPrimitive?.contentOrNull
        val msg = err?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root?.get("detail")?.jsonPrimitive?.contentOrNull
            ?: body.take(200)
        if (code == 429 || type == "usage_limit_reached") {
            val secs = err?.get("resets_in_seconds")?.jsonPrimitive?.longOrNull
            val wait = secs?.let { " Resets in about ${if (it >= 3600) "${it / 3600} h ${(it % 3600) / 60} min" else "${(it / 60).coerceAtLeast(1)} min"}." }.orEmpty()
            return "ChatGPT plan limit reached.$wait Switch account or model in Providers > ChatGPT Codex."
        }
        if (code == 403) return "ChatGPT refused the request (HTTP 403). Check that the plan is active. $msg"
        return "HTTP $code: $msg"
    }

    private fun buildBody(r: ChatRequest): JsonObject = buildJsonObject {
        put("model", r.model)
        put("instructions", r.system.ifBlank { "You are a helpful coding assistant." })
        put("input", buildInput(r.messages))
        if (r.supportsTools && r.tools.isNotEmpty()) {
            putJsonArray("tools") {
                r.tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parametersSchema)
                        put("strict", false)
                    })
                }
            }
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
        }
        put("store", false)
        put("stream", true)
        putJsonObject("reasoning") {
            put("effort", r.reasoningEffort?.takeIf { it.isNotBlank() } ?: "medium")
            put("summary", "auto")
        }
        putJsonObject("text") { put("verbosity", "medium") }
    }

    private fun buildInput(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        for (msg in messages) {
            when (msg.role) {
                "assistant" -> {
                    val text = msg.plainText()
                    if (text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            putJsonArray("content") { add(buildJsonObject { put("type", "output_text"); put("text", text) }) }
                        })
                    }
                    msg.toolCalls().forEach { tc ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", safeCallId(tc.id))
                            put("name", tc.name)
                            put("arguments", tc.argumentsJson.ifBlank { "{}" })
                        })
                    }
                }
                else -> {
                    msg.toolResults().forEach { tr ->
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", safeCallId(tr.toolCallId))
                            put("output", tr.output)
                        })
                    }
                    val text = msg.plainText()
                    val images = msg.parts.filterIsInstance<LlmContentPart.ImageBase64>()
                    if (text.isNotBlank() || images.isNotEmpty()) {
                        add(buildJsonObject {
                            put("type", "message")
                            put("role", "user")
                            putJsonArray("content") {
                                if (text.isNotBlank()) add(buildJsonObject { put("type", "input_text"); put("text", text) })
                                images.forEach { img ->
                                    add(buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", "data:${img.mimeType};base64,${img.dataBase64}")
                                    })
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    override suspend fun fetchModels(): Result<List<ModelConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val acc = auth.validAccount()
            val req = auth.requestBuilder("${CodexAuth.BASE_URL}/models?client_version=${CodexAuth.CLIENT_VERSION}", acc).get().build()
            HttpClients.streamingClient.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val root = JsonHelper.parseOrNull(txt)?.jsonObject ?: error("Invalid JSON")
                val arr = root["models"]?.jsonArray ?: root["data"]?.jsonArray ?: error("No models in response")
                val list = arr.mapNotNull { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val visibility = o["visibility"]?.jsonPrimitive?.contentOrNull
                    if (visibility != null && visibility != "list") return@mapNotNull null
                    val id = o["slug"]?.jsonPrimitive?.contentOrNull ?: o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = o["display_name"]?.jsonPrimitive?.contentOrNull ?: id
                    val ctx = o["context_window"]?.jsonPrimitive?.intOrNull ?: 272_000
                    ModelConfig(id, name, ctx, supportsTools = true, supportsVision = true, supportsReasoning = true)
                }
                if (list.isEmpty()) error("Empty model list") else list
            }
        }
    }

    override suspend fun testConnection(modelId: String?): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val acc = runCatching { auth.validAccount() }.getOrElse {
            return@withContext ConnectionTestResult(false, it.message ?: "Not signed in")
        }
        val models = fetchModels().getOrElse { fallbackModels }
        ConnectionTestResult(
            success = true,
            message = "Signed in as ${acc.email} (${acc.plan}). ${models.size} models available.",
            latencyMs = System.currentTimeMillis() - start,
            discoveredModels = models
        )
    }
}
