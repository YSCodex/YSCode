// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.llm.LlmContentPart
import com.example.llm.LlmMessage
import com.example.tools.ToolDiffInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class TimelineItem {
    abstract val stableKey: String

    data class UserTurn(
        override val stableKey: String,
        val message: LlmMessage,
        val text: String,
        val attachmentCount: Int,
        val isCompactedSummary: Boolean
    ) : TimelineItem()

    data class AssistantTurn(
        override val stableKey: String,
        val message: LlmMessage,
        val reasoning: String?,
        val reasoningDurationSec: Long,
        val text: String?,
        val toolSteps: List<TimelineToolStep>
    ) : TimelineItem()
}

sealed class TimelineToolStep {
    abstract val stableKey: String

    data class SingleCall(
        override val stableKey: String,
        val tool: StreamingToolState
    ) : TimelineToolStep()

    data class ReadGroup(
        override val stableKey: String,
        val items: List<StreamingToolState>
    ) : TimelineToolStep()
}

object TimelineMapper {
    private val readGroupTools = setOf("read_file", "glob", "grep", "list_dir", "repo_map")
    private val interactiveTools = setOf("ask_user", "exit_plan_mode")

    /**
     * Converts raw persisted [LlmMessage] history (user / assistant(ToolUse) / tool(ToolResult))
     * into a clean unified Claude Code / Codex style timeline:
     * - Merges each ToolUse with its matching ToolResult (by toolCallId).
     * - Groups consecutive read-only exploration calls into expandable ReadGroups.
     * - Suppresses redundant raw ask_user / exit_plan_mode cards while interactive UI is active,
     *   and merges consecutive assistant tool-loop turns belonging to the same agent reply when helpful.
     */
    fun mapMessagesToTimeline(
        messages: List<LlmMessage>,
        hasPendingQuestion: Boolean,
        hasPendingPlan: Boolean
    ): List<TimelineItem> {
        // Index all tool results by toolCallId across the entire session
        val resultsById = LinkedHashMap<String, LlmContentPart.ToolResult>()
        for (m in messages) {
            if (m.role == "tool") {
                m.toolResults().forEach { tr ->
                    resultsById[tr.toolCallId] = tr
                }
            }
        }

        val items = mutableListOf<TimelineItem>()
        for (m in messages) {
            when (m.role) {
                "user" -> {
                    val imgCount = m.parts.filterIsInstance<LlmContentPart.ImageBase64>().size
                    items += TimelineItem.UserTurn(
                        stableKey = "u_${m.id}",
                        message = m,
                        text = m.plainText(),
                        attachmentCount = imgCount,
                        isCompactedSummary = m.isCompactedSummary
                    )
                }
                "assistant" -> {
                    val reasoning = m.reasoningText().takeIf { it.isNotBlank() }
                    val durSec = m.parts.filterIsInstance<LlmContentPart.Reasoning>().sumOf { it.durationMs } / 1000L
                    val text = m.plainText().takeIf { it.isNotBlank() }

                    val mergedTools = m.toolCalls().mapNotNull { tc ->
                        val res = resultsById[tc.id]
                        if (tc.name == "ask_user" && hasPendingQuestion && res == null) return@mapNotNull null
                        if (tc.name == "exit_plan_mode" && hasPendingPlan && res == null) return@mapNotNull null

                        val status = when {
                            res == null -> "done"
                            res.isError -> "error"
                            else -> "done"
                        }
                        val summary = when {
                            res == null -> "ok"
                            res.isError -> "error"
                            else -> summarizeResult(tc.name, tc.argumentsJson, res.output)
                        }
                        val fullOut = buildString {
                            appendLine("Args: ${tc.argumentsJson}")
                            if (res != null) {
                                appendLine("---")
                                append(res.output)
                            }
                        }.trim()

                        StreamingToolState(
                            id = tc.id,
                            name = tc.name,
                            argsPreview = formatArgsPreview(tc.name, tc.argumentsJson),
                            status = status,
                            outputPreview = res?.output?.lines()?.take(4)?.joinToString("\n").orEmpty(),
                            fullOutput = fullOut,
                            summary = summary,
                            diff = extractDiffHint(tc.name, tc.argumentsJson, res?.output.orEmpty())
                        )
                    }

                    val groupedSteps = groupToolSteps(m.id, mergedTools)
                    if (reasoning != null || text != null || groupedSteps.isNotEmpty()) {
                        items += TimelineItem.AssistantTurn(
                            stableKey = "a_${m.id}",
                            message = m,
                            reasoning = reasoning,
                            reasoningDurationSec = durSec,
                            text = text,
                            toolSteps = groupedSteps
                        )
                    }
                }
                // "tool" role messages are merged into their parent assistant ToolUse cards above!
                // Orphaned tool messages (e.g. from legacy compacted logs) are only shown if unmatched.
                "tool" -> {
                    val parentIds = messages
                        .asSequence()
                        .filter { it.role == "assistant" }
                        .flatMap { it.toolCalls().asSequence() }
                        .map { it.id }
                        .toSet()
                    val orphans = m.toolResults().filter { it.toolCallId !in parentIds }
                    if (orphans.isNotEmpty()) {
                        val states = orphans.map { tr ->
                            StreamingToolState(
                                id = tr.toolCallId,
                                name = tr.toolName,
                                argsPreview = if (tr.isError) "Error" else "Result",
                                status = if (tr.isError) "error" else "done",
                                fullOutput = tr.output,
                                summary = if (tr.isError) "error" else "ok"
                            )
                        }
                        items += TimelineItem.AssistantTurn(
                            stableKey = "t_${m.id}",
                            message = m,
                            reasoning = null,
                            reasoningDurationSec = 0L,
                            text = null,
                            toolSteps = groupToolSteps(m.id, states)
                        )
                    }
                }
            }
        }
        return items
    }

    fun groupToolSteps(prefixKey: String, tools: List<StreamingToolState>): List<TimelineToolStep> {
        if (tools.isEmpty()) return emptyList()
        val out = mutableListOf<TimelineToolStep>()
        val readBuf = mutableListOf<StreamingToolState>()

        fun flushReads(idx: Int) {
            if (readBuf.size >= 2) {
                out += TimelineToolStep.ReadGroup(
                    stableKey = "${prefixKey}_rg_$idx",
                    items = readBuf.toList()
                )
            } else {
                readBuf.forEach { t ->
                    out += TimelineToolStep.SingleCall("${prefixKey}_sc_${t.id}", t)
                }
            }
            readBuf.clear()
        }

        tools.forEachIndexed { idx, t ->
            if (t.name in readGroupTools && t.status != "error" && t.status != "denied") {
                readBuf += t
            } else {
                flushReads(idx)
                out += TimelineToolStep.SingleCall("${prefixKey}_sc_${t.id}", t)
            }
        }
        flushReads(tools.size)
        return out
    }

    fun formatArgsPreview(toolName: String, argsJson: String): String {
        val obj = runCatching {
            com.example.util.JsonHelper.parseOrNull(argsJson) as? JsonObject
        }.getOrNull() ?: return argsJson.take(100)

        fun str(k: String): String? = (obj[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        return when (toolName) {
            "read_file", "write_file", "edit_file", "multi_edit", "list_dir" -> str("path") ?: argsJson.take(90)
            "glob" -> str("pattern") ?: argsJson.take(90)
            "grep" -> str("pattern")?.let { "/$it/" } ?: argsJson.take(90)
            "bash" -> str("command")?.take(90) ?: argsJson.take(90)
            "web_search" -> str("query")?.let { "\"$it\"" } ?: argsJson.take(90)
            "web_fetch" -> str("url")?.take(90) ?: argsJson.take(90)
            "recall" -> str("query")?.take(80) ?: argsJson.take(80)
            "browser" -> (str("action") ?: "browser") + (str("url")?.let { " ${it.take(70)}" } ?: str("selector")?.let { " ${it.take(50)}" } ?: str("index")?.let { " #$it" } ?: str("x")?.let { " $it,${str("y").orEmpty()}" } ?: str("key")?.let { " $it" } ?: "")
            "repo_map" -> str("path_prefix")?.let { "prefix: $it" } ?: "full repo"
            "ask_user" -> str("question")?.take(80) ?: "question"
            "exit_plan_mode" -> "Plan proposal"
            "task" -> "${str("role") ?: "subagent"}: ${str("prompt")?.take(60).orEmpty()}"
            "parallel_tasks" -> "parallel subagents"
            "memory_write" -> str("entry")?.take(70) ?: "YSCODE.md"
            else -> argsJson.take(100)
        }
    }

    private fun summarizeResult(toolName: String, argsJson: String, output: String): String {
        return when (toolName) {
            "read_file" -> "${output.lines().size} lines"
            "glob", "grep", "list_dir" -> "${output.lines().count { it.isNotBlank() }} hits"
            "web_search" -> output.lines().count { it.trim().matches(Regex("^\\d+\\..*")) }.let { "$it results" }
            "ask_user" -> output.removePrefix("User answered:").trim().take(28).ifBlank { "answered" }
            "exit_plan_mode" -> if (output.contains("Approved", ignoreCase = true)) "approved" else "reviewed"
            else -> "ok"
        }
    }

    private fun extractDiffHint(toolName: String, argsJson: String, output: String): ToolDiffInfo? {
        // Live streaming diffs come directly from StreamingToolState.diff; persisted messages
        // keep minimal summary without re-inflating full file contents.
        return null
    }
}
