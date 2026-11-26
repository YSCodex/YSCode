// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Reads text from a field that different providers send as a string, a list of parts or an object. */
internal fun JsonElement?.flexText(): String? = when (this) {
    null -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> joinToString("") { it.flexText().orEmpty() }.ifEmpty { null }
    is JsonObject -> (this["text"] ?: this["content"] ?: this["reasoning"] ?: this["summary"]).flexText()
}

class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val extraHeaders: Map<String, String> = emptyMap()
) : LlmClient {

    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val cleanBase = baseUrl.trimEnd('/')

    private fun endpoint(path: String): String {
        val normalizedPath = path.trimStart('/')
        return if (cleanBase.endsWith("/v1")) {
            "$cleanBase/$normalizedPath"
        } else {
            "$cleanBase/v1/$normalizedPath"
        }
    }

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val bodyJson = buildPayload(request, stream = true)
        val reqBuilder = Request.Builder()
            .url(endpoint("chat/completions"))
            .post(bodyJson.toString().toRequestBody(mediaType))
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }

        val activeToolCalls = linkedMapOf<Int, MutableToolCallBuffer>()
        var emittedDone = false
        var finishSeen = "stop"

        try {
            executeSseStream(
                client = HttpClients.streamingClient,
                request = reqBuilder.build(),
                onHttpError = { code, errBody ->
                    val retryable = code == 429 || code in 500..599
                    val parsedMsg = extractErrorMessage(errBody)
                    emit(LlmEvent.Error("HTTP $code: $parsedMsg", httpCode = code, retryable = retryable))
                    emittedDone = true
                },
                onFrame = { frame ->
                    if (frame.data == "[DONE]") {
                        for (tc in activeToolCalls.values) {
                            emit(LlmEvent.ToolCallEnd(tc.id, tc.name, tc.args.toString()))
                        }
                        activeToolCalls.clear()
                        emit(LlmEvent.Done(finishSeen))
                        emittedDone = true
                        return@executeSseStream
                    }
                    val root = JsonHelper.parseOrNull(frame.data) as? JsonObject ?: return@executeSseStream
                    root["error"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { errEl ->
                        val msg = (errEl as? JsonObject)?.get("message").flexText()
                            ?: (errEl as? JsonPrimitive)?.contentOrNull ?: frame.data
                        emit(LlmEvent.Error(msg, retryable = false))
                        emittedDone = true
                        return@executeSseStream
                    }

                    (root["usage"] as? JsonObject)?.let { usage ->
                        val inTok = (usage["prompt_tokens"] as? JsonPrimitive)?.longOrNull ?: 0L
                        val outTok = (usage["completion_tokens"] as? JsonPrimitive)?.longOrNull ?: 0L
                        if (inTok > 0 || outTok > 0) {
                            emit(LlmEvent.Usage(inTok, outTok))
                        }
                    }

                    val choices = root["choices"] as? JsonArray ?: return@executeSseStream
                    val first = choices.firstOrNull() as? JsonObject ?: return@executeSseStream
                    val delta = first["delta"] as? JsonObject
                    if (delta != null) {
                        val reasoning = delta["reasoning_content"].flexText()
                            ?: delta["reasoning"].flexText()
                            ?: delta["reasoning_details"].flexText()
                        if (!reasoning.isNullOrEmpty()) {
                            emit(LlmEvent.ReasoningDelta(reasoning))
                        }
                        val content = delta["content"].flexText()
                        if (!content.isNullOrEmpty()) {
                            emit(LlmEvent.TextDelta(content))
                        }
                        (delta["tool_calls"] as? JsonArray)?.forEach { tcEl ->
                            val tcObj = tcEl as? JsonObject ?: return@forEach
                            val idx = (tcObj["index"] as? JsonPrimitive)?.intOrNull ?: 0
                            val id = (tcObj["id"] as? JsonPrimitive)?.contentOrNull
                            val fn = tcObj["function"] as? JsonObject
                            val name = (fn?.get("name") as? JsonPrimitive)?.contentOrNull
                            val argsEl = fn?.get("arguments")
                            val argsDelta = (if (argsEl is JsonPrimitive) argsEl.contentOrNull else argsEl?.toString()).orEmpty()

                            val existing = activeToolCalls[idx]
                            if (existing == null) {
                                val resolvedId = id ?: "call_${System.nanoTime()}_$idx"
                                val resolvedName = name ?: "unknown"
                                val buf = MutableToolCallBuffer(resolvedId, resolvedName)
                                activeToolCalls[idx] = buf
                                emit(LlmEvent.ToolCallStart(resolvedId, resolvedName))
                                if (argsDelta.isNotEmpty()) {
                                    buf.args.append(argsDelta)
                                    emit(LlmEvent.ToolCallDelta(resolvedId, argsDelta))
                                }
                            } else {
                                if (!name.isNullOrEmpty() && existing.name == "unknown") {
                                    existing.name = name
                                }
                                if (argsDelta.isNotEmpty()) {
                                    existing.args.append(argsDelta)
                                    emit(LlmEvent.ToolCallDelta(existing.id, argsDelta))
                                }
                            }
                        }
                    }

                    val finishReason = (first["finish_reason"] as? JsonPrimitive)?.contentOrNull
                    if (!finishReason.isNullOrEmpty() && finishReason != "null") {
                        finishSeen = finishReason
                        for (tc in activeToolCalls.values) {
                            emit(LlmEvent.ToolCallEnd(tc.id, tc.name, tc.args.toString()))
                        }
                        activeToolCalls.clear()
                    }
                }
            )
            if (!emittedDone) {
                for (tc in activeToolCalls.values) {
                    emit(LlmEvent.ToolCallEnd(tc.id, tc.name, tc.args.toString()))
                }
                emit(LlmEvent.Done(finishSeen))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(LlmEvent.Error(e.message ?: "Network error", retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(): Result<List<ModelConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBuilder = Request.Builder()
                .url(endpoint("models"))
                .get()
            if (!apiKey.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
            }
            extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }

            HttpClients.streamingClient.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${extractErrorMessage(body)}")
                }
                val root = JsonHelper.parseOrNull(body)?.jsonObject ?: error("Invalid JSON response")
                val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
                data.mapNotNull { el ->
                    val id = el.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ModelConfig(
                        id = id,
                        displayName = id,
                        supportsTools = !id.contains("embedding", ignoreCase = true),
                        supportsVision = VisionSupport.inferVision(id),
                        supportsReasoning = id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") || id.contains("reasoner") || id.contains("r1")
                    )
                }.sortedBy { it.id }
            }
        }
    }

    override suspend fun testConnection(modelId: String?): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val fetched = fetchModels()
        val latency = System.currentTimeMillis() - start
        fetched.fold(
            onSuccess = { models ->
                ConnectionTestResult(
                    success = true,
                    message = "Connected (${models.size} models found in ${latency}ms)",
                    latencyMs = latency,
                    discoveredModels = models
                )
            },
            onFailure = { err ->
                // Some custom OpenAI endpoints don't implement GET /models; fallback to tiny POST ping if modelId given
                if (!modelId.isNullOrBlank()) {
                    val pingResult = pingCompletion(modelId)
                    if (pingResult.isSuccess) {
                        return@withContext ConnectionTestResult(
                            success = true,
                            message = "Connected via chat probe (${System.currentTimeMillis() - start}ms)",
                            latencyMs = System.currentTimeMillis() - start
                        )
                    }
                }
                ConnectionTestResult(
                    success = false,
                    message = err.message ?: "Connection failed",
                    latencyMs = latency
                )
            }
        )
    }

    private fun pingCompletion(modelId: String): Result<Unit> = runCatching {
        val payload = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 5)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "ping")
                })
            })
        }
        val reqBuilder = Request.Builder()
            .url(endpoint("chat/completions"))
            .post(payload.toString().toRequestBody(mediaType))
        if (!apiKey.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }
        HttpClients.streamingClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}: ${extractErrorMessage(resp.body?.string().orEmpty())}")
            }
        }
    }

    private fun buildPayload(request: ChatRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", stream)
        if (stream) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        request.temperature?.let { put("temperature", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        if (!request.reasoningEffort.isNullOrBlank() &&
            (request.model.startsWith("o1") || request.model.startsWith("o3") || request.model.startsWith("o4"))
        ) {
            put("reasoning_effort", request.reasoningEffort)
        }

        put("messages", buildJsonArray {
            if (request.system.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                })
            }
            for (msg in request.messages) {
                when (msg.role) {
                    "user" -> {
                        val images = msg.parts.filterIsInstance<LlmContentPart.ImageBase64>()
                        val text = msg.plainText()
                        if (images.isEmpty()) {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", text)
                            })
                        } else {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    if (text.isNotBlank()) {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", text)
                                        })
                                    }
                                    images.forEach { img ->
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:${img.mimeType};base64,${img.dataBase64}")
                                            }
                                        })
                                    }
                                })
                            })
                        }
                    }
                    "assistant" -> {
                        val text = msg.plainText()
                        val calls = msg.toolCalls()
                        add(buildJsonObject {
                            put("role", "assistant")
                            if (text.isNotEmpty() || calls.isEmpty()) {
                                put("content", text)
                            } else {
                                put("content", JsonPrimitive(null as String?))
                            }
                            if (calls.isNotEmpty()) {
                                put("tool_calls", buildJsonArray {
                                    calls.forEach { tc ->
                                        add(buildJsonObject {
                                            put("id", tc.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", tc.name)
                                                put("arguments", tc.argumentsJson)
                                            }
                                        })
                                    }
                                })
                            }
                        })
                    }
                    "tool" -> {
                        msg.toolResults().forEach { tr ->
                            add(buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", tr.toolCallId)
                                put("content", tr.output)
                            })
                        }
                    }
                }
            }
        })

        if (request.supportsTools && request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parametersSchema)
                        }
                    })
                }
            })
        }
    }

    private fun extractErrorMessage(body: String): String {
        val parsed = JsonHelper.parseOrNull(body)
        val errEl = (parsed as? JsonObject)?.get("error")
        val msg = ((errEl as? JsonObject)?.get("message") ?: errEl).flexText()
        return msg ?: body.take(300).ifBlank { "Request failed" }
    }

    private data class MutableToolCallBuffer(
        val id: String,
        var name: String,
        val args: StringBuilder = StringBuilder()
    )
}
