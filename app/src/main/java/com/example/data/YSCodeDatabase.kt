// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        CheckpointEntity::class,
        ProviderEntity::class,
        McpServerEntity::class,
        SkillMetaEntity::class,
        ToolchainEntity::class,
        UsageEventEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class YSCodeDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun providerDao(): ProviderDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun skillMetaDao(): SkillMetaDao
    abstract fun toolchainDao(): ToolchainDao
    abstract fun usageDao(): UsageDao

    companion object {
        fun build(context: Context): YSCodeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                YSCodeDatabase::class.java,
                "yscode.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        /** Adds usage_events and backfills one approximate event per existing session. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS usage_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId TEXT NOT NULL, projectId TEXT NOT NULL, model TEXT NOT NULL, " +
                        "ts INTEGER NOT NULL, tokensIn INTEGER NOT NULL, tokensOut INTEGER NOT NULL, " +
                        "costUsd REAL NOT NULL, messages INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO usage_events (sessionId, projectId, model, ts, tokensIn, tokensOut, costUsd, messages) " +
                        "SELECT id, projectId, model, updatedAt, tokensIn, tokensOut, estimatedCostUsd, " +
                        "(SELECT COUNT(*) FROM messages WHERE messages.sessionId = sessions.id) " +
                        "FROM sessions WHERE tokensIn + tokensOut > 0"
                )
            }
        }
        /** Adds in-place project storage mode and the external toolchain registry. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE projects ADD COLUMN storageMode TEXT NOT NULL DEFAULT 'ROOTFS'"
                )
                db.execSQL("ALTER TABLE projects ADD COLUMN localPath TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS toolchains (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, hostPath TEXT NOT NULL, guestPath TEXT NOT NULL, " +
                        "readOnly INTEGER NOT NULL, envJson TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                )
            }
        }
    }
}
