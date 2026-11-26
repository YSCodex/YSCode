// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.linux.ProotRunner
import java.io.File

data class AutoTestResult(
    val command: String,
    val passed: Boolean,
    val exitCode: Int,
    val output: String,
    val durationMs: Long
)

class AutoTestRunner(
    private val runner: ProotRunner
) {
    fun detectTestCommand(projectDir: File, customOverride: String = ""): String? {
        if (customOverride.isNotBlank()) return customOverride.trim()

        // Node.js / JS / TS
        val pkg = File(projectDir, "package.json")
        if (pkg.exists()) {
            val txt = runCatching { pkg.readText() }.getOrDefault("")
            if (Regex(""""test"\s*:\s*"node --test"""").containsMatchIn(txt)) {
                // Bare `node --test` executes every .js file (e.g. server.js -> EADDRINUSE). Restrict to test files.
                return "node --test ${'$'}(ls *.test.js *.test.mjs *.spec.js test/*.js 2>/dev/null)"
            }
            if (txt.contains("\"test\"") && !txt.contains("no test specified")) {
                return "npm test"
            }
            if (File(projectDir, "vitest.config.ts").exists() || File(projectDir, "vitest.config.js").exists()) {
                return "npx vitest run"
            }
        }

        // Python
        val hasPyTests = File(projectDir, "pytest.ini").exists() ||
            File(projectDir, "tests").isDirectory ||
            (projectDir.listFiles()?.any { it.isFile && (it.name.startsWith("test_") || it.name.endsWith("_test.py")) } == true)
        if (hasPyTests) {
            return "python3 -m pytest -q || python3 -m unittest discover -s ."
        }

        // Gradle / Kotlin
        if (File(projectDir, "gradlew").exists()) {
            return "./gradlew test --no-daemon"
        }
        if (File(projectDir, "build.gradle.kts").exists() || File(projectDir, "build.gradle").exists()) {
            return "gradle test"
        }

        // Rust & Go
        if (File(projectDir, "Cargo.toml").exists()) return "cargo test"
        if (File(projectDir, "go.mod").exists()) return "go test ./..."

        return null
    }

    suspend fun runDetectedTests(
        projectDir: File,
        command: String,
        extraEnv: Map<String, String> = emptyMap(),
        onStreamLine: (String) -> Unit = {}
    ): AutoTestResult {
        val res = runner.exec(
            command = command,
            cwd = projectDir,
            extraEnv = extraEnv,
            timeoutMs = 120_000L,
            mirrorToAgentTerminal = true,
            onLine = onStreamLine
        )
        val combined = buildString {
            if (res.stdout.isNotBlank()) appendLine(res.stdout.trimEnd())
            if (res.stderr.isNotBlank()) {
                if (isNotEmpty()) appendLine("--- stderr ---")
                appendLine(res.stderr.trimEnd())
            }
            if (isEmpty()) append("(no test output)")
        }.trim()
        return AutoTestResult(
            command = command,
            passed = res.exitCode == 0 && !res.timedOut,
            exitCode = res.exitCode,
            output = combined.take(8_000),
            durationMs = res.durationMs
        )
    }
}
