// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val settingsJson: String = "{}",
    /** [STORAGE_ROOTFS] copies the folder into the Linux rootfs; [STORAGE_LOCAL_PATH] works in place. */
    @ColumnInfo(defaultValue = "'ROOTFS'")
    val storageMode: String = STORAGE_ROOTFS,
    /** Absolute host path of the real folder when [storageMode] is [STORAGE_LOCAL_PATH]. */
    val localPath: String? = null
) {
    companion object {
        const val STORAGE_ROOTFS = "ROOTFS"
        const val STORAGE_LOCAL_PATH = "LOCAL_PATH"
    }
}

@Serializable
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val providerId: String = "",
    val model: String = "",
    val mode: String = "ask",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tokensIn: Long = 0L,
    val tokensOut: Long = 0L,
    val estimatedCostUsd: Double = 0.0
)

@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String, // "user", "assistant", "tool", "system"
    val contentJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val parentId: String? = null,
    val isCompactedSummary: Boolean = false
)

@Serializable
@Entity(
    tableName = "checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String,
    val label: String,
    val gitRef: String? = null,
    val snapshotPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // "OPENAI_COMPAT", "ANTHROPIC", "GEMINI"
    val baseUrl: String,
    val secretAlias: String,
    val headersJson: String = "{}",
    val modelsJson: String = "[]",
    val defaultModel: String = "",
    val isDefault: Boolean = false
)

@Serializable
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val scope: String, // "global" or "project"
    val projectId: String? = null,
    val name: String,
    val transport: String, // "stdio", "http", "sse"
    val configJson: String,
    val enabled: Boolean = true,
    val disabledToolsJson: String = "[]"
)

@Serializable
@Entity(tableName = "skills_meta")
data class SkillMetaEntity(
    @PrimaryKey val path: String,
    val name: String,
    val description: String,
    val scope: String, // "global" or "project"
    val projectId: String? = null,
    val enabled: Boolean = true,
    val allowedTools: String = ""
)


@Serializable
@Entity(tableName = "toolchains")
data class ToolchainEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Real host path of the toolchain (inside the rootfs or shared storage). */
    val hostPath: String,
    /** Path the toolchain is visible at from inside PRoot, e.g. /opt/jdk17. */
    val guestPath: String,
    val readOnly: Boolean = false,
    val envJson: String = "{}",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "usage_events")
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val projectId: String,
    val model: String,
    val ts: Long,
    val tokensIn: Long,
    val tokensOut: Long,
    val costUsd: Double,
    val messages: Int
)
