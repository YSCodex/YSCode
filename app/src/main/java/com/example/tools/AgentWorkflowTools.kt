// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.mcp.McpConfigParser
import com.example.mcp.McpRegistry
import com.example.mcp.McpServerConfig
import com.example.skills.SkillInstaller
import com.example.skills.SkillParser
import com.example.skills.SkillRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

data class PendingUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val deferred: CompletableDeferred<String>
)

enum class PlanAction { APPROVE_AND_RUN, EDIT, REJECT }

data class PlanDecision(
    val action: PlanAction,
    val feedback: String = ""
)

data class PendingPlanApproval(
    val planSummary: String,
    val deferred: CompletableDeferred<PlanDecision>
)

class AgentInteractionBridge {
    private val _pendingQuestion = MutableStateFlow<PendingUserQuestion?>(null)
    val pendingQuestion: StateFlow<PendingUserQuestion?> = _pendingQuestion.asStateFlow()

    private val _pendingPlan = MutableStateFlow<PendingPlanApproval?>(null)
    val pendingPlan: StateFlow<PendingPlanApproval?> = _pendingPlan.asStateFlow()

    suspend fun askUser(question: String, options: List<String>): String {
        val deferred = CompletableDeferred<String>()
        _pendingQuestion.value = PendingUserQuestion(
            id = "ask_${System.nanoTime()}",
            question = question,
            options = options,
            deferred = deferred
        )
        return try {
            deferred.await()
        } finally {
            _pendingQuestion.value = null
        }
    }

    fun submitAnswer(answer: String) {
        _pendingQuestion.value?.deferred?.complete(answer)
    }

    suspend fun awaitPlanDecision(planSummary: String): PlanDecision {
        val deferred = CompletableDeferred<PlanDecision>()
        _pendingPlan.value = PendingPlanApproval(planSummary, deferred)
        return try {
            deferred.await()
        } finally {
            _pendingPlan.value = null
        }
    }

    fun submitPlanDecision(action: PlanAction, feedback: String = "") {
        _pendingPlan.value?.deferred?.complete(PlanDecision(action, feedback))
    }

    fun cancelAll() {
        _pendingQuestion.value?.deferred?.cancel()
        _pendingQuestion.value = null
        _pendingPlan.value?.deferred?.cancel()
        _pendingPlan.value = null
    }
}

class TodoWriteTool : Tool {
    override val name = "todo_write"
    override val description = "Update the visible multi-step task checklist in Chat and Activity panel."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("todos") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("content") { put("type", "string") }
                        putJsonObject("status") {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("pending"))
                                add(JsonPrimitive("in_progress"))
                                add(JsonPrimitive("completed"))
                            })
                        }
                    }
                    put("required", buildJsonArray {
                        add(JsonPrimitive("id"))
                        add(JsonPrimitive("content"))
                        add(JsonPrimitive("status"))
                    })
                }
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("todos")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val arr = args["todos"]?.jsonArray ?: return ToolResult.error("Missing 'todos'")
        val items = arr.mapIndexed { idx, el ->
            val obj = el.jsonObject
            TodoItem(
                id = obj.str("id") ?: "t_$idx",
                content = obj.str("content") ?: "",
                status = obj.str("status") ?: "pending"
            )
        }
        ctx.todoState.value = items
        val done = items.count { it.status == "completed" }
        return ToolResult.ok("Updated todo list ($done/${items.size} completed)", summary = "$done/${items.size} done")
    }
}

class AskUserTool(
    private val bridge: AgentInteractionBridge
) : Tool {
    override val name = "ask_user"
    override val description = "Ask the user a clarifying question with optional quick choices when blocked."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("question") { put("type", "string") }
            putJsonObject("options") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("question")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val question = args.str("question") ?: return ToolResult.error("Missing 'question'")
        val options = args["options"]?.jsonArray?.mapNotNull {
            (it as? JsonPrimitive)?.content
        }.orEmpty()
        val answer = bridge.askUser(question, options)
        return ToolResult.ok("User answered: $answer", summary = answer.take(40))
    }
}

class ExitPlanModeTool(
    private val bridge: AgentInteractionBridge,
    private val onSwitchToAutoEdit: suspend () -> Unit
) : Tool {
    override val name = "exit_plan_mode"
    override val description = "Present the completed engineering plan to the user for approval before executing edits."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("plan") { put("type", "string"); put("description", "Concise step-by-step plan") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("plan")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val plan = args.str("plan") ?: return ToolResult.error("Missing 'plan'")
        val decision = bridge.awaitPlanDecision(plan)
        return when (decision.action) {
            PlanAction.APPROVE_AND_RUN -> {
                onSwitchToAutoEdit()
                ToolResult.ok("Plan approved by user. Switched mode to Auto-edit — proceed with implementation.", summary = "Approved")
            }
            PlanAction.EDIT -> {
                ToolResult.ok("User requested changes to the plan: ${decision.feedback}", summary = "Revision requested")
            }
            PlanAction.REJECT -> {
                ToolResult.denied("Plan rejected by user.")
            }
        }
    }
}

class UseSkillTool(
    private val skillRepository: SkillRepository
) : Tool {
    override val name = "use_skill"
    override val description = "Load a skill's full SKILL.md instructions and helper folder path by name."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("name")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.str("name") ?: return ToolResult.error("Missing 'name'")
        val loaded = skillRepository.loadSkillForUse(name, ctx.projectDir)
            ?: return ToolResult.error("Skill '$name' not found or disabled.")
        val helpers = if (loaded.helperFiles.isEmpty()) "(none)" else loaded.helperFiles.take(60).joinToString(", ")
        val folderVirtual = ctx.env.toVirtualPath(loaded.folder)
        val pluginRoot = runCatching { java.io.File(loaded.folder, ".yscode-source").readText().trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: folderVirtual
        var body = loaded.parsed.body
        for (v in listOf("\${CLAUDE_PLUGIN_ROOT}", "\${CODEX_PLUGIN_ROOT}", "\${CURSOR_PLUGIN_ROOT}", "\${PLUGIN_ROOT}", "\${CLAUDE_PLUGIN_DIR}")) {
            body = body.replace(v, pluginRoot)
        }
        for (v in listOf("\${SKILL_DIR}", "\${CLAUDE_SKILL_DIR}")) body = body.replace(v, folderVirtual)
        return ToolResult.ok(
            output = "# Skill: ${loaded.parsed.name}\n" +
                "Folder (use this path in bash): $folderVirtual\n" +
                "Plugin/repo root: $pluginRoot\n" +
                "Helper files: $helpers\n\n" +
                body,
            summary = loaded.parsed.name
        )
    }
}

class CreateSkillTool(
    private val skillRepository: SkillRepository
) : Tool {
    override val name = "create_skill"
    override val description = "Create or register a reusable SKILL.md workflow (always requires user approval)."
    override val risk = Risk.WRITE
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "string") }
            putJsonObject("markdown") { put("type", "string"); put("description", "Full SKILL.md content with YAML frontmatter") }
            putJsonObject("scope") { put("type", "string"); put("description", "'global' or 'project'") }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("name"))
            add(JsonPrimitive("markdown"))
        })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawName = args.str("name") ?: return ToolResult.error("Missing 'name'")
        val markdown = args.str("markdown") ?: return ToolResult.error("Missing 'markdown'")
        val scope = (args.str("scope") ?: "global").lowercase()
        val safeName = SkillParser.sanitizeSkillName(rawName)
        val baseDir = if (scope == "project") File(ctx.projectDir, ".yscode/skills") else ctx.env.globalSkillsDir
        val destDir = File(baseDir, safeName)

        return skillRepository.saveSkillContent(
            skillDir = destDir,
            markdown = markdown,
            scope = if (scope == "project") "project" else "global",
            projectId = if (scope == "project") ctx.projectDir.name else null
        ).fold(
            onSuccess = { meta ->
                ToolResult.ok("Created skill '${meta.name}' at ${meta.path}", summary = meta.name)
            },
            onFailure = { err ->
                ToolResult.error("Invalid SKILL.md: ${err.message}")
            }
        )
    }
}

class InstallSkillTool(
    private val installer: SkillInstaller
) : Tool {
    override val name = "install_skill"
    override val description = "Install skills/plugins from a GitHub repo (full repo download, finds every SKILL.md at any depth, " +
        "keeps helper scripts, detects MCP server configs). Also accepts raw SKILL.md markdown. Installed skills are usable " +
        "immediately via use_skill. Requires user approval."
    override val risk = Risk.WRITE
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("github_url") {
                put("type", "string")
                put("description", "https://github.com/owner/repo, owner/repo, or a /tree/<branch>/<subdir> URL")
            }
            putJsonObject("only") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Optional skill names to install when the repo has many skills")
            }
            putJsonObject("markdown") { put("type", "string"); put("description", "Raw SKILL.md content (instead of github_url)") }
            putJsonObject("scope") { put("type", "string"); put("description", "'global' (default) or 'project'") }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args.str("github_url")
        val md = args.str("markdown")
        val scope = (args.str("scope") ?: "global").lowercase().let { if (it == "project") "project" else "global" }
        val only = args["only"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()

        if (!url.isNullOrBlank()) {
            return installer.installFromGitHub(url, scope, ctx.projectDir, only).fold(
                onSuccess = { r ->
                    val out = buildString {
                        appendLine("Repo downloaded to: ${r.repoVirtualPath}")
                        if (r.installedSkills.isNotEmpty()) {
                            appendLine("Installed ${r.installedSkills.size} skill(s) ($scope): ${r.installedSkills.joinToString(", ")}")
                            appendLine("They are available NOW: call use_skill(name) to load one.")
                        } else {
                            appendLine("No skills were installed.")
                        }
                        if (r.skippedSkills.isNotEmpty()) appendLine("Skipped (invalid): ${r.skippedSkills.joinToString(", ")}")
                        if (r.installedSkills.isEmpty() && r.allSkillNames.isNotEmpty()) {
                            appendLine("Skills found in repo: ${r.allSkillNames.take(60).joinToString(", ")}")
                        }
                        r.notes.forEach { appendLine(it) }
                        if (r.mcpHints.isNotEmpty()) {
                            appendLine("MCP servers detected in this repo (not added yet):")
                            r.mcpHints.forEach { appendLine("- $it") }
                            appendLine("Set them up with bash (install deps/build) then add_mcp_server.")
                        }
                        if (r.installedSkills.isEmpty() && r.mcpHints.isEmpty()) {
                            appendLine("This repo has no SKILL.md or MCP config. Read its README in ${r.repoVirtualPath} to decide how to install/use it.")
                        }
                    }
                    ToolResult.ok(out.trim(), summary = r.installedSkills.firstOrNull() ?: "repo")
                },
                onFailure = { ToolResult.error("GitHub install failed: ${it.message}") }
            )
        }
        if (!md.isNullOrBlank()) {
            val candidate = installer.prepareFromMarkdown(md, "Agent install")
                .getOrElse { return ToolResult.error("Skill prepare failed: ${it.message}") }
            return installer.commitInstall(candidate, scope, ctx.projectDir).fold(
                onSuccess = { meta -> ToolResult.ok("Installed skill '${meta.name}' ($scope): ${meta.description}", summary = meta.name) },
                onFailure = { err -> ToolResult.error("Skill install failed: ${err.message}") }
            )
        }
        return ToolResult.error("Provide 'github_url' or 'markdown'")
    }
}

class AddMcpServerTool(
    private val mcpRegistry: McpRegistry
) : Tool {
    override val name = "add_mcp_server"
    override val description = "Add and connect an MCP server (stdio or HTTP/SSE). Always requires user approval."
    override val risk = Risk.WRITE
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "string") }
            putJsonObject("transport") { put("type", "string"); put("description", "'stdio', 'http', or 'sse'") }
            putJsonObject("command") { put("type", "string") }
            putJsonObject("args") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            putJsonObject("url") { put("type", "string") }
            putJsonObject("env") { put("type", "object"); put("description", "Environment variables, e.g. API tokens (stored encrypted)") }
            putJsonObject("headers") { put("type", "object"); put("description", "HTTP headers for http/sse servers, e.g. Authorization (stored encrypted)") }
            putJsonObject("scope") { put("type", "string") }
        }
        put("required", buildJsonArray {
            add(JsonPrimitive("name"))
            add(JsonPrimitive("transport"))
        })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = McpConfigParser.sanitizeMcpName(args.str("name") ?: return ToolResult.error("Missing 'name'"))
        val transport = (args.str("transport") ?: return ToolResult.error("Missing 'transport'")).lowercase()
        val command = args.str("command").orEmpty()
        val cmdArgs = args["args"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val url = args.str("url").orEmpty()
        val scope = (args.str("scope") ?: "global").lowercase()

        if (transport == "stdio" && command.isBlank()) return ToolResult.error("stdio server requires 'command'")
        if ((transport == "http" || transport == "sse") && url.isBlank()) return ToolResult.error("$transport server requires 'url'")

        val envMap = (args["env"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        val hdrMap = (args["headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
        val entity = mcpRegistry.addOrUpdateServer(
            name = name,
            transport = transport,
            scopeStr = if (scope == "project") "project" else "global",
            projectId = if (scope == "project") ctx.projectDir.name else null,
            // secrets live in the encrypted store; config only keeps the key names
            config = McpServerConfig(
                command = command, args = cmdArgs, url = url,
                env = envMap.mapValues { "" }, headers = hdrMap.mapValues { "" }
            ),
            secretEnvValues = envMap,
            secretHeaderValues = hdrMap,
            workingDir = ctx.projectDir
        )
        // addOrUpdateServer connects synchronously; report the real outcome so the agent can react.
        val st = mcpRegistry.serverStates.value.firstOrNull { it.serverId == entity.id }
        return if (st?.status == com.example.mcp.McpStatus.CONNECTED) {
            ToolResult.ok(
                "MCP server '${entity.name}' connected with ${st.tools.size} tools: " +
                    st.tools.take(25).joinToString(", ") { it.qualifiedName } +
                    ". They are callable now.",
                summary = entity.name
            )
        } else {
            ToolResult.error(
                "MCP server '${entity.name}' was added but failed to connect: ${st?.lastError ?: "unknown error"}. " +
                    "Recent logs: ${st?.logs?.takeLast(6)?.joinToString(" | ").orEmpty()}"
            )
        }
    }
}

class TaskSubagentTool(
    private val runner: suspend (taskPrompt: String, role: String, allowWrite: Boolean, background: Boolean, modelOverride: String?, ctx: ToolContext) -> String
) : Tool {
    override val name = "task"
    override val description = "Delegate a focused sub-task to an isolated subagent (role: explorer, coder, reviewer, general). Supports background=true for async tasks with notification on completion."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") { put("type", "string"); put("description", "Clear instruction for the subagent") }
            putJsonObject("role") {
                put("type", "string")
                put("description", "Subagent specialization: 'explorer' (read-only search), 'coder' (edit+test), 'reviewer' (audit/diff), or 'general'")
            }
            putJsonObject("allow_write") { put("type", "boolean"); put("description", "Allow write/exec tools (defaults true for 'coder', false otherwise)") }
            putJsonObject("background") { put("type", "boolean"); put("description", "Run asynchronously in background and notify on finish") }
            putJsonObject("model") { put("type", "string"); put("description", "Optional model override") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("prompt")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.isSubagent) {
            return ToolResult.error("Nested subagents are disallowed to prevent infinite recursion.")
        }
        val prompt = args.str("prompt") ?: return ToolResult.error("Missing 'prompt'")
        val role = args.str("role")?.lowercase()?.ifBlank { "general" } ?: "general"
        val allowWrite = args.bool("allow_write") ?: (role == "coder")
        val background = args.bool("background") ?: false
        val model = args.str("model")
        val summary = runner(prompt, role, allowWrite, background, model, ctx)
        return ToolResult.ok(
            summary,
            summary = if (background) "Launched bg:$role" else "$role done"
        )
    }
}
