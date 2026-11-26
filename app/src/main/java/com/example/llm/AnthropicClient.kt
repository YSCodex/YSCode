// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
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

class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val extraHeaders: Map<String, String> = emptyMap()
) : LlmClient {

    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val cleanBase = baseUrl.trimEnd('/')

    private fun endpoint(path: String): String {
        val p = path.trimStart('/')
        return if (cleanBase.endsWith("/v1")) "$cleanBase/$p" else "$cleanBase/v1/$p"
    }

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val payload = buildPayload(request, stream = true)
        val reqBuilder = Request.Builder()
            .url(endpoint("messages"))
            .post(payload.toString().toRequestBody(mediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("anthropic-version", "2023-06-01")
        if (!apiKey.isNullOrBlank()) {
            reqBuilder.header("x-api-key", apiKey)
        }
        extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }

        val toolBlocks = linkedMapOf<Int, MutableToolBuffer>()
        var inputTokens = 0L
        var outputTokens = 0L
        var emittedDone = false
        var finishSeen = "stop"

        try {
            executeSseStream(
                client = HttpClients.streamingClient,
                request = reqBuilder.build(),
                onHttpError = { code, errBody ->
                    val retryable = code == 429 || code == 529 || code in 500..599
                    emit(LlmEvent.Error("HTTP $code: ${extractError(errBody)}", httpCode = code, retryable = retryable))
                    emittedDone = true
                },
                onFrame = { frame ->
                    val root = JsonHelper.parseOrNull(frame.data)?.jsonObject ?: return@executeSseStream
                    val type = frame.event ?: root["type"]?.jsonPrimitive?.contentOrNull

                    when (type) {
                        "message_start" -> {
                            val usage = root["message"]?.jsonObject?.get("usage")?.jsonObject
                            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                        }
                        "content_block_start" -> {
                            val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                            val block = root["content_block"]?.jsonObject ?: return@executeSseStream
                            if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                                val id = block["id"]?.jsonPrimitive?.contentOrNull ?: "toolu_${System.nanoTime()}"
                                val name = block["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                                toolBlocks[index] = MutableToolBuffer(id, name)
                                emit(LlmEvent.ToolCallStart(id, name))
                            }
                        }
                        "content_block_delta" -> {
                            val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                            val delta = root["delta"]?.jsonObject ?: return@executeSseStream
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> {
                                    val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (text.isNotEmpty()) emit(LlmEvent.TextDelta(text))
                                }
                                "thinking_delta" -> {
                                    val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (thinking.isNotEmpty()) emit(LlmEvent.ReasoningDelta(thinking))
                                }
                                "input_json_delta" -> {
                                    val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    toolBlocks[index]?.let { buf ->
                                        buf.args.append(partial)
                                        emit(LlmEvent.ToolCallDelta(buf.id, partial))
                                    }
                                }
                            }
                        }
                        "content_block_stop" -> {
                            val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                            toolBlocks.remove(index)?.let { buf ->
                                val args = buf.args.toString().ifBlank { "{}" }
                                emit(LlmEvent.ToolCallEnd(buf.id, buf.name, args))
                            }
                        }
                        "message_delta" -> {
                            root["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { finishSeen = it }
                            val usage = root["usage"]?.jsonObject
                            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: outputTokens
                            if (inputTokens > 0 || outputTokens > 0) {
                                emit(LlmEvent.Usage(inputTokens, outputTokens))
                            }
                        }
                        "message_stop" -> {
                            emit(LlmEvent.Done(finishSeen))
                            emittedDone = true
                        }
                        "error" -> {
                            val msg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: frame.data
                            emit(LlmEvent.Error(msg, retryable = false))
                            emittedDone = true
                        }
                    }
                }
            )
            if (!emittedDone) {
                toolBlocks.values.forEach { buf ->
                    emit(LlmEvent.ToolCallEnd(buf.id, buf.name, buf.args.toString().ifBlank { "{}" }))
                }
                emit(LlmEvent.Done(finishSeen))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(LlmEvent.Error(e.message ?: "Anthropic stream error", retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(): Result<List<ModelConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBuilder = Request.Builder()
                .url(endpoint("models"))
                .get()
                .header("anthropic-version", "2023-06-01")
            if (!apiKey.isNullOrBlank()) reqBuilder.header("x-api-key", apiKey)
            extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }

            HttpClients.streamingClient.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${extractError(body)}")
                val data = JsonHelper.parseOrNull(body)?.jsonObject?.get("data")?.jsonArray ?: error("Missing data")
                data.mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = obj["display_name"]?.jsonPrimitive?.contentOrNull ?: id
                    ModelConfig(id, name, 200_000, supportsTools = true, supportsVision = true, supportsReasoning = true)
                }
            }
        }
    }

    override suspend fun testConnection(modelId: String?): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        fetchModels().fold(
            onSuccess = { models ->
                val ms = System.currentTimeMillis() - start
                ConnectionTestResult(true, "Anthropic connected (${models.size} models in ${ms}ms)", ms, models)
            },
            onFailure = { err ->
                ConnectionTestResult(false, err.message ?: "Connection failed", System.currentTimeMillis() - start)
            }
        )
    }

    private fun buildPayload(request: ChatRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", stream)
        put("max_tokens", request.maxTokens ?: 8192)
        request.temperature?.let { put("temperature", it) }
        if (request.system.isNotBlank()) {
            put("system", request.system)
        }
        put("messages", buildJsonArray {
            for (msg in request.messages) {
                when (msg.role) {
                    "user" -> {
                        val images = msg.parts.filterIsInstance<LlmContentPart.ImageBase64>()
                        val text = msg.plainText()
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                images.forEach { img ->
                                    add(buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", img.mimeType)
                                            put("data", img.dataBase64)
                                        }
                                    })
                                }
                                if (text.isNotBlank() || images.isEmpty()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", text.ifBlank { " " })
                                    })
                                }
                            })
                        })
                    }
                    "assistant" -> {
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", buildJsonArray {
                                val text = msg.plainText()
                                if (text.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    })
                                }
                                msg.toolCalls().forEach { tc ->
                                    val inputObj = JsonHelper.parseOrRepairObject(tc.argumentsJson)
                                        .getOrDefault(JsonObject(emptyMap()))
                                    add(buildJsonObject {
                                        put("type", "tool_use")
                                        put("id", tc.id)
                                        put("name", tc.name)
                                        put("input", inputObj)
                                    })
                                }
                            })
                        })
                    }
                    "tool" -> {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                msg.toolResults().forEach { tr ->
                                    add(buildJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", tr.toolCallId)
                                        put("content", tr.output)
                                        if (tr.isError) put("is_error", true)
                                    })
                                }
                            })
                        })
                    }
                }
            }
        })
        if (request.supportsTools && request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parametersSchema)
                    })
                }
            })
        }
    }

    private fun extractError(body: String): String {
        val obj = JsonHelper.parseOrNull(body)?.jsonObject
        return obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: body.take(300).ifBlank { "Anthropic error" }
    }

    private data class MutableToolBuffer(
        val id: String,
        val name: String,
        val args: StringBuilder = StringBuilder()
    )
}
