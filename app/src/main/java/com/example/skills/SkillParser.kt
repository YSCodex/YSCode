// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.skills

data class ParsedSkill(
    val name: String,
    val description: String,
    val allowedTools: List<String>,
    val body: String,
    val rawContent: String
)

object SkillParser {

    fun parse(rawMarkdown: String, fallbackName: String = ""): Result<ParsedSkill> {
        val normalized = rawMarkdown.replace("\r\n", "\n").trimStart()
        if (!normalized.startsWith("---")) {
            return Result.failure(IllegalArgumentException("SKILL.md must begin with YAML frontmatter (---)"))
        }
        val secondFence = normalized.indexOf("\n---", startIndex = 3)
        if (secondFence < 0) {
            return Result.failure(IllegalArgumentException("Unclosed YAML frontmatter in SKILL.md"))
        }
        val frontmatter = normalized.substring(3, secondFence).trim()
        val body = normalized.substring(secondFence + 4).trim()

        var name = fallbackName
        var description = ""
        val allowedTools = mutableListOf<String>()
        var currentKey: String? = null
        val descLines = mutableListOf<String>()

        for (line in frontmatter.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if ((line.startsWith("  ") || line.startsWith("\t")) && currentKey == "description") {
                descLines += trimmed
                continue
            }
            if (trimmed.startsWith("- ") && currentKey == "allowed-tools") {
                allowedTools += trimmed.removePrefix("- ").trim()
                continue
            }
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim().lowercase()
                val value = trimmed.substring(colonIdx + 1).trim().trim('"', '\'')
                currentKey = key
                when (key) {
                    "name" -> name = value
                    "description" -> {
                        if (value != ">" && value != "|" && value.isNotEmpty()) {
                            descLines += value
                        }
                    }
                    "allowed-tools", "allowed_tools" -> {
                        if (value.isNotEmpty() && !value.startsWith("[")) {
                            allowedTools += value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        } else if (value.startsWith("[") && value.endsWith("]")) {
                            allowedTools += value.removeSurrounding("[", "]")
                                .split(",").map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
                        }
                    }
                }
            }
        }
        description = descLines.joinToString(" ").trim()
        if (name.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing required 'name' field in SKILL.md frontmatter"))
        }
        if (description.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing required 'description' field in SKILL.md frontmatter"))
        }
        return Result.success(
            ParsedSkill(
                name = sanitizeSkillName(name),
                description = description,
                allowedTools = allowedTools,
                body = body,
                rawContent = rawMarkdown
            )
        )
    }

    fun sanitizeSkillName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').ifEmpty { "custom-skill" }
}
