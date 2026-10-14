package org.example.myapplication.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用唯一 Room 数据库，包含四张表：
 * items（事项）、chat_messages（聊天记录）、memories（长期记忆）、audit_log（事项操作日志）
 *
 * v2 起建立正式 Migration 机制：此后任何表结构变更必须递增版本并提供 Migration，
 * 不再允许「清空重建」，防止升级丢失用户数据。
 *
 * v3：items 增加 endTime（结束时间）、attendees（人物）、scope（工作/私人）三列。
 */
@Database(
    entities = [
        ItemEntity::class,
        ChatMessageEntity::class,
        MemoryEntity::class,
        AuditLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun auditDao(): AuditDao

    companion object {
        private const val DB_NAME = "personal_agent.db"

        /**
         * v1 → v2：新增事项操作审计日志表。
         * 列定义必须与 [AuditLogEntity] 的 Room 期望结构逐字一致。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audit_log` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`actor` TEXT NOT NULL, " +
                        "`action` TEXT NOT NULL, " +
                        "`targetType` TEXT NOT NULL, " +
                        "`targetId` INTEGER, " +
                        "`summary` TEXT, " +
                        "`detail` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v2 → v3：items 增加结束时间、人物、工作/私人范围三列。
         * 列名与可空性必须与 [ItemEntity] 逐字一致；scope 非空需带默认值。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `endTime` TEXT")
                db.execSQL("ALTER TABLE `items` ADD COLUMN `attendees` TEXT")
                db.execSQL(
                    "ALTER TABLE `items` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'PERSONAL'"
                )
            }
        }

        /** 简易单例：MainActivity 初始化后全局共用一个实例（手动 DI） */
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
