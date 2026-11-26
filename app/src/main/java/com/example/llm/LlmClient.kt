// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

interface LlmClient {
    fun stream(request: ChatRequest): Flow<LlmEvent>
    suspend fun fetchModels(): Result<List<ModelConfig>>
    suspend fun testConnection(modelId: String?): ConnectionTestResult
}

object HttpClients {
    val streamingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

data class SseFrame(
    val event: String?,
    val data: String
)

suspend fun executeSseStream(
    client: OkHttpClient,
    request: Request,
    onFrame: suspend (SseFrame) -> Unit,
    onHttpError: suspend (code: Int, body: String) -> Unit
) {
    val call = client.newCall(request)
    try {
        val response: Response = call.execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                onHttpError(resp.code, errBody)
                return
            }
            val body = resp.body ?: run {
                onHttpError(resp.code, "Empty response body")
                return
            }
            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            var currentEvent: String? = null
            val dataLines = mutableListOf<String>()

            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (dataLines.isNotEmpty()) {
                            val payload = dataLines.joinToString("\n")
                            onFrame(SseFrame(currentEvent, payload))
                            currentEvent = null
                            dataLines.clear()
                        }
                    }
                    line.startsWith("event:") -> {
                        currentEvent = line.removePrefix("event:").trim()
                    }
                    line.startsWith("data:") -> {
                        dataLines += line.removePrefix("data:").trimStart()
                    }
                    line.startsWith(":") -> {
                        // SSE keep-alive comment
                    }
                }
            }
            if (dataLines.isNotEmpty()) {
                onFrame(SseFrame(currentEvent, dataLines.joinToString("\n")))
            }
        }
    } catch (ce: CancellationException) {
        call.cancel()
        throw ce
    } catch (e: Throwable) {
        if (!call.isCanceled()) {
            throw e
        }
    }
}
