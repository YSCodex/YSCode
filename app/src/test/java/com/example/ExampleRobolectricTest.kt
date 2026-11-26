// SPDX-License-Identifier: GPL-3.0-or-later
package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.agent.PermissionCheckOutcome
import com.example.agent.PermissionEngine
import com.example.data.AppSettings
import com.example.data.PermissionMode
import com.example.data.PermissionRule
import com.example.data.SettingsStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app name matches YSCode`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("YSCode", appName)
    }

    @Test
    fun `plan mode blocks non-read tool calls`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        val engine = PermissionEngine(store)
        val out = engine.checkAndAwait(
            toolName = "bash",
            args = buildJsonObject { put("command", "echo hi") },
            risk = com.example.tools.Risk.EXEC,
            mode = PermissionMode.PLAN
        )
        assertTrue(out is PermissionCheckOutcome.Denied)
    }

    @Test
    fun `explicit permission rule allows matching bash prefix`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        store.save(
            AppSettings(
                permissionRules = listOf(PermissionRule("r1", "Bash(pytest:*)", "allow"))
            )
        )
        val engine = PermissionEngine(store)
        val out = engine.checkAndAwait(
            toolName = "bash",
            args = buildJsonObject { put("command", "pytest -q tests/") },
            risk = com.example.tools.Risk.EXEC,
            mode = PermissionMode.ASK
        )
        assertEquals(PermissionCheckOutcome.Allowed, out)
    }

    @Test
    fun `onboarding renders title cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as YSCodeApplication
        composeRule.setContent {
            com.example.ui.YSCodeApp(container = app.container)
        }
        composeRule.onNodeWithText("YSCode").assertExists()
        composeRule.onNodeWithText("YSCode Setup (1/3)").assertExists()
    }

    @Test
    fun `updating session metadata via upsert preserves persisted user and assistant messages`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.YSCodeDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val proj = com.example.data.ProjectEntity(
                id = "proj_test",
                name = "Test",
                path = "/tmp/test"
            )
            val sess = com.example.data.SessionEntity(
                id = "sess_test",
                projectId = proj.id,
                title = "New Session"
            )
            db.projectDao().upsert(proj)
            db.sessionDao().upsert(sess)

            val userMsg = com.example.data.MessageEntity(
                id = "msg_u1",
                sessionId = sess.id,
                role = "user",
                contentJson = """[{"type":"text","text":"hi"}]""",
                createdAt = 1000L
            )
            val assistantMsg = com.example.data.MessageEntity(
                id = "msg_a1",
                sessionId = sess.id,
                role = "assistant",
                contentJson = """[{"type":"text","text":"Hi! How can I help?"}]""",
                createdAt = 1001L
            )
            db.messageDao().upsert(userMsg)
            db.messageDao().upsert(assistantMsg)

            // Updating session title & tokens (which previously caused ON DELETE CASCADE wipe!)
            db.sessionDao().upsert(
                sess.copy(title = "hi", tokensIn = 120L, tokensOut = 25L, updatedAt = 2000L)
            )
            db.projectDao().upsert(proj.copy(lastOpenedAt = 2000L))

            val savedMessages = db.messageDao().getForSession(sess.id)
            assertEquals(2, savedMessages.size)
            assertEquals("user", savedMessages[0].role)
            assertEquals("assistant", savedMessages[1].role)
        } finally {
            db.close()
        }
    }

    @Test
    fun `auto test runner detects node python gradle and custom override`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = com.example.agent.AutoTestRunner(
            com.example.linux.ProotRunner(com.example.linux.LinuxEnvironment(context))
        )
        val tempDir = java.nio.file.Files.createTempDirectory("yscode_autotest_detect").toFile()
        try {
            org.junit.Assert.assertNull(detector.detectTestCommand(tempDir))

            java.io.File(tempDir, "package.json").writeText("""{"name":"app","scripts":{"test":"vitest run"}}""")
            assertEquals("npm test", detector.detectTestCommand(tempDir))
            assertEquals("pytest -k fast", detector.detectTestCommand(tempDir, "pytest -k fast"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `builtin shell commands execute git workflow pkg status and which without proot`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val env = com.example.linux.LinuxEnvironment(context)
        val builtins = com.example.linux.BuiltinShellCommands(env)
        val workDir = java.nio.file.Files.createTempDirectory("yscode_git_test").toFile()
        try {
            val initRes = builtins.tryExecute("git init -b main && git checkout -b feature", workDir)
            assertTrue(initRes != null && initRes.exitCode == 0)
            assertTrue(java.io.File(workDir, ".git/HEAD").readText().contains("refs/heads/feature"))

            java.io.File(workDir, "index.html").writeText("<h1>Todo</h1>")
            val commitRes = builtins.tryExecute("""git add . && git commit -m "Add todo page"""", workDir)
            assertTrue(commitRes != null && commitRes.exitCode == 0)
            assertTrue(commitRes!!.stdout.contains("feature"))

            val logRes = builtins.tryExecute("git log", workDir)
            assertTrue(logRes != null && logRes.exitCode == 0 && logRes.stdout.contains("Add todo page"))

            val whichRes = builtins.tryExecute("which git", workDir)
            assertEquals("/yscode/bin/git", whichRes?.stdout)

            val pkgGit = builtins.tryExecute("apt-get update && apt-get install -y git", workDir)
            assertTrue(pkgGit != null && pkgGit.exitCode == 0)

            val pkgPython = builtins.tryExecute("pkg install python3", workDir)
            assertTrue(pkgPython != null && pkgPython.exitCode != 0 && pkgPython.stderr.contains("Android POSIX"))

            val helpRes = builtins.tryExecute("help", workDir)
            assertTrue(helpRes != null && helpRes.exitCode == 0 && helpRes.stdout.contains("git"))
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `prompt builder reports truthful posix mode when proot rootfs is absent`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val builder = (context as YSCodeApplication).container.promptBuilder
        val tempDir = java.nio.file.Files.createTempDirectory("yscode_prompt_test").toFile()
        try {
            val prompt = builder.buildSystemPrompt(
                projectDir = tempDir,
                mode = PermissionMode.ASK,
                availableTools = listOf("bash", "web_search")
            )
            assertTrue(prompt.contains("Android POSIX Sandbox"))
            assertTrue(prompt.contains("NEVER loop trying `apt-get`, `apk`, `ls /`"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

