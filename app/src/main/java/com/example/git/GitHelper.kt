// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.git

import com.example.data.SecretStore
import com.example.data.SettingsStore
import com.example.linux.ProotRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GitStatusResult(
    val isGitRepo: Boolean,
    val branch: String,
    val changedFiles: Map<String, String>, // relativePath -> "M" / "A" / "?"
    val cliAvailable: Boolean
)

class GitHelper(
    private val runner: ProotRunner,
    private val settingsStore: SettingsStore,
    private val secretStore: SecretStore,
    private val changesRepository: ChangesRepository
) {
    suspend fun getBranch(projectDir: File): String = withContext(Dispatchers.IO) {
        val gitHead = File(projectDir, ".git/HEAD")
        if (gitHead.exists()) {
            val raw = runCatching { gitHead.readText().trim() }.getOrDefault("")
            if (raw.startsWith("ref: refs/heads/")) {
                return@withContext raw.removePrefix("ref: refs/heads/").trim()
            }
            if (raw.isNotBlank()) return@withContext raw.take(8)
        }
        val res = runner.exec("git rev-parse --abbrev-ref HEAD", projectDir, timeoutMs = 4_000L, mirrorToAgentTerminal = false)
        if (res.exitCode == 0 && res.stdout.isNotBlank()) res.stdout.trim() else "no-git"
    }

    suspend fun getStatus(projectDir: File): GitStatusResult = withContext(Dispatchers.IO) {
        val isRepo = File(projectDir, ".git").exists()
        val branch = getBranch(projectDir)
        val res = runner.exec("git status --porcelain", projectDir, timeoutMs = 5_000L, mirrorToAgentTerminal = false)
        if (res.exitCode == 0) {
            val map = linkedMapOf<String, String>()
            res.stdout.lines().filter { it.length >= 4 }.forEach { line ->
                val code = line.substring(0, 2).trim().ifEmpty { "M" }
                val path = line.substring(3).trim()
                val badge = when {
                    code.contains("?") -> "?"
                    code.contains("A") -> "A"
                    else -> "M"
                }
                map[path] = badge
            }
            return@withContext GitStatusResult(isRepo, branch, map, cliAvailable = true)
        }
        // Fallback: combine tracked in-app changes when git CLI is absent
        val trackedMap = changesRepository.changes.value.associate { change ->
            change.relativePath to if (change.diff.isNewFile) "A" else "M"
        }
        GitStatusResult(isRepo, if (isRepo) branch else "local", trackedMap, cliAvailable = false)
    }

    suspend fun initRepo(projectDir: File): Result<String> = withContext(Dispatchers.IO) {
        val res = runner.exec("git init", projectDir, timeoutMs = 8_000L)
        if (res.exitCode == 0) {
            Result.success(res.stdout.ifBlank { "Initialized git repository" })
        } else {
            // Pure-Kotlin minimal .git initializer fallback if git CLI is not installed
            val gitDir = File(projectDir, ".git")
            if (!gitDir.exists()) {
                gitDir.mkdirs()
                File(gitDir, "objects").mkdirs()
                File(gitDir, "refs/heads").mkdirs()
                File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")
            }
            Result.success("Initialized .git metadata directory (main)")
        }
    }

    suspend fun commit(projectDir: File, message: String): Result<String> = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val safeMsg = message.replace("\"", "\\\"")
        val cmd = buildString {
            append("git config user.name \"${settings.gitUserName}\" && ")
            append("git config user.email \"${settings.gitUserEmail}\" && ")
            append("git add -A && git commit -m \"$safeMsg\"")
        }
        val res = runner.exec(cmd, projectDir, timeoutMs = 15_000L)
        if (res.exitCode == 0) {
            changesRepository.acceptAll()
            Result.success(res.stdout.ifBlank { "Committed successfully" })
        } else {
            val err = res.stderr.ifBlank { res.stdout }
            if (err.contains("not found", ignoreCase = true)) {
                // Record commit locally in .yscode/commits.log and clear pending review queue
                val logFile = File(projectDir, ".yscode/commits.log").apply { parentFile?.mkdirs() }
                logFile.appendText("[${java.util.Date()}] ${settings.gitUserName}: $message\n")
                changesRepository.acceptAll()
                Result.success("Recorded local commit in .yscode/commits.log (install git CLI in PRoot for full git objects)")
            } else {
                Result.failure(IllegalStateException(err.ifBlank { "Commit failed" }))
            }
        }
    }

    suspend fun switchOrCreateBranch(projectDir: File, branchName: String, create: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            val flag = if (create) "-b " else ""
            val res = runner.exec("git checkout $flag\"$branchName\"", projectDir, timeoutMs = 8_000L)
            if (res.exitCode == 0) {
                Result.success(res.stdout.ifBlank { "Switched to $branchName" })
            } else {
                val head = File(projectDir, ".git/HEAD")
                if (head.exists() && create) {
                    head.writeText("ref: refs/heads/$branchName\n")
                    Result.success("Updated HEAD to $branchName")
                } else {
                    Result.failure(IllegalStateException(res.stderr.ifBlank { "Branch switch failed" }))
                }
            }
        }

    suspend fun pushOrPull(projectDir: File, push: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val pat = secretStore.getSecret(settings.gitTokenAlias)
        val op = if (push) "push" else "pull"
        val env = if (!pat.isNullOrBlank()) mapOf("GIT_TOKEN" to pat) else emptyMap()
        val res = runner.exec("git $op", projectDir, extraEnv = env, timeoutMs = 45_000L)
        if (res.exitCode == 0) {
            Result.success(res.stdout.ifBlank { "git $op succeeded" })
        } else {
            Result.failure(IllegalStateException(res.stderr.ifBlank { "git $op failed (check remote & token in Settings)" }))
        }
    }
}
