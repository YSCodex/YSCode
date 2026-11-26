// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.data.PermissionMode
import com.example.data.PermissionRule
import com.example.data.SettingsStore
import com.example.tools.Risk
import com.example.tools.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.UUID

enum class PermissionReply {
    DENY, ALLOW_ONCE, ALLOW_SESSION, ALWAYS_ALLOW
}

data class PendingPermissionRequest(
    val id: String,
    val toolName: String,
    val summary: String,
    val detail: String,
    val risk: Risk,
    val isDangerous: Boolean,
    val suggestedRulePattern: String,
    val previewDiff: String? = null,
    val deferred: CompletableDeferred<PermissionReply>
)

sealed class PermissionCheckOutcome {
    data object Allowed : PermissionCheckOutcome()
    data class Denied(val reason: String) : PermissionCheckOutcome()
}

class PermissionEngine(
    private val settingsStore: SettingsStore
) {
    private val sessionAllowedPatterns = mutableSetOf<String>()

    private val _pendingRequest = MutableStateFlow<PendingPermissionRequest?>(null)
    val pendingRequest: StateFlow<PendingPermissionRequest?> = _pendingRequest.asStateFlow()

    fun resetSessionPermissions() {
        sessionAllowedPatterns.clear()
    }

    suspend fun checkAndAwait(
        toolName: String,
        args: JsonObject,
        risk: Risk,
        mode: PermissionMode
    ): PermissionCheckOutcome {
        val settings = settingsStore.current()
        val targetPath = args.str("path").orEmpty()
        val bashCmd = args.str("command").orEmpty()

        // 1. Plan mode blocks all non-read mutations
        if (mode == PermissionMode.PLAN && risk != Risk.READ) {
            return PermissionCheckOutcome.Denied("Plan mode is read-only. Present your plan and call exit_plan_mode first.")
        }

        // 2. Protected paths check
        if (targetPath.isNotBlank() && isProtectedPath(targetPath, settings.protectedPaths)) {
            return requestUserDecision(
                toolName = toolName,
                args = args,
                risk = Risk.EXEC,
                isDangerous = true,
                reasonPrefix = "Protected path access: $targetPath",
                suggestedPattern = buildRulePattern(toolName, args)
            )
        }

        // 3. Hard denylist & dangerous command detection
        if (toolName == "bash" && bashCmd.isNotBlank()) {
            val matchedDeny = settings.commandDenylist.firstOrNull { denied ->
                denied.isNotBlank() && bashCmd.contains(denied, ignoreCase = true)
            }
            if (matchedDeny != null || isDangerousCommand(bashCmd)) {
                return requestUserDecision(
                    toolName = toolName,
                    args = args,
                    risk = Risk.EXEC,
                    isDangerous = true,
                    reasonPrefix = "Dangerous command guard triggered: $bashCmd",
                    suggestedPattern = buildRulePattern(toolName, args)
                )
            }
        }

        // 4. Capability-altering tools ALWAYS ask user for approval (PRD 5.6 / 5.7)
        if (toolName in setOf("create_skill", "install_skill", "add_mcp_server")) {
            return requestUserDecision(
                toolName = toolName,
                args = args,
                risk = Risk.WRITE,
                isDangerous = false,
                reasonPrefix = "Approve agent extension ($toolName)",
                suggestedPattern = toolName
            )
        }

        // 5. Explicit user permission rules (allow / deny / ask)
        val ruleAction = matchRuleAction(toolName, args, settings.permissionRules)
        if (ruleAction == "deny") {
            return PermissionCheckOutcome.Denied("Blocked by permission rule.")
        }
        if (ruleAction == "allow") {
            return PermissionCheckOutcome.Allowed
        }

        // 6. Session allow cache
        val signature = buildRulePattern(toolName, args)
        if (signature in sessionAllowedPatterns || toolName in sessionAllowedPatterns) {
            return PermissionCheckOutcome.Allowed
        }

        // 7. Read tools & internal workflow tools are allowed
        if (risk == Risk.READ || toolName in setOf("todo_write", "ask_user", "exit_plan_mode", "use_skill", "task")) {
            return PermissionCheckOutcome.Allowed
        }

        // 8. Mode check
        return when (mode) {
            PermissionMode.YOLO -> PermissionCheckOutcome.Allowed
            PermissionMode.AUTO_EDIT -> {
                if (risk == Risk.WRITE) PermissionCheckOutcome.Allowed
                else requestUserDecision(toolName, args, risk, false, null, signature)
            }
            PermissionMode.ASK -> {
                requestUserDecision(toolName, args, risk, false, null, signature)
            }
            PermissionMode.PLAN -> PermissionCheckOutcome.Denied("Plan mode is read-only.")
        }
    }

    fun respond(reply: PermissionReply) {
        _pendingRequest.value?.deferred?.complete(reply)
    }

    fun cancelPending() {
        _pendingRequest.value?.deferred?.cancel()
        _pendingRequest.value = null
    }

    private suspend fun requestUserDecision(
        toolName: String,
        args: JsonObject,
        risk: Risk,
        isDangerous: Boolean,
        reasonPrefix: String?,
        suggestedPattern: String
    ): PermissionCheckOutcome {
        val summary = when (toolName) {
            "bash" -> "Run: ${args.str("command").orEmpty()}"
            "write_file" -> "Write: ${args.str("path").orEmpty()}"
            "edit_file", "multi_edit" -> "Edit: ${args.str("path").orEmpty()}"
            "web_fetch" -> "Fetch: ${args.str("url").orEmpty()}"
            "create_skill", "install_skill" -> "Install skill: ${args.str("name") ?: args.str("github_url") ?: "SKILL.md"}"
            "add_mcp_server" -> "Add MCP server: ${args.str("name").orEmpty()}"
            else -> "$toolName: ${args.toString().take(120)}"
        }
        val detail = buildString {
            if (!reasonPrefix.isNullOrBlank()) appendLine(reasonPrefix)
            append(args.toString().take(1200))
        }
        val previewDiff = when (toolName) {
            "edit_file" -> {
                val oldS = args.str("old_string").orEmpty()
                val newS = args.str("new_string").orEmpty()
                "- ${oldS.lines().take(4).joinToString("\n- ")}\n+ ${newS.lines().take(4).joinToString("\n+ ")}"
            }
            "write_file" -> args.str("content")?.lines()?.take(12)?.joinToString("\n") { "+ $it" }
            "create_skill" -> args.str("markdown")?.take(600)
            else -> null
        }

        val deferred = CompletableDeferred<PermissionReply>()
        _pendingRequest.value = PendingPermissionRequest(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            summary = summary,
            detail = detail,
            risk = risk,
            isDangerous = isDangerous,
            suggestedRulePattern = suggestedPattern,
            previewDiff = previewDiff,
            deferred = deferred
        )
        val reply = try {
            deferred.await()
        } finally {
            _pendingRequest.value = null
        }

        return when (reply) {
            PermissionReply.DENY -> PermissionCheckOutcome.Denied("User denied $toolName.")
            PermissionReply.ALLOW_ONCE -> PermissionCheckOutcome.Allowed
            PermissionReply.ALLOW_SESSION -> {
                sessionAllowedPatterns.add(suggestedPattern)
                PermissionCheckOutcome.Allowed
            }
            PermissionReply.ALWAYS_ALLOW -> {
                settingsStore.update { s ->
                    val newRule = PermissionRule(
                        id = "rule_" + UUID.randomUUID().toString().take(8),
                        pattern = suggestedPattern,
                        action = "allow"
                    )
                    s.copy(permissionRules = s.permissionRules + newRule)
                }
                PermissionCheckOutcome.Allowed
            }
        }
    }

    private fun matchRuleAction(toolName: String, args: JsonObject, rules: List<PermissionRule>): String? {
        val candidateArg = when (toolName) {
            "bash" -> args.str("command").orEmpty()
            "read_file", "write_file", "edit_file", "multi_edit", "list_dir" -> args.str("path").orEmpty()
            "web_fetch" -> args.str("url").orEmpty()
            else -> ""
        }
        val normalizedTool = normalizeToolRuleName(toolName)
        for (rule in rules) {
            val pat = rule.pattern.trim()
            val openParen = pat.indexOf('(')
            if (openParen < 0) {
                if (pat.equals(toolName, ignoreCase = true) || pat.equals(normalizedTool, ignoreCase = true)) {
                    return rule.action
                }
                continue
            }
            val closeParen = pat.lastIndexOf(')')
            if (closeParen <= openParen) continue
            val ruleTool = pat.substring(0, openParen).trim()
            val ruleArg = pat.substring(openParen + 1, closeParen).trim()
            if (!ruleTool.equals(toolName, ignoreCase = true) && !ruleTool.equals(normalizedTool, ignoreCase = true)) {
                continue
            }
            if (wildcardMatch(ruleArg, candidateArg)) {
                return rule.action
            }
        }
        return null
    }

    private fun normalizeToolRuleName(toolName: String): String = when (toolName) {
        "bash" -> "Bash"
        "write_file" -> "Write"
        "edit_file", "multi_edit" -> "Edit"
        "read_file" -> "Read"
        "web_fetch" -> "WebFetch"
        else -> toolName
    }

    private fun buildRulePattern(toolName: String, args: JsonObject): String {
        val norm = normalizeToolRuleName(toolName)
        return when (toolName) {
            "bash" -> {
                val cmd = args.str("command").orEmpty().trim()
                val prefix = cmd.split(Regex("\\s+")).take(2).joinToString(" ")
                "$norm($prefix:*)"
            }
            "write_file", "edit_file", "multi_edit", "read_file" -> {
                val p = args.str("path").orEmpty()
                "$norm($p)"
            }
            else -> norm
        }
    }

    private fun wildcardMatch(pattern: String, value: String): Boolean {
        if (pattern == "*" || pattern == ":*") return true
        val regexStr = buildString {
            append("^")
            var i = 0
            while (i < pattern.length) {
                if (pattern.startsWith(":*", i)) {
                    append("(?: .*)?")
                    i += 2
                } else if (pattern[i] == '*') {
                    append(".*")
                    i++
                } else {
                    append(Regex.escape(pattern[i].toString()))
                    i++
                }
            }
            append("$")
        }
        return Regex(regexStr, RegexOption.IGNORE_CASE).matches(value.trim())
    }

    private fun isProtectedPath(path: String, protectedPatterns: List<String>): Boolean {
        val clean = path.trim().lowercase()
        val fileName = clean.substringAfterLast('/')
        return protectedPatterns.any { pat ->
            val p = pat.trim().lowercase()
            when {
                p.startsWith("*.") -> fileName.endsWith(p.removePrefix("*"))
                p.startsWith("~/") -> clean.contains(p.removePrefix("~/"))
                else -> fileName == p || clean.endsWith("/$p") || clean.contains("/$p/")
            }
        }
    }

    private fun isDangerousCommand(cmd: String): Boolean {
        val lower = cmd.lowercase()
        return lower.contains("rm -rf /") ||
            lower.contains("rm -rf ~") ||
            lower.contains("mkfs.") ||
            lower.contains("git push --force") ||
            lower.contains("git push -f") ||
            lower.contains("dd if=/dev/")
    }
}
