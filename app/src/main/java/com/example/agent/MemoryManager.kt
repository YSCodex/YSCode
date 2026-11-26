// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.linux.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class MemoryUpdateResult(
    val updated: Boolean,
    val addedNotes: List<String> = emptyList(),
    val filePath: String = ""
)

class MemoryManager(
    private val globalMemoryDir: File
) {
    constructor(env: LinuxEnvironment) : this(File(env.rootHomeDir, ".yscode"))

    fun loadProjectMemory(projectDir: File): String {
        val projectMem = File(projectDir, "YSCODE.md")
        val globalMem = File(globalMemoryDir, "MEMORY.md")
        return buildString {
            if (globalMem.exists()) {
                val g = runCatching { globalMem.readText().trim() }.getOrDefault("")
                if (g.isNotBlank()) {
                    appendLine("### Global User Memory")
                    appendLine(g.take(2000))
                    appendLine()
                }
            }
            if (projectMem.exists()) {
                val p = runCatching { projectMem.readText().trim() }.getOrDefault("")
                if (p.isNotBlank()) {
                    appendLine("### Project Memory (YSCODE.md)")
                    appendLine(p.take(4500))
                }
            }
        }.trim()
    }

    suspend fun appendMemoryEntry(
        projectDir: File,
        sectionTitle: String,
        entry: String,
        scope: String = "project"
    ): MemoryUpdateResult = withContext(Dispatchers.IO) {
        val cleanEntry = entry.trim().removePrefix("-").trim()
        if (cleanEntry.isBlank()) return@withContext MemoryUpdateResult(false)

        val targetFile = if (scope == "global") {
            File(globalMemoryDir, "MEMORY.md")
        } else {
            File(projectDir, "YSCODE.md")
        }
        targetFile.parentFile?.mkdirs()

        val current = if (targetFile.exists()) {
            targetFile.readText()
        } else {
            if (scope == "global") {
                "# YSCode Global User Memory\n\n## User Preferences\n"
            } else {
                "# ${projectDir.name} — YSCode Project Memory\n\n## Tech Stack & Commands\n\n## Coding Conventions\n\n## User Preferences\n"
            }
        }

        // Deduplicate case-insensitively
        val normalizedNew = cleanEntry.lowercase().replace(Regex("\\s+"), " ")
        val existingNormalized = current.lines().map {
            it.trim().removePrefix("-").trim().lowercase().replace(Regex("\\s+"), " ")
        }
        if (normalizedNew in existingNormalized) {
            return@withContext MemoryUpdateResult(false, emptyList(), targetFile.absolutePath)
        }

        val updated = insertUnderSection(current, sectionTitle.trim(), "- $cleanEntry")
        targetFile.writeText(updated)
        MemoryUpdateResult(true, listOf(cleanEntry), targetFile.absolutePath)
    }

    suspend fun autoLearnFromTurn(
        projectDir: File,
        userText: String,
        verifiedTestCommand: String? = null
    ): MemoryUpdateResult = withContext(Dispatchers.IO) {
        val added = mutableListOf<String>()
        val extractedPrefs = extractDurablePreferences(userText)
        for ((section, note) in extractedPrefs) {
            val res = appendMemoryEntry(projectDir, section, note, scope = "project")
            if (res.updated) added += res.addedNotes
        }
        if (!verifiedTestCommand.isNullOrBlank()) {
            val res = appendMemoryEntry(
                projectDir = projectDir,
                sectionTitle = "Tech Stack & Commands",
                entry = "Verified test command: `$verifiedTestCommand`",
                scope = "project"
            )
            if (res.updated) added += res.addedNotes
        }
        MemoryUpdateResult(
            updated = added.isNotEmpty(),
            addedNotes = added,
            filePath = File(projectDir, "YSCODE.md").absolutePath
        )
    }

    fun extractDurablePreferences(userText: String): List<Pair<String, String>> {
        val text = userText.trim()
        if (text.length < 8) return emptyList()
        val found = mutableListOf<Pair<String, String>>()

        val lower = text.lowercase()
        when {
            lower.contains("hinglish") && (lower.contains("reply") || lower.contains("speak") || lower.contains("talk") || lower.contains("likh") || lower.contains("bat")) -> {
                found += "User Preferences" to "User prefers Hinglish explanations when chatting."
            }
            lower.contains("hindi") && (lower.contains("reply") || lower.contains("speak") || lower.contains("talk") || lower.contains("likh")) -> {
                found += "User Preferences" to "User prefers Hindi/Hinglish conversational responses."
            }
        }

        // Capture explicit rule sentences ("always ...", "never ...", "prefer ...", "hamesha ...")
        val sentences = text.split(Regex("[.\\n!?]+")).map { it.trim() }.filter { it.length in 10..140 }
        val triggers = listOf("always ", "never ", "don't ever ", "do not ever ", "prefer ", "remember that ", "make sure to always ", "hamesha ")
        for (s in sentences) {
            val sLow = s.lowercase()
            if (triggers.any { sLow.startsWith(it) || sLow.contains(" $it") }) {
                val section = if (sLow.contains("test") || sLow.contains("lint") || sLow.contains("format") || sLow.contains("indent") || sLow.contains("typescript") || sLow.contains("kotlin")) {
                    "Coding Conventions"
                } else {
                    "User Preferences"
                }
                found += section to s
            }
        }
        return found.distinctBy { it.second.lowercase() }.take(3)
    }

    companion object {
        fun insertUnderSection(markdown: String, sectionTitle: String, bulletLine: String): String {
            val lines = markdown.lines().toMutableList()
            val headerPattern = Regex("""^#{1,3}\s+${Regex.escape(sectionTitle)}\s*$""", RegexOption.IGNORE_CASE)
            val headerIdx = lines.indexOfFirst { headerPattern.matches(it.trim()) }
            if (headerIdx == -1) {
                val base = markdown.trimEnd()
                return buildString {
                    if (base.isNotBlank()) {
                        appendLine(base)
                        appendLine()
                    }
                    appendLine("## $sectionTitle")
                    appendLine(bulletLine)
                }
            }
            var insertIdx = headerIdx + 1
            while (insertIdx < lines.size && !lines[insertIdx].trimStart().startsWith("#")) {
                insertIdx++
            }
            while (insertIdx > headerIdx + 1 && lines[insertIdx - 1].isBlank()) {
                insertIdx--
            }
            lines.add(insertIdx, bulletLine)
            return lines.joinToString("\n").trimEnd() + "\n"
        }
    }
}
