// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val extraHeaders: Map<String, String> = emptyMap()
) : LlmClient {

    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val cleanBase = baseUrl.trimEnd('/')

    private fun streamUrl(model: String): String {
        val modelPath = if (model.startsWith("models/")) model else "models/$model"
        return "$cleanBase/v1beta/$modelPath:streamGenerateContent?alt=sse"
    }

    private fun modelsUrl(): String = "$cleanBase/v1beta/models"

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val payload = buildPayload(request)
        val reqBuilder = Request.Builder()
            .url(streamUrl(request.model))
            .post(payload.toString().toRequestBody(mediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        if (!apiKey.isNullOrBlank()) {
            reqBuilder.header("x-goog-api-key", apiKey)
        }
        extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }

        var emittedDone = false
        var finishSeen = "stop"
        try {
            executeSseStream(
                client = HttpClients.streamingClient,
                request = reqBuilder.build(),
                onHttpError = { code, errBody ->
                    val retryable = code == 429 || code in 500..599
                    emit(LlmEvent.Error("HTTP $code: ${extractError(errBody)}", httpCode = code, retryable = retryable))
                    emittedDone = true
                },
                onFrame = { frame ->
                    val root = JsonHelper.parseOrNull(frame.data)?.jsonObject ?: return@executeSseStream
                    root["usageMetadata"]?.jsonObject?.let { usage ->
                        val inTok = usage["promptTokenCount"]?.jsonPrimitive?.longOrNull ?: 0L
                        val outTok = usage["candidatesTokenCount"]?.jsonPrimitive?.longOrNull ?: 0L
                        if (inTok > 0 || outTok > 0) {
                            emit(LlmEvent.Usage(inTok, outTok))
                        }
                    }

                    val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@executeSseStream
                    candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishSeen = it }
                    val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                    for (partEl in parts) {
                        val part = partEl.jsonObject
                        val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                        val text = part["text"]?.jsonPrimitive?.contentOrNull
                        if (!text.isNullOrEmpty()) {
                            if (isThought) emit(LlmEvent.ReasoningDelta(text))
                            else emit(LlmEvent.TextDelta(text))
                        }
                        part["functionCall"]?.jsonObject?.let { fc ->
                            val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                            val argsObj = fc["args"]?.jsonObject ?: JsonObject(emptyMap())
                            val callId = "gemini_${name}_${System.nanoTime()}"
                            emit(LlmEvent.ToolCallStart(callId, name))
                            emit(LlmEvent.ToolCallEnd(callId, name, argsObj.toString()))
                        }
                    }
                }
            )
            if (!emittedDone) {
                emit(LlmEvent.Done(finishSeen))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(LlmEvent.Error(e.message ?: "Gemini stream error", retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(): Result<List<ModelConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBuilder = Request.Builder().url(modelsUrl()).get()
            if (!apiKey.isNullOrBlank()) reqBuilder.header("x-goog-api-key", apiKey)
            extraHeaders.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }

            HttpClients.streamingClient.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${extractError(body)}")
                val arr = JsonHelper.parseOrNull(body)?.jsonObject?.get("models")?.jsonArray ?: error("Missing models")
                arr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val rawName = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val id = rawName.removePrefix("models/")
                    val methods = obj["supportedGenerationMethods"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    if ("generateContent" !in methods) return@mapNotNull null
                    val display = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: id
                    ModelConfig(
                        id = id,
                        displayName = display,
                        contextWindow = 1_000_000,
                        supportsTools = true,
                        supportsVision = true,
                        supportsReasoning = id.contains("2.5") || id.contains("thinking")
                    )
                }
            }
        }
    }

    override suspend fun testConnection(modelId: String?): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        fetchModels().fold(
            onSuccess = { models ->
                val ms = System.currentTimeMillis() - start
                ConnectionTestResult(true, "Gemini connected (${models.size} models in ${ms}ms)", ms, models)
            },
            onFailure = { err ->
                ConnectionTestResult(false, err.message ?: "Connection failed", System.currentTimeMillis() - start)
            }
        )
    }

    private fun buildPayload(request: ChatRequest): JsonObject = buildJsonObject {
        if (request.system.isNotBlank()) {
            putJsonObject("systemInstruction") {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", request.system) })
                })
            }
        }
        put("contents", buildJsonArray {
            for (msg in request.messages) {
                when (msg.role) {
                    "user" -> {
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                val text = msg.plainText()
                                if (text.isNotEmpty()) add(buildJsonObject { put("text", text) })
                                msg.parts.filterIsInstance<LlmContentPart.ImageBase64>().forEach { img ->
                                    add(buildJsonObject {
                                        putJsonObject("inlineData") {
                                            put("mimeType", img.mimeType)
                                            put("data", img.dataBase64)
                                        }
                                    })
                                }
                            })
                        })
                    }
                    "assistant" -> {
                        add(buildJsonObject {
                            put("role", "model")
                            put("parts", buildJsonArray {
                                val text = msg.plainText()
                                if (text.isNotEmpty()) add(buildJsonObject { put("text", text) })
                                msg.toolCalls().forEach { tc ->
                                    val argsObj = JsonHelper.parseOrRepairObject(tc.argumentsJson)
                                        .getOrDefault(JsonObject(emptyMap()))
                                    add(buildJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", tc.name)
                                            put("args", argsObj)
                                        }
                                    })
                                }
                            })
                        })
                    }
                    "tool" -> {
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                msg.toolResults().forEach { tr ->
                                    add(buildJsonObject {
                                        putJsonObject("functionResponse") {
                                            put("name", tr.toolName)
                                            putJsonObject("response") {
                                                put("result", tr.output)
                                                put("is_error", tr.isError)
                                            }
                                        }
                                    })
                                }
                            })
                        })
                    }
                }
            }
        })

        putJsonObject("generationConfig") {
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("maxOutputTokens", it) }
        }

        if (request.supportsTools && request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        request.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", sanitizeGeminiSchema(tool.parametersSchema))
                            })
                        }
                    })
                })
            })
        }
    }

    /**
     * Gemini rejects "additionalProperties" in Schema objects; recursively strip unsupported keywords.
     */
    private fun sanitizeGeminiSchema(obj: JsonObject): JsonObject {
        return buildJsonObject {
            for ((k, v) in obj) {
                if (k == "additionalProperties" || k == "\$schema") continue
                when (v) {
                    is JsonObject -> put(k, sanitizeGeminiSchema(v))
                    else -> put(k, v)
                }
            }
        }
    }

    private fun extractError(body: String): String {
        val obj = JsonHelper.parseOrNull(body)?.jsonObject
        return obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: body.take(300).ifBlank { "Gemini error" }
    }
}
