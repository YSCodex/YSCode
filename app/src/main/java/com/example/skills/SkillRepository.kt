// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.skills

import com.example.data.SkillMetaDao
import com.example.data.SkillMetaEntity
import com.example.linux.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class LoadedSkill(
    val meta: SkillMetaEntity,
    val parsed: ParsedSkill,
    val folder: File,
    val helperFiles: List<String>
)

data class DiscoverSkillRepo(
    val name: String,
    val description: String,
    val githubUrl: String,
    val tags: List<String>
)

class SkillRepository(
    private val env: LinuxEnvironment,
    private val dao: SkillMetaDao
) {
    val discoverCatalog: List<DiscoverSkillRepo> = listOf(
        DiscoverSkillRepo(
            name = "Anthropic Official Skills",
            description = "Curated SKILL.md workflows for documents, code review, testing, and skill authoring.",
            githubUrl = "https://github.com/anthropics/skills",
            tags = listOf("official", "workflows")
        ),
        DiscoverSkillRepo(
            name = "Superpowers Agent Skills",
            description = "TDD, systematic debugging, verification-before-completion, and planning skills.",
            githubUrl = "https://github.com/obra/superpowers",
            tags = listOf("tdd", "debug", "planning")
        ),
        DiscoverSkillRepo(
            name = "Reverse Engineer Anything (REA)",
            description = "Decompile APKs/JARs with headless JADX + Apktool over MCP (Android reverse engineering).",
            githubUrl = "https://github.com/morluto/rea",
            tags = listOf("reverse-engineering", "apk", "android", "mcp")
        )
    )

    fun observeSkills(): Flow<List<SkillMetaEntity>> = dao.observeAll()

    suspend fun ensureStarterSkillsInstalled() = withContext(Dispatchers.IO) {
        val globalDir = env.globalSkillsDir.apply { mkdirs() }
        for ((slug, content) in StarterSkills.bundled) {
            val skillFolder = File(globalDir, slug).apply { mkdirs() }
            val file = File(skillFolder, "SKILL.md")
            if (!file.exists()) {
                file.writeText(content.trim() + "\n")
            }
        }
        rescan(null)
    }

    suspend fun rescan(projectDir: File?): List<SkillMetaEntity> = withContext(Dispatchers.IO) {
        val existingStates = dao.getAll().associateBy { it.path }
        val discovered = mutableListOf<SkillMetaEntity>()

        scanDirectory(env.globalSkillsDir, "global", null, existingStates, discovered)
        if (projectDir != null) {
            val projSkills = File(projectDir, ".yscode/skills")
            scanDirectory(projSkills, "project", projectDir.name, existingStates, discovered)
        }

        val discoveredPaths = discovered.map { it.path }.toSet()
        existingStates.keys.filter { it !in discoveredPaths }.forEach { stale ->
            dao.deleteByPath(stale)
        }
        dao.upsertAll(discovered)
        discovered
    }

    suspend fun toggleSkill(path: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val existing = dao.getAll().find { it.path == path } ?: return@withContext
        dao.upsert(existing.copy(enabled = enabled))
    }

    suspend fun saveSkillContent(skillDir: File, markdown: String, scope: String, projectId: String?): Result<SkillMetaEntity> =
        withContext(Dispatchers.IO) {
            val parsed = SkillParser.parse(markdown, skillDir.name).getOrElse { return@withContext Result.failure(it) }
            skillDir.mkdirs()
            val skillFile = File(skillDir, "SKILL.md")
            skillFile.writeText(markdown.trimEnd() + "\n")
            val entity = SkillMetaEntity(
                path = skillFile.canonicalPath,
                name = parsed.name,
                description = parsed.description,
                scope = scope,
                projectId = projectId,
                enabled = true,
                allowedTools = parsed.allowedTools.joinToString(",")
            )
            dao.upsert(entity)
            Result.success(entity)
        }

    suspend fun deleteSkill(path: String) = withContext(Dispatchers.IO) {
        val skillFile = File(path)
        val folder = skillFile.parentFile
        if (folder != null && folder.exists() && folder.name != "skills") {
            folder.deleteRecursively()
        } else {
            skillFile.delete()
        }
        dao.deleteByPath(path)
    }

    suspend fun loadSkillForUse(name: String, projectDir: File?): LoadedSkill? = withContext(Dispatchers.IO) {
        val all = rescan(projectDir)
        val match = all.firstOrNull { it.enabled && it.name.equals(name.trim(), ignoreCase = true) }
            ?: return@withContext null
        val file = File(match.path)
        if (!file.exists()) return@withContext null
        val parsed = SkillParser.parse(file.readText(), match.name).getOrNull() ?: return@withContext null
        val folder = file.parentFile ?: env.globalSkillsDir
        val helpers = folder.walkTopDown()
            .filter { it.isFile && it != file }
            .map { it.relativeTo(folder).path }
            .toList()
        LoadedSkill(match, parsed, folder, helpers)
    }

    suspend fun buildSkillsIndex(projectDir: File?): String = withContext(Dispatchers.IO) {
        val active = rescan(projectDir).filter { it.enabled }
        if (active.isEmpty()) return@withContext "(none)"
        active.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    private fun scanDirectory(
        rootDir: File,
        scope: String,
        projectId: String?,
        existing: Map<String, SkillMetaEntity>,
        out: MutableList<SkillMetaEntity>
    ) {
        if (!rootDir.exists() || !rootDir.isDirectory) return
        rootDir.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            val mdFile = File(folder, "SKILL.md")
            if (mdFile.exists() && mdFile.isFile) {
                val parsed = SkillParser.parse(mdFile.readText(), folder.name).getOrNull() ?: return@forEach
                val canonical = mdFile.canonicalPath
                val wasEnabled = existing[canonical]?.enabled ?: true
                out += SkillMetaEntity(
                    path = canonical,
                    name = parsed.name,
                    description = parsed.description,
                    scope = scope,
                    projectId = projectId,
                    enabled = wasEnabled,
                    allowedTools = parsed.allowedTools.joinToString(",")
                )
            }
        }
    }
}
