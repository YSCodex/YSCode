// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.llm.LlmContentPart
import com.example.llm.LlmMessage
import com.example.util.JsonHelper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class FileOp(val path: String, val writes: Int, val edits: Int)
data class CommandRun(val command: String, val failed: Boolean)

/** Everything the agent did in a session, including work before any context compaction. */
data class WorkLog(
    val files: List<FileOp>,
    val commands: List<CommandRun>,
    val toolCounts: List<Pair<String, Int>>,
    val skills: List<String>,
    val mcpTools: List<String>,
    val failedCalls: Int,
    val totalMessages: Int,
    val compactions: Int
)

object WorkLogBuilder {
    private val writeTools = setOf("write_file", "create_file")
    private val editTools = setOf("edit_file", "multi_edit", "apply_patch", "str_replace")

    fun build(messages: List<LlmMessage>): WorkLog {
        val failedIds = HashSet<String>()
        messages.forEach { m ->
            m.parts.forEach { p -> if (p is LlmContentPart.ToolResult && p.isError) failedIds += p.toolCallId }
        }
        val writes = LinkedHashMap<String, Int>()
        val edits = LinkedHashMap<String, Int>()
        val commands = ArrayList<CommandRun>()
        val counts = LinkedHashMap<String, Int>()
        val skills = LinkedHashSet<String>()
        val mcp = LinkedHashSet<String>()

        for (m in messages) {
            if (m.role != "assistant") continue
            for (p in m.parts) {
                if (p !is LlmContentPart.ToolUse) continue
                counts[p.name] = (counts[p.name] ?: 0) + 1
                val args = (JsonHelper.parseOrNull(p.argumentsJson) as? JsonObject)
                fun str(k: String) = (args?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    p.name in writeTools -> (str("path") ?: str("file_path"))?.let { writes[it] = (writes[it] ?: 0) + 1 }
                    p.name in editTools -> (str("path") ?: str("file_path"))?.let { edits[it] = (edits[it] ?: 0) + 1 }
                    p.name == "bash" -> str("command")?.let { commands += CommandRun(it.trim().take(300), p.id in failedIds) }
                    p.name == "use_skill" -> str("name")?.let { skills += it }
                    p.name.startsWith("mcp__") -> mcp += p.name
                }
            }
        }
        val paths = (writes.keys + edits.keys).distinct()
        return WorkLog(
            files = paths.map { FileOp(it, writes[it] ?: 0, edits[it] ?: 0) },
            commands = commands,
            toolCounts = counts.entries.sortedByDescending { it.value }.map { it.key to it.value },
            skills = skills.toList(),
            mcpTools = mcp.toList(),
            failedCalls = failedIds.size,
            totalMessages = messages.size,
            compactions = messages.count { it.isCompactedSummary }
        )
    }
}
