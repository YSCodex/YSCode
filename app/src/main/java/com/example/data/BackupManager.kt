// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import android.content.Context
import android.net.Uri
import com.example.linux.LinuxEnvironment
import com.example.util.JsonHelper
import com.example.util.ZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class YSCodeBackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings,
    val projects: List<ProjectEntity>,
    val sessions: List<SessionEntity>,
    val messages: List<MessageEntity>,
    val providers: List<ProviderEntity>, // API keys NOT stored here; secretAlias only
    val mcpServers: List<McpServerEntity>,
    val skills: List<SkillMetaEntity>
)

class BackupManager(
    private val context: Context,
    private val db: YSCodeDatabase,
    private val settingsStore: SettingsStore,
    private val env: LinuxEnvironment
) {
    suspend fun exportBackupToUri(destUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = YSCodeBackupPayload(
                settings = settingsStore.current(),
                projects = db.projectDao().getAll(),
                sessions = db.sessionDao().getAll(),
                messages = db.messageDao().getAll(),
                providers = db.providerDao().getAll(),
                mcpServers = db.mcpServerDao().getAll(),
                skills = db.skillMetaDao().getAll()
            )
            val jsonBytes = JsonHelper.json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            val out = context.contentResolver.openOutputStream(destUri) ?: error("Cannot open output file")
            out.use { os ->
                ZipOutputStream(os).use { zos ->
                    zos.putNextEntry(ZipEntry("yscode_db.json"))
                    zos.write(jsonBytes)
                    zos.closeEntry()

                    addDirToZip(env.projectsDir, "projects", zos)
                    addDirToZip(env.globalSkillsDir, "skills", zos)
                    addDirToZip(env.globalCommandsDir, "commands", zos)
                }
            }
        }
    }

    suspend fun restoreBackupFromUri(srcUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val inp = context.contentResolver.openInputStream(srcUri) ?: error("Cannot open backup file")
            val stage = File(context.cacheDir, "restore_stage_${System.nanoTime()}").apply { mkdirs() }
            try {
                inp.use { ZipHelper.unzip(it, stage, maxBytes = 150L * 1024L * 1024L) }
                val dbFile = File(stage, "yscode_db.json")
                if (!dbFile.exists()) error("Invalid .yscode backup archive (missing yscode_db.json)")

                val payload = JsonHelper.json.decodeFromString<YSCodeBackupPayload>(dbFile.readText())
                settingsStore.save(payload.settings)

                payload.projects.forEach { db.projectDao().upsert(it) }
                payload.sessions.forEach { db.sessionDao().upsert(it) }
                payload.messages.forEach { db.messageDao().upsert(it) }
                payload.providers.forEach { db.providerDao().upsert(it) }
                payload.mcpServers.forEach { db.mcpServerDao().upsert(it) }
                payload.skills.forEach { db.skillMetaDao().upsert(it) }

                File(stage, "projects").takeIf { it.exists() }?.copyRecursively(env.projectsDir, overwrite = true)
                File(stage, "skills").takeIf { it.exists() }?.copyRecursively(env.globalSkillsDir, overwrite = true)
                File(stage, "commands").takeIf { it.exists() }?.copyRecursively(env.globalCommandsDir, overwrite = true)

                "Restored ${payload.projects.size} projects, ${payload.sessions.size} sessions, and ${payload.providers.size} providers (re-enter API keys for security)."
            } finally {
                stage.deleteRecursively()
            }
        }
    }

    private fun addDirToZip(dir: File, prefix: String, zos: ZipOutputStream) {
        if (!dir.exists()) return
        val base = dir.canonicalPath
        dir.walkTopDown()
            .onEnter { d -> d == dir || d.name !in setOf(".git", "node_modules", "build") }
            .filter { it.isFile }
            .forEach { f ->
                val rel = f.canonicalPath.removePrefix(base).trimStart(File.separatorChar).replace(File.separatorChar, '/')
                if (rel.isNotEmpty()) {
                    zos.putNextEntry(ZipEntry("$prefix/$rel"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
    }
}
