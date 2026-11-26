// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.agent.HookDefinition
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.UiLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "yscode_settings")

enum class PermissionMode(val id: String, val label: String) {
    ASK("ask", "Ask"),
    AUTO_EDIT("auto-edit", "Auto-edit"),
    PLAN("plan", "Plan"),
    YOLO("yolo", "YOLO")
}

@Serializable
data class PermissionRule(
    val id: String,
    val pattern: String, // e.g., "Bash(npm test:*)", "Write(src/**)", "Read(.env)"
    val action: String   // "allow", "deny", "ask"
)

@Serializable
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val rootfsInitialized: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val codeFontSize: Int = 14,
    val terminalFontSize: Int = 13,
    val uiLanguage: UiLanguage = UiLanguage.ENGLISH,
    val defaultMode: PermissionMode = PermissionMode.ASK,
    val yoloConfirmedOnce: Boolean = false,
    val maxSteps: Int = 100,
    val maxTokensPerTurn: Int = 32768,
    val temperature: Double = 0.2,
    val reasoningEffort: String = "medium",
    val autoCompactThreshold: Double = 0.80,
    val subagentsEnabled: Boolean = true,
    val subagentModelOverride: String = "",
    val fallbackProviderId: String = "",
    val fallbackModel: String = "",
    val allowWebFetch: Boolean = true,
    val allowMcpHttp: Boolean = true,
    val wakeLockDuringJobs: Boolean = true,
    val disabledTools: Set<String> = emptySet(),
    val permissionRules: List<PermissionRule> = defaultPermissionRules(),
    val protectedPaths: List<String> = listOf(".env", ".env.local", "~/.ssh", "*.pem", "*.key", "id_rsa"),
    val commandDenylist: List<String> = listOf("rm -rf /", "mkfs", "dd if=", ":(){:|:&};:", "shutdown", "reboot"),
    val commandAllowlist: List<String> = listOf("ls", "pwd", "cat", "git status", "git diff", "python3 -V", "node -v"),
    val customEnvVars: Map<String, String> = emptyMap(),
    val gitUserName: String = "YSCode User",
    val gitUserEmail: String = "user@yscode.local",
    val gitTokenAlias: String = "git_pat_token",
    val activeProjectId: String = "",
    val activeProviderId: String = "",
    val autoTestAfterEdit: Boolean = true,
    val autoTestMaxRetries: Int = 2,
    val autoMemoryEnabled: Boolean = true,
    val useTreeSitterRepoMap: Boolean = true,
    val customTestCommand: String = "",
    val hooks: List<HookDefinition> = emptyList()
) {
    companion object {
        fun defaultPermissionRules(): List<PermissionRule> = listOf(
            PermissionRule("rule_test_npm", "Bash(npm test:*)", "allow"),
            PermissionRule("rule_test_pytest", "Bash(pytest:*)", "allow"),
            PermissionRule("rule_git_status", "Bash(git status:*)", "allow"),
            PermissionRule("rule_rm_rf", "Bash(rm -rf /*)", "deny")
        )
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val ROOTFS_INITIALIZED = booleanPreferencesKey("rootfs_initialized")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val CODE_FONT_SIZE = intPreferencesKey("code_font_size")
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
        val UI_LANGUAGE = stringPreferencesKey("ui_language")
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val YOLO_CONFIRMED = booleanPreferencesKey("yolo_confirmed")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val MAX_TOKENS_PER_TURN = intPreferencesKey("max_tokens_per_turn")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val AUTO_COMPACT_THRESHOLD = doublePreferencesKey("auto_compact_threshold")
        val SUBAGENTS_ENABLED = booleanPreferencesKey("subagents_enabled")
        val SUBAGENT_MODEL = stringPreferencesKey("subagent_model")
        val FALLBACK_PROVIDER_ID = stringPreferencesKey("fallback_provider_id")
        val FALLBACK_MODEL = stringPreferencesKey("fallback_model")
        val ALLOW_WEB_FETCH = booleanPreferencesKey("allow_web_fetch")
        val ALLOW_MCP_HTTP = booleanPreferencesKey("allow_mcp_http")
        val WAKE_LOCK = booleanPreferencesKey("wake_lock_during_jobs")
        val DISABLED_TOOLS_JSON = stringPreferencesKey("disabled_tools_json")
        val PERMISSION_RULES_JSON = stringPreferencesKey("permission_rules_json")
        val PROTECTED_PATHS_JSON = stringPreferencesKey("protected_paths_json")
        val COMMAND_DENYLIST_JSON = stringPreferencesKey("command_denylist_json")
        val COMMAND_ALLOWLIST_JSON = stringPreferencesKey("command_allowlist_json")
        val CUSTOM_ENV_JSON = stringPreferencesKey("custom_env_json")
        val GIT_USER_NAME = stringPreferencesKey("git_user_name")
        val GIT_USER_EMAIL = stringPreferencesKey("git_user_email")
        val ACTIVE_PROJECT_ID = stringPreferencesKey("active_project_id")
        val ACTIVE_PROVIDER_ID = stringPreferencesKey("active_provider_id")
        val AUTO_TEST_AFTER_EDIT = booleanPreferencesKey("auto_test_after_edit")
        val AUTO_TEST_MAX_RETRIES = intPreferencesKey("auto_test_max_retries")
        val AUTO_MEMORY_ENABLED = booleanPreferencesKey("auto_memory_enabled")
        val USE_TREE_SITTER_REPO_MAP = booleanPreferencesKey("use_tree_sitter_repo_map")
        val CUSTOM_TEST_COMMAND = stringPreferencesKey("custom_test_command")
        val HOOKS_JSON = stringPreferencesKey("hooks_json")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingCompleted = p[Keys.ONBOARDING_COMPLETED] ?: false,
            rootfsInitialized = p[Keys.ROOTFS_INITIALIZED] ?: false,
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: false,
            codeFontSize = (p[Keys.CODE_FONT_SIZE] ?: 14).coerceIn(10, 20),
            terminalFontSize = (p[Keys.TERMINAL_FONT_SIZE] ?: 13).coerceIn(9, 22),
            uiLanguage = runCatching { UiLanguage.valueOf(p[Keys.UI_LANGUAGE] ?: "ENGLISH") }.getOrDefault(UiLanguage.ENGLISH),
            defaultMode = PermissionMode.entries.find { it.id == p[Keys.DEFAULT_MODE] } ?: PermissionMode.ASK,
            yoloConfirmedOnce = p[Keys.YOLO_CONFIRMED] ?: false,
            maxSteps = (p[Keys.MAX_STEPS] ?: 100).coerceIn(1, 200),
            maxTokensPerTurn = (p[Keys.MAX_TOKENS_PER_TURN] ?: 32768).coerceIn(256, 65536),
            temperature = (p[Keys.TEMPERATURE] ?: 0.2).coerceIn(0.0, 2.0),
            reasoningEffort = p[Keys.REASONING_EFFORT] ?: "medium",
            autoCompactThreshold = (p[Keys.AUTO_COMPACT_THRESHOLD] ?: 0.80).coerceIn(0.4, 0.95),
            subagentsEnabled = p[Keys.SUBAGENTS_ENABLED] ?: true,
            subagentModelOverride = p[Keys.SUBAGENT_MODEL] ?: "",
            fallbackProviderId = p[Keys.FALLBACK_PROVIDER_ID] ?: "",
            fallbackModel = p[Keys.FALLBACK_MODEL] ?: "",
            allowWebFetch = p[Keys.ALLOW_WEB_FETCH] ?: true,
            allowMcpHttp = p[Keys.ALLOW_MCP_HTTP] ?: true,
            wakeLockDuringJobs = p[Keys.WAKE_LOCK] ?: true,
            disabledTools = decodeStringSet(p[Keys.DISABLED_TOOLS_JSON]),
            permissionRules = decodeRules(p[Keys.PERMISSION_RULES_JSON]) ?: AppSettings.defaultPermissionRules(),
            protectedPaths = decodeStringList(p[Keys.PROTECTED_PATHS_JSON])
                ?: listOf(".env", ".env.local", "~/.ssh", "*.pem", "*.key", "id_rsa"),
            commandDenylist = decodeStringList(p[Keys.COMMAND_DENYLIST_JSON])
                ?: listOf("rm -rf /", "mkfs", "dd if=", ":(){:|:&};:", "shutdown", "reboot"),
            commandAllowlist = decodeStringList(p[Keys.COMMAND_ALLOWLIST_JSON])
                ?: listOf("ls", "pwd", "cat", "git status", "git diff", "python3 -V", "node -v"),
            customEnvVars = decodeStringMap(p[Keys.CUSTOM_ENV_JSON]),
            gitUserName = p[Keys.GIT_USER_NAME] ?: "YSCode User",
            gitUserEmail = p[Keys.GIT_USER_EMAIL] ?: "user@yscode.local",
            activeProjectId = p[Keys.ACTIVE_PROJECT_ID] ?: "",
            activeProviderId = p[Keys.ACTIVE_PROVIDER_ID] ?: "",
            autoTestAfterEdit = p[Keys.AUTO_TEST_AFTER_EDIT] ?: true,
            autoTestMaxRetries = (p[Keys.AUTO_TEST_MAX_RETRIES] ?: 2).coerceIn(0, 5),
            autoMemoryEnabled = p[Keys.AUTO_MEMORY_ENABLED] ?: true,
            useTreeSitterRepoMap = p[Keys.USE_TREE_SITTER_REPO_MAP] ?: true,
            customTestCommand = p[Keys.CUSTOM_TEST_COMMAND] ?: "",
            hooks = decodeHooks(p[Keys.HOOKS_JSON])
        )
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        val current = current()
        val updated = block(current)
        save(updated)
    }

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.ONBOARDING_COMPLETED] = s.onboardingCompleted
            p[Keys.ROOTFS_INITIALIZED] = s.rootfsInitialized
            p[Keys.THEME_MODE] = s.themeMode.name
            p[Keys.DYNAMIC_COLOR] = s.dynamicColor
            p[Keys.CODE_FONT_SIZE] = s.codeFontSize
            p[Keys.TERMINAL_FONT_SIZE] = s.terminalFontSize
            p[Keys.UI_LANGUAGE] = s.uiLanguage.name
            p[Keys.DEFAULT_MODE] = s.defaultMode.id
            p[Keys.YOLO_CONFIRMED] = s.yoloConfirmedOnce
            p[Keys.MAX_STEPS] = s.maxSteps
            p[Keys.MAX_TOKENS_PER_TURN] = s.maxTokensPerTurn
            p[Keys.TEMPERATURE] = s.temperature
            p[Keys.REASONING_EFFORT] = s.reasoningEffort
            p[Keys.AUTO_COMPACT_THRESHOLD] = s.autoCompactThreshold
            p[Keys.SUBAGENTS_ENABLED] = s.subagentsEnabled
            p[Keys.SUBAGENT_MODEL] = s.subagentModelOverride
            p[Keys.FALLBACK_PROVIDER_ID] = s.fallbackProviderId
            p[Keys.FALLBACK_MODEL] = s.fallbackModel
            p[Keys.ALLOW_WEB_FETCH] = s.allowWebFetch
            p[Keys.ALLOW_MCP_HTTP] = s.allowMcpHttp
            p[Keys.WAKE_LOCK] = s.wakeLockDuringJobs
            p[Keys.DISABLED_TOOLS_JSON] = com.example.util.JsonHelper.json.encodeToString(s.disabledTools.toList())
            p[Keys.PERMISSION_RULES_JSON] = com.example.util.JsonHelper.json.encodeToString(s.permissionRules)
            p[Keys.PROTECTED_PATHS_JSON] = com.example.util.JsonHelper.json.encodeToString(s.protectedPaths)
            p[Keys.COMMAND_DENYLIST_JSON] = com.example.util.JsonHelper.json.encodeToString(s.commandDenylist)
            p[Keys.COMMAND_ALLOWLIST_JSON] = com.example.util.JsonHelper.json.encodeToString(s.commandAllowlist)
            p[Keys.CUSTOM_ENV_JSON] = com.example.util.JsonHelper.json.encodeToString(s.customEnvVars)
            p[Keys.GIT_USER_NAME] = s.gitUserName
            p[Keys.GIT_USER_EMAIL] = s.gitUserEmail
            p[Keys.ACTIVE_PROJECT_ID] = s.activeProjectId
            p[Keys.ACTIVE_PROVIDER_ID] = s.activeProviderId
            p[Keys.AUTO_TEST_AFTER_EDIT] = s.autoTestAfterEdit
            p[Keys.AUTO_TEST_MAX_RETRIES] = s.autoTestMaxRetries
            p[Keys.AUTO_MEMORY_ENABLED] = s.autoMemoryEnabled
            p[Keys.USE_TREE_SITTER_REPO_MAP] = s.useTreeSitterRepoMap
            p[Keys.CUSTOM_TEST_COMMAND] = s.customTestCommand
            p[Keys.HOOKS_JSON] = com.example.util.JsonHelper.json.encodeToString(s.hooks)
        }
    }

    private fun decodeStringSet(raw: String?): Set<String> =
        if (raw.isNullOrBlank()) emptySet()
        else runCatching { com.example.util.JsonHelper.json.decodeFromString<List<String>>(raw).toSet() }
            .getOrDefault(emptySet())

    private fun decodeStringList(raw: String?): List<String>? =
        if (raw.isNullOrBlank()) null
        else runCatching { com.example.util.JsonHelper.json.decodeFromString<List<String>>(raw) }.getOrNull()

    private fun decodeRules(raw: String?): List<PermissionRule>? =
        if (raw.isNullOrBlank()) null
        else runCatching { com.example.util.JsonHelper.json.decodeFromString<List<PermissionRule>>(raw) }.getOrNull()

    private fun decodeStringMap(raw: String?): Map<String, String> =
        if (raw.isNullOrBlank()) emptyMap()
        else runCatching { com.example.util.JsonHelper.json.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap())

    private fun decodeHooks(raw: String?): List<HookDefinition> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { com.example.util.JsonHelper.json.decodeFromString<List<HookDefinition>>(raw) }
            .getOrDefault(emptyList())
}
