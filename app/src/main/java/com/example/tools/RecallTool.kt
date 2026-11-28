// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.data.MessageDao
import com.example.data.SessionDao
import com.example.data.SessionEntity
import com.example.llm.LlmContentPart
import com.example.llm.LlmMessage
import com.example.util.JsonHelper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Searches the complete stored history. The model only receives a compacted and trimmed copy of old messages, but everything is kept
 * in the database, so this tool lets the agent look up an exact earlier detail instead of guessing or asking the user again.
 */
class RecallTool(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao
) : Tool {
    override val name = "recall"
    override val description = "Search everything said and done earlier: user instructions, decisions, file paths, commands, errors, tool output. " +
        "It reads the full stored chat, including parts that were compacted or trimmed from your context. Use it BEFORE asking the user to repeat " +
        "something, and whenever you need an exact earlier detail (a number, path, name, setting, error). scope=chat searches this chat (default); " +
        "scope=project also searches the other chats of this project."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string"); put("description", "Words to look for (any order), e.g. 'port database password hint' or 'screenshot folder'") }
            putJsonObject("scope") { put("type", "string"); put("description", "chat (default) or project") }
            putJsonObject("limit") { put("type", "integer"); put("description", "Max matches to show (default 6, max 12)") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    }

    private class Hit(val score: Double, val session: SessionEntity, val msg: LlmMessage, val text: String)

    private fun searchable(m: LlmMessage): String = buildString {
        for (p in m.parts) {
            when (p) {
                is LlmContentPart.Text -> append(p.text).append('\n')
                is LlmContentPart.ToolUse -> append("[tool call ").append(p.name).append("] ").append(p.argumentsJson.take(1500)).append('\n')
                is LlmContentPart.ToolResult -> append("[tool result ").append(p.toolName).append("] ").append(p.output.take(3000)).append('\n')
                else -> {}
            }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.error("Missing 'query'")
        val words = Regex("[\\p{L}\\p{N}_./:-]{2,}").findAll(query.lowercase()).map { it.value }.distinct().toList()
        if (words.isEmpty()) return ToolResult.error("Query has no searchable words")
        val limit = (args.int("limit") ?: 6).coerceIn(1, 12)
        val current = sessionDao.getById(ctx.sessionId)
        val sessions: List<SessionEntity> = if (args.str("scope")?.lowercase() == "project" && current != null) {
            sessionDao.getAll().filter { it.projectId == current.projectId }.take(12)
        } else listOfNotNull(current)
        if (sessions.isEmpty()) return ToolResult.error("No stored chat found to search")

        val need = maxOf(1, ceil(words.size * 0.6).toInt())
        val phrase = query.lowercase()
        val hits = ArrayList<Hit>()
        for (session in sessions) {
            val entities = messageDao.getForSession(session.id).takeLast(2500)
            for (e in entities) {
                val msg = runCatching { JsonHelper.json.decodeFromString<LlmMessage>(e.contentJson) }.getOrNull() ?: continue
                val text = searchable(msg)
                if (text.isBlank()) continue
                val lower = text.lowercase()
                val found = words.count { lower.contains(it) }
                if (found < need) continue
                var score = found.toDouble() / words.size
                if (lower.contains(phrase)) score += 1.0
                if (msg.role == "user" && !msg.isCompactedSummary) score += 0.3
                if (session.id == ctx.sessionId) score += 0.1
                hits.add(Hit(score, session, msg, text))
            }
        }
        if (hits.isEmpty()) {
            return ToolResult.ok(
                "No match for \"$query\". Try fewer or different words${if (args.str("scope")?.lowercase() != "project") ", or scope=project" else ""}.",
                "recall: none"
            )
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val top = hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.msg.createdAt }).take(limit)
        val out = StringBuilder()
        out.append("Found ${hits.size} matching message(s) for \"$query\"; showing ${top.size}:\n")
        for (h in top) {
            val lower = h.text.lowercase()
            val anchor = words.filter { lower.contains(it) }.maxByOrNull { it.length }?.let { lower.indexOf(it) } ?: 0
            val start = (anchor - 250).coerceAtLeast(0)
            val end = (anchor + 450).coerceAtMost(h.text.length)
            val snippet = h.text.substring(start, end).replace(Regex("\\s+"), " ").trim()
            val where = if (h.session.id == ctx.sessionId) "this chat" else "chat \"${h.session.title.take(40)}\""
            val kind = if (h.msg.isCompactedSummary) "earlier summary" else h.msg.role
            out.append("\n- ").append(where).append(", ").append(fmt.format(Date(h.msg.createdAt))).append(", ").append(kind).append(":\n  ")
            if (start > 0) out.append("... ")
            out.append(snippet)
            if (end < h.text.length) out.append(" ...")
            out.append('\n')
            if (out.length > 7_000) break
        }
        return ToolResult.ok(out.toString().take(7_500), "recall: ${top.size} of ${hits.size}")
    }
}
