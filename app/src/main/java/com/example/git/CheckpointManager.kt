// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.git

import com.example.data.CheckpointDao
import com.example.data.CheckpointEntity
import com.example.linux.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class CheckpointManager(
    private val env: LinuxEnvironment,
    private val checkpointDao: CheckpointDao,
    private val changesRepository: ChangesRepository
) {
    private val excludedDirs = setOf(".git", "node_modules", "build", ".gradle", "__pycache__", ".venv")

    suspend fun createCheckpoint(
        sessionId: String,
        messageId: String,
        projectDir: File,
        label: String
    ): CheckpointEntity = withContext(Dispatchers.IO) {
        val id = "chk_" + UUID.randomUUID().toString().take(10)
        val snapDir = File(env.checkpointsDir, id).apply { mkdirs() }
        copyProjectSnapshot(projectDir, snapDir)
        val entity = CheckpointEntity(
            id = id,
            sessionId = sessionId,
            messageId = messageId,
            label = label.take(80),
            snapshotPath = snapDir.absolutePath
        )
        checkpointDao.upsert(entity)
        entity
    }

    suspend fun restoreCheckpoint(checkpoint: CheckpointEntity, projectDir: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapPath = checkpoint.snapshotPath ?: error("Snapshot path missing")
                val snapDir = File(snapPath)
                if (!snapDir.exists()) error("Checkpoint snapshot no longer exists on disk")

                // Remove files in projectDir (excluding .git & ignored dirs) that are not in snapshot
                projectDir.walkTopDown()
                    .onEnter { dir -> dir == projectDir || dir.name !in excludedDirs }
                    .filter { it.isFile }
                    .forEach { file ->
                        val rel = file.relativeTo(projectDir).path
                        if (!File(snapDir, rel).exists()) {
                            file.delete()
                        }
                    }

                // Restore snapshot files
                snapDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { src ->
                        val rel = src.relativeTo(snapDir).path
                        val dst = File(projectDir, rel)
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst, overwrite = true)
                    }
                changesRepository.clearTrackedOnly()
            }
        }

    suspend fun undoLast(sessionId: String, projectDir: File): Result<CheckpointEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val latest = checkpointDao.getForSession(sessionId).firstOrNull()
                    ?: error("No checkpoints found for this session")
                restoreCheckpoint(latest, projectDir).getOrThrow()
                latest
            }
        }

    private fun copyProjectSnapshot(srcDir: File, dstDir: File) {
        if (!srcDir.exists()) return
        var copiedBytes = 0L
        val maxBytes = 25L * 1024L * 1024L // cap snapshot at 25MB to avoid blowing mobile storage
        srcDir.walkTopDown()
            .onEnter { dir -> dir == srcDir || dir.name !in excludedDirs }
            .filter { it.isFile && it.length() < 2_000_000L }
            .forEach { file ->
                if (copiedBytes + file.length() > maxBytes) return@forEach
                val rel = file.relativeTo(srcDir).path
                val target = File(dstDir, rel)
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
                copiedBytes += file.length()
            }
    }
}
