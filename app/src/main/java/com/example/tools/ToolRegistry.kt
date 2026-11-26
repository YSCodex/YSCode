// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.data.SettingsStore
import com.example.llm.ToolSpec
import com.example.mcp.McpRegistry
import kotlinx.serialization.json.JsonObject

class ToolRegistry(
    private val builtInTools: List<Tool>,
    private val mcpRegistry: McpRegistry,
    private val settingsStore: SettingsStore
) {
    private val builtInMap = builtInTools.associateBy { it.name }

    fun allBuiltInDefinitions(): List<Tool> = builtInTools

    suspend fun getActiveToolSpecs(readOnlyOnly: Boolean = false, includeSubagentTool: Boolean = true): List<ToolSpec> {
        val settings = settingsStore.current()
        val disabled = settings.disabledTools
        val specs = mutableListOf<ToolSpec>()

        for (tool in builtInTools) {
            if (tool.name in disabled) continue
            if (!includeSubagentTool && (tool.name == "task" || tool.name == "parallel_tasks")) continue
            if (readOnlyOnly && tool.risk != Risk.READ) continue
            if ((tool.name == "task" || tool.name == "parallel_tasks") && !settings.subagentsEnabled) continue
            specs += ToolSpec(tool.name, tool.description, tool.schema)
        }

        if (!readOnlyOnly) {
            mcpRegistry.getAllEnabledTools().forEach { mcpTool ->
                if (mcpTool.qualifiedName !in disabled) {
                    specs += ToolSpec(
                        name = mcpTool.qualifiedName,
                        description = "[MCP:${mcpTool.serverName}] ${mcpTool.description}",
                        parametersSchema = mcpTool.inputSchema
                    )
                }
            }
        }
        return specs
    }

    fun resolveRisk(toolName: String): Risk {
        builtInMap[toolName]?.let { return it.risk }
        return if (toolName.startsWith("mcp__")) Risk.EXEC else Risk.EXEC
    }

    suspend fun execute(toolName: String, args: JsonObject, ctx: ToolContext): ToolResult {
        val settings = settingsStore.current()
        if (toolName in settings.disabledTools) {
            return ToolResult.denied("Tool '$toolName' is disabled in Settings -> Tools.")
        }

        val builtIn = builtInMap[toolName]
        val rawResult = if (builtIn != null) {
            builtIn.execute(args, ctx)
        } else if (toolName.startsWith("mcp__")) {
            mcpRegistry.callQualifiedTool(toolName, args).fold(
                onSuccess = { out ->
                    ToolResult.ok("[UNTRUSTED MCP OUTPUT]\n$out", summary = "MCP ok")
                },
                onFailure = { err ->
                    ToolResult.error("MCP error: ${err.message}")
                }
            )
        } else {
            ToolResult.error("Unknown tool '$toolName'")
        }

        return rawResult.copy(output = truncateOutput(rawResult.output))
    }

    companion object {
        private const val HEAD_CHARS = 8_000
        private const val TAIL_CHARS = 4_000

        fun truncateOutput(text: String): String {
            if (text.length <= HEAD_CHARS + TAIL_CHARS + 128) return text
            val omitted = text.length - (HEAD_CHARS + TAIL_CHARS)
            return buildString {
                append(text.substring(0, HEAD_CHARS))
                append("\n\n...[$omitted chars truncated]...\n\n")
                append(text.substring(text.length - TAIL_CHARS))
            }
        }
    }
}
