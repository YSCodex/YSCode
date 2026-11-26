// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.agent.MemoryManager
import com.example.agent.RepoMapIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RepoMapTool(
    private val repoMapIndex: RepoMapIndex
) : Tool {
    override val name = "repo_map"
    override val description = "Build or query the ranked multi-language project symbol map (classes, functions, exports, line numbers) without reading every file."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path_prefix") { put("type", "string"); put("description", "Optional relative folder/file prefix filter (e.g. 'src/agent')") }
            putJsonObject("max_chars") { put("type", "integer"); put("description", "Max character budget (default 2800)") }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val prefix = args.str("path_prefix").orEmpty()
        val maxChars = (args.int("max_chars") ?: 2800).coerceIn(500, 12_000)
        val map = repoMapIndex.formatRepoMap(ctx.projectDir, prefix, maxChars)
        return ToolResult.ok(map, summary = "${map.lines().size} lines")
    }
}

class MemoryWriteTool(
    private val memoryManager: MemoryManager
) : Tool {
    override val name = "memory_write"
    override val description = "Save a durable project convention, build/test command, or user preference into YSCODE.md (project) or MEMORY.md (global)."
    override val risk = Risk.READ // Low-risk structured memory note append
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("section") {
                put("type", "string")
                put("description", "Section heading, e.g. 'Tech Stack & Commands', 'Coding Conventions', or 'User Preferences'")
            }
            putJsonObject("entry") { put("type", "string"); put("description", "Concise single-line memory note") }
            putJsonObject("scope") { put("type", "string"); put("description", "'project' (YSCODE.md) or 'global' (MEMORY.md)") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("entry")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val entry = args.str("entry")?.trim() ?: return ToolResult.error("Missing 'entry'")
        val section = args.str("section")?.ifBlank { "Learned Notes" } ?: "Learned Notes"
        val scope = args.str("scope")?.lowercase() ?: "project"
        val res = memoryManager.appendMemoryEntry(ctx.projectDir, section, entry, scope)
        return if (res.updated) {
            ToolResult.ok("Saved memory to ${if (scope == "global") "MEMORY.md" else "YSCODE.md"}: $entry", summary = "Memory saved")
        } else {
            ToolResult.ok("Memory entry already exists (deduplicated).", summary = "Already saved")
        }
    }
}

data class ParallelSubagentSpec(
    val role: String,
    val prompt: String,
    val modelOverride: String? = null
)

class ParallelTasksTool(
    private val runner: suspend (role: String, prompt: String, modelOverride: String?, ctx: ToolContext) -> String
) : Tool {
    override val name = "parallel_tasks"
    override val description = "Launch multiple specialized subagents (explorer, coder, reviewer) in parallel with isolated contexts and aggregate their results."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("tasks") {
                put("type", "array")
                put("description", "Up to 4 subagent tasks to run concurrently")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("role") {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("explorer"))
                                add(JsonPrimitive("coder"))
                                add(JsonPrimitive("reviewer"))
                            })
                        }
                        putJsonObject("prompt") { put("type", "string") }
                        putJsonObject("model") { put("type", "string") }
                    }
                    put("required", buildJsonArray {
                        add(JsonPrimitive("role"))
                        add(JsonPrimitive("prompt"))
                    })
                }
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("tasks")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.isSubagent) {
            return ToolResult.error("Nested parallel subagents are disallowed.")
        }
        val arr = args["tasks"]?.jsonArray ?: return ToolResult.error("Missing 'tasks' array")
        val specs = arr.take(4).mapNotNull { el ->
            val obj = el.jsonObject
            val role = obj.str("role")?.lowercase() ?: "explorer"
            val prompt = obj.str("prompt")?.trim() ?: return@mapNotNull null
            ParallelSubagentSpec(role = role, prompt = prompt, modelOverride = obj.str("model"))
        }
        if (specs.isEmpty()) return ToolResult.error("No valid tasks provided")

        val results = coroutineScope {
            specs.mapIndexed { idx, spec ->
                async {
                    ctx.onStreamLine("Started [${spec.role} #${idx + 1}]: ${spec.prompt.take(60)}")
                    val out = runCatching {
                        runner(spec.role, spec.prompt, spec.modelOverride, ctx)
                    }.getOrElse { "Error: ${it.message}" }
                    ctx.onStreamLine("Finished [${spec.role} #${idx + 1}]")
                    Triple(idx + 1, spec.role, out)
                }
            }.awaitAll()
        }

        val combined = buildString {
            appendLine("Parallel Multi-Agent Results (${results.size} agents):")
            results.forEach { (idx, role, output) ->
                appendLine()
                appendLine("### [$role #$idx]")
                appendLine(output)
            }
        }.trim()

        return ToolResult.ok(combined, summary = "${results.size} agents done")
    }
}
