// SPDX-License-Identifier: GPL-3.0-or-later
package com.example

import com.example.agent.HookEngine
import com.example.agent.MemoryManager
import com.example.agent.PromptBuilder
import com.example.agent.RepoMapIndex
import com.example.agent.TimelineItem
import com.example.agent.TimelineMapper
import com.example.agent.TimelineToolStep
import com.example.data.ProviderEntity
import com.example.llm.LlmContentPart
import com.example.llm.LlmMessage
import com.example.llm.ModelConfig
import com.example.mcp.McpConfigParser
import com.example.skills.SkillParser
import com.example.skills.StarterSkills
import com.example.tools.WebSearchTool
import com.example.ui.components.filterProviderModels
import com.example.util.DiffEngine
import com.example.util.HunkStatus
import com.example.util.JsonHelper
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class YSCodeCoreTests {

    @Test
    fun `diff engine computes hunks and applies hunk reject cleanly`() {
        val before = "line1\nline2\nline3\nline4\nline5"
        val after = "line1\nline2_edited\nline3\nline4\nline5_added\nline6"
        val diff = DiffEngine.computeDiff("sample.txt", before, after, contextLines = 0)
        assertTrue(diff.addedCount >= 2)
        assertTrue(diff.hunks.size >= 2)

        // Reject first hunk, accept second hunk
        val updatedHunks = diff.hunks.mapIndexed { idx, h ->
            h.copy(status = if (idx == 0) HunkStatus.REJECTED else HunkStatus.ACCEPTED)
        }
        val merged = DiffEngine.applyHunkDecisions(before, updatedHunks)
        assertTrue(merged.contains("line2"))
        assertTrue(!merged.contains("line2_edited"))
        assertTrue(merged.contains("line5_added"))
        assertTrue(merged.contains("line6"))
    }

    @Test
    fun `json helper repairs markdown fences trailing commas and unclosed braces`() {
        val malformed = """
            ```json
            {"path": "src/Main.kt", "line": 42,
            ```
        """.trimIndent()
        val parsed = JsonHelper.parseOrRepairObject(malformed)
        assertTrue(parsed.isSuccess)
        val obj = parsed.getOrThrow()
        assertEquals("src/Main.kt", obj["path"]?.jsonPrimitive?.content)
        assertEquals("42", obj["line"]?.jsonPrimitive?.content)
    }

    @Test
    fun `skill parser validates all bundled starter skills`() {
        assertEquals(7, StarterSkills.bundled.size)
        StarterSkills.bundled.forEach { (key, md) ->
            val res = SkillParser.parse(md)
            assertTrue("Skill $key failed to parse: ${res.exceptionOrNull()}", res.isSuccess)
            assertEquals(key, res.getOrThrow().name)
            assertTrue(res.getOrThrow().description.isNotBlank())
        }
    }

    @Test
    fun `mcp config parser parses claude desktop mcpServers json`() {
        val raw = """
            {
              "mcpServers": {
                "github": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-github"],
                  "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "tok" }
                },
                "remote-docs": {
                  "url": "https://mcp.example.com/mcp",
                  "headers": { "Authorization": "Bearer abc" }
                }
              }
            }
        """.trimIndent()
        val res = McpConfigParser.parseMcpServersJson(raw)
        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(2, list.size)
        assertEquals("stdio", list[0].transport)
        assertEquals("http", list[1].transport)
    }

    @Test
    fun `prompt builder detects casual greetings without misclassifying coding tasks`() {
        assertTrue(PromptBuilder.isCasualGreetingOrSmallTalk("hi"))
        assertTrue(PromptBuilder.isCasualGreetingOrSmallTalk("Hello!"))
        assertTrue(PromptBuilder.isCasualGreetingOrSmallTalk("Namaste kaise ho?"))
        assertTrue(PromptBuilder.isCasualGreetingOrSmallTalk("thanks"))
        assertFalse(PromptBuilder.isCasualGreetingOrSmallTalk("fix index.html and add styles"))
        assertFalse(PromptBuilder.isCasualGreetingOrSmallTalk("hi read main.kt"))
        assertFalse(PromptBuilder.isCasualGreetingOrSmallTalk("/help"))
    }

    @Test
    fun `timeline mapper merges tool calls with results and groups consecutive reads`() {
        val msgs = listOf(
            LlmMessage(
                id = "m1",
                role = "user",
                parts = listOf(LlmContentPart.Text("inspect project")),
                createdAt = 1L
            ),
            LlmMessage(
                id = "m2",
                role = "assistant",
                parts = listOf(
                    LlmContentPart.Reasoning("Checking files", durationMs = 2000L),
                    LlmContentPart.ToolUse("call_1", "read_file", """{"path":"index.html"}"""),
                    LlmContentPart.ToolUse("call_2", "read_file", """{"path":"README.md"}"""),
                    LlmContentPart.ToolUse("call_3", "ask_user", """{"question":"Pending?"}"""),
                    LlmContentPart.Text("All checked.")
                ),
                createdAt = 2L
            ),
            LlmMessage(
                id = "m3",
                role = "tool",
                parts = listOf(
                    LlmContentPart.ToolResult("call_1", "read_file", "<html></html>", isError = false),
                    LlmContentPart.ToolResult("call_2", "read_file", "# README", isError = false)
                ),
                createdAt = 3L
            )
        )

        val timeline = TimelineMapper.mapMessagesToTimeline(
            messages = msgs,
            hasPendingQuestion = true,
            hasPendingPlan = false
        )

        // Tool role message is merged into parent AssistantTurn, so only UserTurn + AssistantTurn exist
        assertEquals(2, timeline.size)
        val userTurn = timeline[0] as TimelineItem.UserTurn
        assertEquals("inspect project", userTurn.text)

        val assistantTurn = timeline[1] as TimelineItem.AssistantTurn
        assertEquals("All checked.", assistantTurn.text)
        assertEquals(2L, assistantTurn.reasoningDurationSec)
        // Two consecutive read_file calls collapsed into a single ReadGroup, and pending ask_user hidden
        assertEquals(1, assistantTurn.toolSteps.size)
        val group = assistantTurn.toolSteps[0] as TimelineToolStep.ReadGroup
        assertEquals(2, group.items.size)
        assertEquals("1 lines", group.items[0].summary)
    }

    @Test
    fun `web search parser extracts titles unwrapped urls and snippets from duckduckgo html`() {
        val sampleHtml = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines-overview.html&amp;rut=123">Kotlin <b>Coroutines</b> Guide</a>
              <a class="result__snippet">Official guide to asynchronous programming in Kotlin.</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://developer.android.com/compose">Jetpack Compose</a>
              <div class="result__snippet">Modern toolkit for native Android UI.</div>
            </div>
        """.trimIndent()

        val results = WebSearchTool.parseDuckDuckGoHtml(sampleHtml, maxResults = 5)
        assertEquals(2, results.size)
        assertEquals("Kotlin Coroutines Guide", results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", results[0].url)
        assertEquals("Official guide to asynchronous programming in Kotlin.", results[0].snippet)
        assertEquals("https://developer.android.com/compose", results[1].url)
    }

    @Test
    fun `repo map extracts multi-language symbols and ranks referenced files higher`() {
        val kotlinSrc = """
            package demo
            data class AuthSession(val token: String)
            interface TokenStore {
                fun loadToken(): String
            }
        """.trimIndent()
        val ktSymbols = RepoMapIndex.extractSymbolsFast(kotlinSrc, "kotlin")
        assertTrue(ktSymbols.any { it.name == "AuthSession" && it.kind == "class" })
        assertTrue(ktSymbols.any { it.name == "TokenStore" && it.kind == "interface" })
        assertTrue(ktSymbols.any { it.name == "loadToken" && it.kind == "fun" })

        val repoMap = RepoMapIndex(runner = null)
        val coreFile = com.example.agent.RepoFileSymbols(
            relativePath = "CoreAuth.kt",
            language = "kotlin",
            lastModified = 1L,
            sizeBytes = 100L,
            symbols = listOf(com.example.agent.RepoSymbol("AuthSession", "class", 2)),
            references = emptyList()
        )
        val consumer1 = com.example.agent.RepoFileSymbols(
            relativePath = "LoginScreen.kt",
            language = "kotlin",
            lastModified = 1L,
            sizeBytes = 100L,
            symbols = emptyList(),
            references = listOf("AuthSession")
        )
        val consumer2 = com.example.agent.RepoFileSymbols(
            relativePath = "SettingsScreen.kt",
            language = "kotlin",
            lastModified = 1L,
            sizeBytes = 100L,
            symbols = emptyList(),
            references = listOf("AuthSession")
        )
        val ranked = repoMap.rankFilesByReferences(listOf(consumer1, coreFile, consumer2))
        assertEquals("CoreAuth.kt", ranked.first().entry.relativePath)
    }

    @Test
    fun `memory manager inserts under structured sections and extracts durable preferences`() {
        val base = "# Demo\n\n## User Preferences\n- Existing rule\n\n## Coding Conventions\n"
        val updated = MemoryManager.insertUnderSection(base, "User Preferences", "- Always use dark theme")
        assertTrue(updated.contains("- Existing rule\n- Always use dark theme"))

        val withNewSection = MemoryManager.insertUnderSection(updated, "Tech Stack & Commands", "- Verified test command: `pytest -q`")
        assertTrue(withNewSection.contains("## Tech Stack & Commands\n- Verified test command: `pytest -q`"))

        val tempDir = Files.createTempDirectory("yscode_mem_test").toFile()
        try {
            val mgr = MemoryManager(tempDir)
            val prefs = mgr.extractDurablePreferences("Please reply in Hinglish and always run pytest after edits.")
            assertTrue(prefs.any { it.second.contains("Hinglish") })
            assertTrue(prefs.any { it.first == "Coding Conventions" && it.second.contains("pytest") })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `hook matcher matches glob-like tool filters`() {
        assertTrue(HookEngine.matchesTool("write_file|edit_file|multi_edit", "edit_file"))
        assertFalse(HookEngine.matchesTool("write_file|edit_file", "bash"))
        assertTrue(HookEngine.matchesTool("*", "bash"))
    }

    @Test
    fun `model picker filter finds models by query and capability across providers`() {
        val openRouterModels = JsonHelper.json.encodeToString(
            listOf(
                ModelConfig("anthropic/claude-sonnet-4", "Claude Sonnet 4", supportsTools = true, supportsReasoning = true),
                ModelConfig("deepseek/deepseek-chat-v3", "DeepSeek V3", supportsTools = true, supportsReasoning = false),
                ModelConfig("xiaomi/mimo-v2.6-flash", "MiMo Flash", supportsTools = true, supportsReasoning = true)
            )
        )
        val providers = listOf(
            ProviderEntity(
                id = "p1",
                name = "OpenRouter",
                type = "OPENAI_COMPAT",
                baseUrl = "https://openrouter.ai/api/v1",
                secretAlias = "k1",
                modelsJson = openRouterModels
            )
        )

        val searchMimo = filterProviderModels(providers, query = "mimo")
        assertEquals(1, searchMimo.size)
        assertEquals("xiaomi/mimo-v2.6-flash", searchMimo.first().model.id)

        val reasoningOnly = filterProviderModels(providers, query = "", onlyReasoning = true)
        assertEquals(2, reasoningOnly.size)
    }

    @Test
    fun `web search lite parser extracts titles unwrapped urls and snippets`() {
        val sampleLiteHtml = """
            <table>
              <tr>
                <td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fff.garena.com%2Fen&amp;rut=1">Free Fire Official</a></td>
              </tr>
              <tr>
                <td class="result-snippet">Battle Royale mobile game by Garena.</td>
              </tr>
            </table>
        """.trimIndent()
        val results = WebSearchTool.parseDuckDuckGoLiteHtml(sampleLiteHtml, maxResults = 5)
        assertEquals(1, results.size)
        assertEquals("Free Fire Official", results[0].title)
        assertEquals("https://ff.garena.com/en", results[0].url)
        assertEquals("Battle Royale mobile game by Garena.", results[0].snippet)
    }

    @Test
    fun `markdown parser parses tables blockquotes numbered lists and rules`() {
        val md = """
            # Report
            
            | Feature | Status | Link |
            | --- | --- | --- |
            | Search | Ready | [Docs](https://example.com) |
            | Terminal | Active | `git` |
            
            > Important summary note
            
            1. First step
            2. Second step
            
            ---
        """.trimIndent()
        val blocks = com.example.ui.components.parseMarkdownBlocks(md)
        val table = blocks.filterIsInstance<com.example.ui.components.MdBlock.Table>().firstOrNull()
        assertTrue(table != null)
        assertEquals(listOf("Feature", "Status", "Link"), table!!.headers)
        assertEquals(2, table.rows.size)
        assertEquals("Search", table.rows[0][0])
        assertEquals("[Docs](https://example.com)", table.rows[0][2])

        assertTrue(blocks.any { it is com.example.ui.components.MdBlock.Quote && it.text == "Important summary note" })
        assertEquals(2, blocks.filterIsInstance<com.example.ui.components.MdBlock.NumberedItem>().size)
        assertTrue(blocks.any { it is com.example.ui.components.MdBlock.HorizontalRule })
    }

    @Test
    fun `builtin shell tokenizer and operator detection handle quotes and chains`() {
        val tokens = com.example.linux.BuiltinShellCommands.tokenize("""git commit -m "hello world" 'extra'""")
        assertEquals(listOf("git", "commit", "-m", "hello world", "extra"), tokens)

        assertFalse(com.example.linux.BuiltinShellCommands.hasShellOperatorsRequiringSh("""git init && git commit -m "a | b""""))
        assertTrue(com.example.linux.BuiltinShellCommands.hasShellOperatorsRequiringSh("ls -la | grep Main"))
        assertTrue(com.example.linux.BuiltinShellCommands.hasShellOperatorsRequiringSh("echo hello > out.txt"))
    }
}


