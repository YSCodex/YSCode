// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.data.SettingsStore
import com.example.linux.ProotRunner
import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File

@Serializable
enum class HookEvent {
    PreToolUse,
    PostToolUse,
    Stop
}

@Serializable
data class HookDefinition(
    val id: String,
    val name: String,
    val event: HookEvent,
    val toolMatcher: String = "*", // regex or pipe list e.g. "edit_file|write_file|multi_edit", "*"
    val command: String,
    val timeoutMs: Long = 15_000L,
    val enabled: Boolean = true
)

data class HookExecutionSummary(
    val hookName: String,
    val event: HookEvent,
    val blocked: Boolean,
    val exitCode: Int,
    val output: String
)

object HookPresets {
    val starterTemplates: List<HookDefinition> = listOf(
        HookDefinition(
            id = "preset_prettier",
            name = "Auto-format JS/TS (Prettier)",
            event = HookEvent.PostToolUse,
            toolMatcher = "write_file|edit_file|multi_edit",
            command = "if [ -n \"\$YSCODE_FILE\" ] && echo \"\$YSCODE_FILE\" | grep -qE '\\.(js|jsx|ts|tsx|css|json)\$' && command -v npx >/dev/null 2>&1; then npx prettier --write \"\$YSCODE_FILE\"; fi",
            enabled = true
        ),
        HookDefinition(
            id = "preset_ruff",
            name = "Auto-format Python (Ruff)",
            event = HookEvent.PostToolUse,
            toolMatcher = "write_file|edit_file|multi_edit",
            command = "if [ -n \"\$YSCODE_FILE\" ] && echo \"\$YSCODE_FILE\" | grep -qE '\\.py\$' && command -v ruff >/dev/null 2>&1; then ruff format \"\$YSCODE_FILE\" && ruff check --fix \"\$YSCODE_FILE\"; fi",
            enabled = true
        ),
        HookDefinition(
            id = "preset_guard_lockfile",
            name = "Block Lockfile Edits",
            event = HookEvent.PreToolUse,
            toolMatcher = "write_file|edit_file|multi_edit",
            command = "case \"\$YSCODE_FILE\" in *package-lock.json|*yarn.lock|*pnpm-lock.yaml) echo \"Refusing direct lockfile edit: \$YSCODE_FILE\" >&2; exit 2;; esac",
            enabled = true
        )
    )
}

class HookEngine(
    private val settingsStore: SettingsStore,
    private val runner: ProotRunner
) {
    suspend fun loadEffectiveHooks(projectDir: File): List<HookDefinition> = withContext(Dispatchers.IO) {
        val global = settingsStore.current().hooks
        val projectFile = File(projectDir, ".yscode/hooks.json")
        val projectHooks = if (projectFile.exists()) {
            runCatching { JsonHelper.json.decodeFromString<List<HookDefinition>>(projectFile.readText()) }
                .getOrDefault(emptyList())
        } else emptyList()
        (global + projectHooks).filter { it.enabled }
    }

    suspend fun loadProjectHooks(projectDir: File): List<HookDefinition> = withContext(Dispatchers.IO) {
        val projectFile = File(projectDir, ".yscode/hooks.json")
        if (!projectFile.exists()) return@withContext emptyList()
        runCatching { JsonHelper.json.decodeFromString<List<HookDefinition>>(projectFile.readText()) }
            .getOrDefault(emptyList())
    }

    suspend fun saveProjectHooks(projectDir: File, hooks: List<HookDefinition>) = withContext(Dispatchers.IO) {
        val projectFile = File(projectDir, ".yscode/hooks.json")
        projectFile.parentFile?.mkdirs()
        projectFile.writeText(JsonHelper.json.encodeToString(hooks))
    }

    suspend fun runMatchingHooks(
        event: HookEvent,
        toolName: String,
        args: JsonObject?,
        projectDir: File,
        extraEnv: Map<String, String> = emptyMap()
    ): List<HookExecutionSummary> {
        val hooks = loadEffectiveHooks(projectDir).filter { it.event == event && matchesTool(it.toolMatcher, toolName) }
        if (hooks.isEmpty()) return emptyList()

        val filePath = args?.let {
            (it["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.orEmpty()

        val envMap = extraEnv + mapOf(
            "YSCODE_HOOK_EVENT" to event.name,
            "YSCODE_TOOL" to toolName,
            "YSCODE_FILE" to filePath,
            "YSCODE_PROJECT_DIR" to projectDir.absolutePath,
            "YSCODE_ARGS_JSON" to (args?.toString()?.take(2000) ?: "{}")
        )

        val results = mutableListOf<HookExecutionSummary>()
        for (hook in hooks) {
            val res = runner.exec(
                command = hook.command,
                cwd = projectDir,
                extraEnv = envMap,
                timeoutMs = hook.timeoutMs.coerceIn(1000L, 60_000L),
                mirrorToAgentTerminal = false
            )
            val combined = buildString {
                if (res.stdout.isNotBlank()) appendLine(res.stdout.trim())
                if (res.stderr.isNotBlank()) appendLine(res.stderr.trim())
            }.trim()
            val blocked = event == HookEvent.PreToolUse && res.exitCode == 2
            results += HookExecutionSummary(
                hookName = hook.name,
                event = event,
                blocked = blocked,
                exitCode = res.exitCode,
                output = combined
            )
            if (blocked) break
        }
        return results
    }

    companion object {
        fun matchesTool(matcher: String, toolName: String): Boolean {
            val m = matcher.trim()
            if (m.isEmpty() || m == "*") return true
            return runCatching { Regex("^($m)$", RegexOption.IGNORE_CASE).matches(toolName) }
                .getOrElse { m.equals(toolName, ignoreCase = true) }
        }
    }
}
