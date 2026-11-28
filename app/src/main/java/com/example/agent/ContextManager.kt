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
        val keepRecentCount = KEEP_RECENT.coerceAtMost(messages.size - 1)
        var cut = messages.size - keepRecentCount
        // Never start the kept tail with a tool result: it must stay attached to its assistant tool_calls message.
        while (cut > 0 && messages[cut].role == "tool") cut--
        if (cut <= 0) return messages
        val older = messages.subList(0, cut)
        val recent = messages.subList(cut, messages.size)

        // The previous summary is the agent's long-term memory: it is merged in full, never cut down to a few hundred characters.
        val previousSummary = older.firstOrNull { it.isCompactedSummary }?.plainText()
            ?.removePrefix("[Compacted Context Summary]")?.trim().orEmpty()
        val body = older.filter { !it.isCompactedSummary }

        val lines = body.map { m ->
            when (m.role) {
                "user" -> "[USER]: " + m.plainText().take(1_800)
                "assistant" -> {
                    val calls = m.toolCalls().joinToString { "${it.name}(${it.argumentsJson.take(90)})" }
                    "[ASSISTANT]: " + m.plainText().take(1_000) + if (calls.isNotBlank()) "  -> calls: $calls" else ""
                }
                else -> m.toolResults().joinToString("\n") {
                    "[TOOL ${it.toolName}${if (it.isError) " ERROR" else ""}]: " + it.output.take(if (it.isError) 400 else 220)
                }.ifBlank { "[TOOL]: " + m.plainText().take(220) }
            }
        }
        val transcript = lines.joinToString("\n").let { if (it.length > TRANSCRIPT_CAP) it.takeLast(TRANSCRIPT_CAP) else it }

        var summaryText: String? = null
        if (llmClient != null && !modelId.isNullOrBlank()) {
            val sb = StringBuilder()
            runCatching {
                val req = ChatRequest(
                    model = modelId,
                    system = SUMMARY_SYSTEM,
                    messages = listOf(
                        LlmMessage(
                            id = "compact_req",
                            role = "user",
                            parts = listOf(
                                LlmContentPart.Text(
                                    (if (previousSummary.isNotBlank()) "PREVIOUS SUMMARY (merge it, keep every fact that is still true):\n${previousSummary.take(14_000)}\n\n" else "") +
                                        "NEW CONVERSATION SINCE THEN:\n$transcript"
                                )
                            )
                        )
                    ),
                    maxTokens = 6000,
                    supportsTools = false
                )
                llmClient.stream(req).collect { evt ->
                    if (evt is LlmEvent.TextDelta) sb.append(evt.text)
                }
            }
            if (sb.isNotBlank()) summaryText = sb.toString().trim()
        }

        val finalSummary = buildString {
            append(summaryText ?: buildDeterministicSummary(body, previousSummary))
            val (files, commands) = touchedFilesAndCommands(body)
            if (files.isNotEmpty()) append("\n\n## Files touched (auto)\n").append(files.takeLast(40).joinToString("\n") { "- $it" })
            if (commands.isNotEmpty()) append("\n\n## Recent commands (auto)\n").append(commands.takeLast(15).joinToString("\n") { "- $it" })
            append("\n\nNote: older messages were compacted. For an exact earlier detail that is not above, call the recall tool before asking the user again.")
        }
        val summaryMsg = LlmMessage(
            id = "summary_" + UUID.randomUUID().toString().take(8),
            role = "user",
            parts = listOf(LlmContentPart.Text("[Compacted Context Summary]\n$finalSummary")),
            isCompactedSummary = true
        )
        return listOf(summaryMsg) + recent
    }

    private fun touchedFilesAndCommands(msgs: List<LlmMessage>): Pair<List<String>, List<String>> {
        val files = LinkedHashSet<String>()
        val commands = ArrayList<String>()
        for (tc in msgs.flatMap { it.toolCalls() }) {
            val obj = JsonHelper.parseOrNull(tc.argumentsJson) as? JsonObject ?: continue
            fun s(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (tc.name) {
                "write_file", "edit_file", "multi_edit" -> (s("path") ?: s("file_path"))?.let { files.add(it) }
                "bash" -> s("command")?.let { commands.add(it.replace(Regex("\\s+"), " ").take(110)) }
            }
        }
        return files.toList() to commands
    }

    private fun buildDeterministicSummary(body: List<LlmMessage>, previous: String): String {
        val userAsks = body.filter { it.role == "user" }.map { it.plainText().replace(Regex("\\s+"), " ").take(300) }.filter { it.isNotBlank() }
        val toolsUsed = body.flatMap { it.toolCalls() }.map { it.name }.distinct()
        return buildString {
            if (previous.isNotBlank()) appendLine("## Earlier summary (kept)\n${previous.take(14_000)}\n")
            appendLine("## Newer messages (${body.size}, compacted without a model summary)")
            if (userAsks.isNotEmpty()) appendLine("User said, in order:\n" + userAsks.takeLast(40).joinToString("\n") { "- $it" })
            if (toolsUsed.isNotEmpty()) appendLine("Tools used: ${toolsUsed.joinToString(", ")}")
        }.trim()
    }

    /**
     * Shrinks what is SENT to the model (the stored chat stays complete): old reasoning is dropped, old tool outputs
     * and file contents inside old tool-call arguments are trimmed. Typically cuts long-session token use several-fold.
     */
    fun slimForRequest(messages: List<LlmMessage>, keepRecent: Int = 16): List<LlmMessage> {
        if (messages.size <= keepRecent) return messages
        val cut = messages.size - keepRecent
        return messages.mapIndexed { i, m ->
            if (i >= cut || m.isCompactedSummary) m else {
                val newParts = m.parts.mapNotNull { p ->
                    when (p) {
                        is LlmContentPart.Reasoning -> null
                        is LlmContentPart.ImageBase64 -> LlmContentPart.Text("[image omitted to save tokens]")
                        is LlmContentPart.ToolResult -> {
                            val limit = if (p.isError) 1_400 else 900
                            if (p.output.length > limit) p.copy(output = headTail(p.output, limit * 5 / 9, limit * 4 / 9)) else p
                        }
                        is LlmContentPart.ToolUse ->
                            if (p.argumentsJson.length > 800) p.copy(argumentsJson = shrinkArgs(p.argumentsJson)) else p
                        is LlmContentPart.Text ->
                            // User messages are never trimmed: they hold the instructions and preferences.
                            if (m.role == "assistant" && p.text.length > 3_500) p.copy(text = headTail(p.text, 2_200, 900)) else p
                        else -> p
                    }
                }
                m.copy(parts = if (newParts.isEmpty()) listOf(LlmContentPart.Text("[omitted]")) else newParts)
            }
        }
    }

    private fun headTail(s: String, head: Int, tail: Int): String =
        s.take(head) + "\n[... ${s.length - head - tail} chars omitted to save tokens; re-run the tool or use the recall tool ...]\n" + s.takeLast(tail)

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

    private companion object {
        const val KEEP_RECENT = 10
        const val TRANSCRIPT_CAP = 48_000
        const val SUMMARY_SYSTEM = "You maintain the long-term memory of a coding agent. Write a faithful, dense summary of the session using exactly these " +
            "sections:\n## User goal and requirements\n## User preferences and corrections (quote the user's own words for anything they insisted on: " +
            "language, tone, style, \"don't do X\", ports, names)\n## Decisions made and why\n## Work done (files created or changed with exact paths, " +
            "commands and their results)\n## Problems found and fixes\n## Open items and next steps\n## Facts to remember (versions, URLs, ports, names, " +
            "numbers, settings). Rules: if a PREVIOUS SUMMARY is given, MERGE it: keep every fact from it that is still true, update what changed and " +
            "drop only what is clearly obsolete. Never invent facts. Keep exact identifiers. Never include secrets, tokens or passwords. About 1200 words at most."
    }
}
