// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.skills

object StarterSkills {
    val bundled: Map<String, String> = mapOf(
        "skill-creator" to """
            ---
            name: skill-creator
            description: Create a new YSCode skill when the user wants a reusable workflow. Use when asked to "make a skill".
            ---
            # Steps
            1. Ask (only if unclear) what the skill should do and when it should trigger.
            2. Pick a short kebab-case name.
            3. Write `~/.yscode/skills/<name>/SKILL.md` with frontmatter (name, description) and clear numbered steps.
            4. Put helper scripts in the same folder; make them executable.
            5. Call create_skill with the path so the user can approve it.
            6. Test the skill once on a small example and fix issues.
        """.trimIndent(),

        "code-review" to """
            ---
            name: code-review
            description: Review code or diffs for bugs, edge cases, security issues, and clarity. Use when asked to review changes or PRs.
            allowed-tools: read_file, glob, grep, bash
            ---
            # Code Review Checklist
            1. Inspect modified files (`git diff` or `read_file`) before commenting.
            2. Check correctness: off-by-one errors, nullability, concurrency races, cancellation leaks, resource closing.
            3. Check security: path traversal, command injection, leaked secrets, untrusted input handling.
            4. Check tests & error paths: verify callers handle failures cleanly.
            5. Output concise findings ordered by severity with `path:line` references and concrete fixes.
        """.trimIndent(),

        "apk-reverse-engineering" to """
            ---
            name: apk-reverse-engineering
            description: Decompile and analyze Android APKs on-device with JADX + Apktool through the REA MCP server. Use when asked to inspect, decompile, or reverse-engineer an APK.
            allowed-tools: bash, read_file, grep, glob, toolchain, add_mcp_server
            ---
            # On-device APK analysis (REA)
            1. Install runtimes: `apk add --no-cache nodejs npm openjdk17-jre procps-ng`.
            2. Put the pinned JADX headless MCP jar on the device (for example `/sdcard/yscode/jadx-headless-mcp-0.7.1-all.jar`).
            3. Clone REA into `~/.yscode/tools/rea` (git clone https://github.com/morluto/rea) and run `npm install --omit=dev` inside it.
               Do NOT run `rea setup`: it targets desktop agents and fails on Android hardlinks.
            4. Register it once with add_mcp_server: name=rea, transport=stdio, command=node,
               args=["~/.yscode/tools/rea/scripts/rea.mjs", "mcp"] (expand the real path),
               env={"REA_JADX_MCP_JAR": "/sdcard/yscode/jadx-headless-mcp-0.7.1-all.jar"}.
            5. Decompiling a large APK can take several minutes; MCP tool calls allow up to 600s.
            6. If `java` is not on PATH, register the JDK with the toolchain tool (path `~/.yscode/toolchains/jdk17` or the apk install path).
            7. For resources, set REA_APKTOOL_COMMAND to an installed Apktool wrapper; otherwise JADX-only analysis is enough for code review.
            8. Work on APKs under `/sdcard/...` directly (shared storage is mounted); write decompiled output under `/root/.yscode/rea-out/<apk>`.
        """.trimIndent(),

        "debug" to """
            ---
            name: debug
            description: Systematically diagnose crashes, failing tests, or build errors from root cause. Use when debugging an issue.
            allowed-tools: read_file, grep, glob, bash, edit_file
            ---
            # Systematic Debugging
            1. Reproduce the failure first with `bash` and capture the exact error/stacktrace.
            2. Read the failing file and surrounding callers completely with `read_file`.
            3. Identify the root cause — do not patch symptoms blindly.
            4. Apply a minimal `edit_file` fix.
            5. Re-run the failing test or command and confirm it passes with zero regressions.
        """.trimIndent(),

        "git-commit" to """
            ---
            name: git-commit
            description: Inspect staged/unstaged changes and create a clear conventional git commit. Use when asked to commit work.
            allowed-tools: bash, read_file
            ---
            # Git Commit Workflow
            1. Run `git status` and `git diff` to understand every changed line.
            2. Ensure no secrets (`.env`, API keys, private keys) are staged.
            3. Draft a concise commit message (`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`) explaining why the change was made.
            4. Stage intended files and commit; summarize commit hash and files touched.
        """.trimIndent(),

        "write-tests" to """
            ---
            name: write-tests
            description: Write focused unit or integration tests matching the project's test runner and conventions.
            allowed-tools: read_file, glob, grep, write_file, edit_file, bash
            ---
            # Writing Tests
            1. Detect the project's test framework (JUnit/Robolectric, pytest, vitest/jest, node --test).
            2. Read existing test files to mirror assertions, fixtures, and naming style.
            3. Cover happy path, boundary conditions, and error/invalid input cases.
            4. Run the test suite via `bash` and verify all new and existing tests pass.
        """.trimIndent(),

        "refactor" to """
            ---
            name: refactor
            description: Cleanly restructure code to improve modularity and readability without changing behavior.
            allowed-tools: read_file, grep, glob, edit_file, multi_edit, bash
            ---
            # Safe Refactoring
            1. Run existing tests first to establish a green baseline.
            2. Search all references with `grep` before renaming or moving symbols.
            3. Apply incremental edits with `edit_file`/`multi_edit`.
            4. Re-run tests and build checks after each logical step.
        """.trimIndent(),

        "android-build" to """
            ---
            name: android-build
            description: Inspect Gradle, AndroidManifest, and Jetpack Compose files to diagnose or optimize Android builds.
            allowed-tools: read_file, glob, grep, edit_file, bash
            ---
            # Android Build & Compose Diagnostics
            1. Check `gradle/libs.versions.toml` and `app/build.gradle.kts` for plugin/dependency alignment.
            2. Verify `AndroidManifest.xml` permissions and exported components.
            3. Check Compose state stability, ViewModel lifecycle collection, and WindowInsets handling.
            4. Run build/unit test checks and report exact compiler diagnostics.
        """.trimIndent()
    )
}
