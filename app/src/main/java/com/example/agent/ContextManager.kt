// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.llm.ChatRequest
import com.example.llm.LlmClient
import com.example.llm.LlmContentPart
import com.example.llm.LlmEvent
import com.example.llm.LlmMessage
import kotlinx.coroutines.flow.collect
import com.example.util.JsonHelper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

class ContextManager {

    fun estimateTokens(text: String): Long = (text.length / 4L).coerceAtLeast(1L)

    fun estimateHistoryTokens(system: String, messages: List<LlmMessage>): Long {
        var total = estimateTokens(system)
        for (msg in messages) {
            for (part in msg.parts) {
                total += when (part) {
                    is LlmContentPart.Text -> estimateTokens(part.text)
                    is LlmContentPart.Reasoning -> estimateTokens(part.text)
                    is LlmContentPart.ToolUse -> estimateTokens(part.name + part.argumentsJson)
                    is LlmContentPart.ToolResult -> estimateTokens(part.output)
                    is LlmContentPart.ImageBase64 -> 768L
                }
            }
        }
        return total
    }

    fun needsCompaction(
        system: String,
        messages: List<LlmMessage>,
        contextWindowTokens: Int,
        thresholdRatio: Double
    ): Boolean {
        if (messages.size < 8) return false
        val used = estimateHistoryTokens(system, messages)
        return used > (contextWindowTokens * thresholdRatio).toLong()
    }

    suspend fun compact(
        messages: List<LlmMessage>,
        llmClient: LlmClient?,
        modelId: String?
    ): List<LlmMessage> {
        if (messages.size <= 4) return messages
        val keepRecentCount = 6.coerceAtMost(messages.size - 1)
        var cut = messages.size - keepRecentCount
        // Never start the kept tail with a tool result: it must stay attached to its assistant tool_calls message.
        while (cut > 0 && messages[cut].role == "tool") cut--
        if (cut <= 0) return messages
        val older = messages.subList(0, cut)
        val recent = messages.subList(cut, messages.size)

        val transcript = older.joinToString("\n") { m ->
            val txt = m.plainText().ifBlank {
                m.toolCalls().joinToString { "${it.name}(${it.argumentsJson.take(120)})" } +
                    m.toolResults().joinToString { "[${it.toolName}: ${it.output.take(180)}]" }
            }
            "[${m.role.uppercase()}]: ${txt.take(600)}"
        }.take(24_000)

        var summaryText: String? = null
        if (llmClient != null && !modelId.isNullOrBlank()) {
            val sb = StringBuilder()
            runCatching {
                val req = ChatRequest(
                    model = modelId,
                    system = "Summarize this coding session for your own future use: the user's goal and requirements, key decisions, files created/modified (exact paths and symbol names), commands run and their outcomes, errors found and how they were fixed, and the exact current state plus the next steps. Be concise but complete.",
                    messages = listOf(
                        LlmMessage(
                            id = "compact_req",
                            role = "user",
                            parts = listOf(LlmContentPart.Text(transcript))
                        )
                    ),
                    maxTokens = 4096,
                    supportsTools = false
                )
                llmClient.stream(req).collect { evt ->
                    if (evt is LlmEvent.TextDelta) sb.append(evt.text)
                }
            }
            if (sb.isNotBlank()) summaryText = sb.toString().trim()
        }

        val finalSummary = summaryText ?: buildDeterministicSummary(older)
        val summaryMsg = LlmMessage(
            id = "summary_" + UUID.randomUUID().toString().take(8),
            role = "user",
            parts = listOf(LlmContentPart.Text("[Compacted Context Summary]\n$finalSummary")),
            isCompactedSummary = true
        )
        return listOf(summaryMsg) + recent
    }

    private fun buildDeterministicSummary(older: List<LlmMessage>): String {
        val userAsks = older.filter { it.role == "user" }.map { it.plainText().take(160) }.filter { it.isNotBlank() }
        val toolsUsed = older.flatMap { it.toolCalls() }.map { it.name }.distinct()
        return buildString {
            appendLine("Previous ${older.size} messages compacted:")
            if (userAsks.isNotEmpty()) {
                appendLine("User requests: ${userAsks.joinToString(" | ")}")
            }
            if (toolsUsed.isNotEmpty()) {
                appendLine("Tools invoked: ${toolsUsed.joinToString(", ")}")
            }
        }.trim()
    }

    /**
     * Shrinks what is SENT to the model (the stored chat stays complete): old reasoning is dropped, old tool outputs
     * and file contents inside old tool-call arguments are trimmed. Typically cuts long-session token use several-fold.
     */
    fun slimForRequest(messages: List<LlmMessage>, keepRecent: Int = 12): List<LlmMessage> {
        if (messages.size <= keepRecent) return messages
        val cut = messages.size - keepRecent
        return messages.mapIndexed { i, m ->
            if (i >= cut || m.isCompactedSummary) m else {
                val newParts = m.parts.mapNotNull { p ->
                    when (p) {
                        is LlmContentPart.Reasoning -> null
                        is LlmContentPart.ImageBase64 -> LlmContentPart.Text("[image omitted to save tokens]")
                        is LlmContentPart.ToolResult ->
                            if (p.output.length > 500) p.copy(
                                output = p.output.take(300) + "\n[... ${p.output.length - 300} chars omitted to save tokens; re-run the tool if you need them]"
                            ) else p
                        is LlmContentPart.ToolUse ->
                            if (p.argumentsJson.length > 600) p.copy(argumentsJson = shrinkArgs(p.argumentsJson)) else p
                        is LlmContentPart.Text ->
                            if (m.role == "assistant" && p.text.length > 1500) p.copy(text = p.text.take(900) + "\n[...trimmed]") else p
                        else -> p
                    }
                }
                m.copy(parts = if (newParts.isEmpty()) listOf(LlmContentPart.Text("[omitted]")) else newParts)
            }
        }
    }

    private fun shrinkArgs(json: String): String {
        val el = JsonHelper.parseOrNull(json) ?: return "{}"
        fun walk(e: JsonElement): JsonElement = when (e) {
            is JsonPrimitive ->
                if (e.isString && e.content.length > 300)
                    JsonPrimitive(e.content.take(160) + "...[${e.content.length - 160} chars omitted]")
                else e
            is JsonObject -> JsonObject(e.mapValues { walk(it.value) })
            is JsonArray -> JsonArray(e.map { walk(it) })
        }
        return walk(el).toString()
    }

    /** For text-only models: replace images with a clear note so the model can tell the user instead of failing silently. */
    fun stripImages(messages: List<LlmMessage>): List<LlmMessage> = messages.map { m ->
        if (m.parts.none { it is LlmContentPart.ImageBase64 }) m
        else m.copy(parts = m.parts.map { p ->
            if (p is LlmContentPart.ImageBase64)
                LlmContentPart.Text("[The user attached an image (${p.fileName}), but the current model cannot receive images. Tell the user to switch to a model marked Vision, or run /vision to test.]")
            else p
        })
    }
}
