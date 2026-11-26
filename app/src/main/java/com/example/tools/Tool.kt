// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.data.PermissionMode
import com.example.git.ChangesRepository
import com.example.linux.LinuxEnvironment
import com.example.linux.ProotRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class Risk {
    READ, WRITE, EXEC, NETWORK
}

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: String // "pending", "in_progress", "completed"
)

data class ToolDiffInfo(
    val relativePath: String,
    val before: String,
    val after: String,
    val addedLines: Int,
    val removedLines: Int
)

data class ToolResult(
    val output: String,
    val isError: Boolean = false,
    val summary: String = "",
    val diff: ToolDiffInfo? = null,
    /** Images produced by the tool (e.g. view_image). The agent loop forwards them to the model as a user message. */
    val images: List<com.example.llm.LlmContentPart.ImageBase64> = emptyList()
) {
    companion object {
        fun ok(output: String, summary: String = "", diff: ToolDiffInfo? = null) =
            ToolResult(output = output, isError = false, summary = summary, diff = diff)

        fun error(message: String, summary: String = "Error") =
            ToolResult(output = message, isError = true, summary = summary)

        fun denied(reason: String = "Permission denied by user") =
            ToolResult(output = reason, isError = true, summary = "Denied")
    }
}

class ReadTracker {
    private val readPaths = ConcurrentHashMap.newKeySet<String>()

    fun markRead(file: File) {
        runCatching { readPaths.add(file.canonicalPath) }
    }

    fun hasRead(file: File): Boolean {
        return runCatching { file.canonicalPath in readPaths }.getOrDefault(false)
    }

    fun clear() {
        readPaths.clear()
    }
}

data class ToolContext(
    val projectDir: File,
    val env: LinuxEnvironment,
    val runner: ProotRunner,
    val changesRepository: ChangesRepository,
    val readTracker: ReadTracker,
    val todoState: MutableStateFlow<List<TodoItem>>,
    val mode: PermissionMode,
    val sessionId: String,
    val isSubagent: Boolean = false,
    val customEnvVars: Map<String, String> = emptyMap(),
    val supportsVision: Boolean = true,
    val onStreamLine: (String) -> Unit = {}
)

interface Tool {
    val name: String
    val description: String
    val schema: JsonObject
    val risk: Risk
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        ?: (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }

fun JsonObject.int(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

fun JsonObject.long(key: String): Long? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
