// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY pinned DESC, lastOpenedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY pinned DESC, lastOpenedAt DESC")
    suspend fun getAll(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeForProject(projectId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getForSession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAt ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE contentJson LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT 50")
    suspend fun searchMessages(query: String): List<MessageEntity>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND createdAt > :afterTimestamp")
    suspend fun deleteAfter(sessionId: String, afterTimestamp: Long)
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoints WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeForSession(sessionId: String): Flow<List<CheckpointEntity>>

    @Query("SELECT * FROM checkpoints WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getForSession(sessionId: String): List<CheckpointEntity>

    @Query("SELECT * FROM checkpoints WHERE id = :id")
    suspend fun getById(id: String): CheckpointEntity?

    @Upsert
    suspend fun upsert(checkpoint: CheckpointEntity)

    @Query("DELETE FROM checkpoints WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY isDefault DESC, name ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY isDefault DESC, name ASC")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Upsert
    suspend fun upsert(provider: ProviderEntity)

    @Query("UPDATE providers SET isDefault = 0")
    suspend fun clearDefaults()

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    suspend fun getAll(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun getById(id: String): McpServerEntity?

    @Upsert
    suspend fun upsert(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SkillMetaDao {
    @Query("SELECT * FROM skills_meta ORDER BY name ASC")
    fun observeAll(): Flow<List<SkillMetaEntity>>

    @Query("SELECT * FROM skills_meta ORDER BY name ASC")
    suspend fun getAll(): List<SkillMetaEntity>

    @Upsert
    suspend fun upsert(skill: SkillMetaEntity)

    @Upsert
    suspend fun upsertAll(skills: List<SkillMetaEntity>)

    @Query("DELETE FROM skills_meta WHERE path = :path")
    suspend fun deleteByPath(path: String)
}


@Dao
interface ToolchainDao {
    @Query("SELECT * FROM toolchains ORDER BY name ASC")
    fun observeAll(): Flow<List<ToolchainEntity>>

    @Query("SELECT * FROM toolchains WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getAllEnabled(): List<ToolchainEntity>

    @Query("SELECT * FROM toolchains WHERE id = :id")
    suspend fun getById(id: String): ToolchainEntity?

    @Upsert
    suspend fun upsert(toolchain: ToolchainEntity)

    @Query("DELETE FROM toolchains WHERE id = :id")
    suspend fun deleteById(id: String)
}

data class DailyUsage(
    val day: Long,
    val tokens: Long,
    val messages: Long,
    val sessions: Long,
    val cost: Double
)

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(event: UsageEventEntity)

    @Query(
        "SELECT ((ts + :tzOffsetMs) / 86400000) AS day, " +
            "SUM(tokensIn + tokensOut) AS tokens, " +
            "SUM(messages) AS messages, " +
            "COUNT(DISTINCT sessionId) AS sessions, " +
            "SUM(costUsd) AS cost " +
            "FROM usage_events GROUP BY day ORDER BY day"
    )
    fun observeDaily(tzOffsetMs: Long): Flow<List<DailyUsage>>
}
