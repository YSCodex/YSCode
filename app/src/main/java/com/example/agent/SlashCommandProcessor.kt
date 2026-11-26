// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.linux.LinuxEnvironment
import java.io.File

data class SlashCommandDef(
    val name: String,
    val description: String,
    val isCustom: Boolean = false,
    val templateBody: String = ""
)

class SlashCommandProcessor(
    private val env: LinuxEnvironment
) {
    val builtIns: List<SlashCommandDef> = listOf(
        SlashCommandDef("/init", "Scan project and generate YSCODE.md memory file"),
        SlashCommandDef("/bg", "Run an agent task in the background with notification"),
        SlashCommandDef("/test", "Detect and run project tests now"),
        SlashCommandDef("/repomap", "Rebuild and inspect ranked symbol index"),
        SlashCommandDef("/hooks", "Configure PreToolUse / PostToolUse shell hooks"),
        SlashCommandDef("/memory", "View or edit YSCODE.md project memory"),
        SlashCommandDef("/clear", "Clear current chat messages"),
        SlashCommandDef("/compact", "Summarize older turns to save context tokens"),
        SlashCommandDef("/vision", "Test whether the current model can see images"),
        SlashCommandDef("/model", "Search or switch active model / provider"),
        SlashCommandDef("/mode", "Cycle mode (Ask / Auto-edit / Plan / YOLO)"),
        SlashCommandDef("/plan", "Switch to Plan mode (read-only)"),
        SlashCommandDef("/undo", "Restore last checkpoint before agent edits"),
        SlashCommandDef("/diff", "Open Changes & Diff review tab"),
        SlashCommandDef("/commit", "Open Git Commit sheet"),
        SlashCommandDef("/resume", "Open Sessions drawer to resume/fork"),
        SlashCommandDef("/sessions", "Manage saved sessions"),
        SlashCommandDef("/skills", "Open Skills manager"),
        SlashCommandDef("/mcp", "Open MCP Servers manager"),
        SlashCommandDef("/tools", "Open Tools toggles in Settings"),
        SlashCommandDef("/export", "Export current session as Markdown"),
        SlashCommandDef("/help", "Show all slash commands")
    )

    /** Prompt-template commands (like Claude Code / Codex / OpenCode): they expand into a message for the agent. */
    private val promptBuiltIns: List<SlashCommandDef> = listOf(
        "/review" to ("Review uncommitted changes for bugs, security and style" to
            "Review the current uncommitted changes (git diff, git diff --staged and new untracked files). Look for bugs, logic errors, missing edge cases, security problems, performance issues and inconsistent style. Group findings by severity (blocker, major, minor, nit) with file:line and a concrete fix for each. Do NOT modify files unless I ask. End with a one-line verdict. \$ARGUMENTS"),
        "/security-review" to ("Security review: secrets, injection, auth, unsafe defaults" to
            "Do a read-only security review of this project (or the changes I name): hard-coded secrets, injection, path traversal, unsafe deserialization, weak auth, insecure defaults, risky dependencies. Cite file:line, explain impact, and propose fixes. \$ARGUMENTS"),
        "/fix" to ("Run build/tests, fix failures at the root cause" to
            "Run this project's build, tests and linter. Find every failure, fix the ROOT cause with minimal changes, and re-run until everything is green. Report what you changed and what you verified. \$ARGUMENTS"),
        "/explain" to ("Explain how the project or given code works" to
            "Explain how this project (or the code I point to) works: architecture, entry points, data flow and key files with paths. Be concise and concrete. \$ARGUMENTS"),
        "/refactor" to ("Refactor safely without changing behavior" to
            "Refactor the code I specify for clarity and maintainability WITHOUT changing behavior. Run or add tests first and re-run them after to prove behavior is unchanged. \$ARGUMENTS"),
        "/docs" to ("Write or update README and code docs from the real code" to
            "Write or update documentation (README, missing docstrings/comments) based on what the code actually does. Keep it accurate, short and runnable. \$ARGUMENTS"),
        "/optimize" to ("Find and fix the biggest performance/size problems" to
            "Inspect the code for the biggest performance and size problems, fix the most valuable ones safely, and verify with measurements or tests. \$ARGUMENTS"),
        "/pr" to ("Write a pull request title and description for current changes" to
            "Summarize all changes on this branch / working tree as a pull request: title, summary, list of changes, how it was tested, risks. Use git log and git diff. Do not push anything. \$ARGUMENTS"),
        "/todo" to ("List TODO/FIXME and unfinished work, prioritized" to
            "Scan the project for TODO/FIXME/HACK comments and unfinished features and give a prioritized list with file:line. Read-only. \$ARGUMENTS"),
        "/deps" to ("Audit dependencies: outdated, vulnerable, unused" to
            "Audit this project's dependencies: outdated, known-vulnerable and unused ones. Suggest safe upgrades. Do not change anything unless I say so. \$ARGUMENTS")
    ).map { (name, d) -> SlashCommandDef(name, d.first, isCustom = true, templateBody = d.second) }

    fun loadAllCommands(projectDir: File?): List<SlashCommandDef> {
        val custom = mutableListOf<SlashCommandDef>()
        scanCommandsDir(env.globalCommandsDir, custom)
        if (projectDir != null) {
            scanCommandsDir(File(projectDir, ".yscode/commands"), custom)
        }
        return (builtIns + custom + promptBuiltIns).distinctBy { it.name }
    }

    fun expandCustomCommand(commandLine: String, projectDir: File?): String? {
        val parts = commandLine.trim().split(Regex("\\s+"), limit = 2)
        val cmdName = parts.firstOrNull() ?: return null
        val args = parts.getOrNull(1).orEmpty()
        val match = loadAllCommands(projectDir).firstOrNull { it.isCustom && it.name.equals(cmdName, ignoreCase = true) }
            ?: return null
        return if (match.templateBody.contains("\$ARGUMENTS")) {
            match.templateBody.replace("\$ARGUMENTS", args)
        } else if (args.isNotBlank()) {
            "${match.templateBody}\n\nUser arguments: $args"
        } else {
            match.templateBody
        }
    }

    private fun scanCommandsDir(dir: File, out: MutableList<SlashCommandDef>) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }?.forEach { file ->
            val cmdName = "/${file.nameWithoutExtension.lowercase()}"
            val content = runCatching { file.readText() }.getOrDefault("").trim()
            if (content.isNotBlank()) {
                val firstLine = content.lines().firstOrNull { it.isNotBlank() }?.removePrefix("#")?.trim() ?: "Custom command"
                out += SlashCommandDef(
                    name = cmdName,
                    description = firstLine.take(70),
                    isCustom = true,
                    templateBody = content
                )
            }
        }
    }
}
