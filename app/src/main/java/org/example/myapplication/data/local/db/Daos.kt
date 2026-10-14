package org.example.myapplication.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 事项表数据访问
 * 列表统一按「无日期排最后 → 日期 → 时间」排序
 */
@Dao
interface ItemDao {

    /** 按状态和类型筛选（type 为 null 表示不限类型）；用于首页与归档页 */
    @Query(
        "SELECT * FROM items WHERE status = :status AND (:type IS NULL OR type = :type) " +
            "ORDER BY (itemDate IS NULL), itemDate, itemTime, id DESC"
    )
    fun observe(status: String, type: String?): Flow<List<ItemEntity>>

    /** 已完成/已取消的归档事项，最近归档的在前 */
    @Query("SELECT * FROM items WHERE status != 'ACTIVE' ORDER BY completedAt DESC, id DESC")
    fun observeArchived(): Flow<List<ItemEntity>>

    /** 某日期区间内的有效事项（供 AI 背景注入，如未来 7 天） */
    @Query(
        "SELECT * FROM items WHERE status = 'ACTIVE' AND itemDate IS NOT NULL " +
            "AND itemDate BETWEEN :from AND :to ORDER BY itemDate, itemTime"
    )
    suspend fun getBetween(from: String, to: String): List<ItemEntity>

    /**
     * 锚点日期不晚于 [date] 的有效事项（含全天）。
     * 供冲突检测/空档查询：重复事项锚点在过去、实例落在目标日期时也能被取出，
     * 是否真正在当天有实例由 ScheduleLogic.occursOnDate 判定。
     */
    @Query(
        "SELECT * FROM items WHERE status = 'ACTIVE' AND itemDate IS NOT NULL " +
            "AND itemDate != '' AND itemDate <= :date ORDER BY itemDate, itemTime"
    )
    suspend fun getAnchoredOnOrBefore(date: String): List<ItemEntity>

    /** 全部事项（导出备份用） */
    @Query("SELECT * FROM items ORDER BY id ASC")
    suspend fun getAll(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: Long): ItemEntity?

    /**
     * 全部有效事项（含无日期），无日期排最后。
     * 供 AI query_items 的 all 范围使用——旧实现走 BETWEEN 日期导致无日期事项永远查不到
     */
    @Query(
        "SELECT * FROM items WHERE status = 'ACTIVE' " +
            "ORDER BY (itemDate IS NULL), itemDate, itemTime, id DESC"
    )
    suspend fun getAllActive(): List<ItemEntity>

    /**
     * 需要调度提醒的有效事项：
     * ACTIVE + 已开启提醒 + 非全天 + 日期与时间均非空
     */
    @Query(
        "SELECT * FROM items WHERE status = 'ACTIVE' AND reminderEnabled = 1 AND isAllDay = 0 " +
            "AND itemDate IS NOT NULL AND itemDate != '' " +
            "AND itemTime IS NOT NULL AND itemTime != ''"
    )
    suspend fun getNeedingReminder(): List<ItemEntity>

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    /** 标记完成/取消（归档），记录完成时间 */
    @Query("UPDATE items SET status = :status, completedAt = :completedAt, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, completedAt: Long?, now: Long)

    /** 归档恢复为有效 */
    @Query("UPDATE items SET status = 'ACTIVE', completedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM items")
    suspend fun clear()

    /** 备份导入：保留原 id 批量写入，同 id 覆盖 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importAll(items: List<ItemEntity>)

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM items WHERE status = 'ACTIVE'")
    suspend fun activeCount(): Int
}

/**
 * 聊天消息表数据访问（单一会话）
 */
@Dao
interface ChatDao {

    /** 全部历史，按时间正序（界面渲染与导出用） */
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    /** 最近 [limit] 条正序返回（AI 上下文用，调用处取最近 20 条） */
    @Query("SELECT * FROM chat_messages ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ChatMessageEntity>

    /** 全部消息（导出备份用），时间正序 */
    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM chat_messages")
    suspend fun clear()

    /** 备份导入：保留原 id 批量写入，同 id 覆盖 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importAll(messages: List<ChatMessageEntity>)

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int

    /** 未读 AI 回复数：id 大于已读水位的 assistant 消息（导航栏红点驱动） */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE role = 'assistant' AND id > :afterId")
    fun observeAssistantCountAfter(afterId: Long): Flow<Int>

    /** 最新一条 AI 回复的 id；无消息时返回 null（已读水位初始化/更新用） */
    @Query("SELECT MAX(id) FROM chat_messages WHERE role = 'assistant'")
    suspend fun latestAssistantId(): Long?
}

/**
 * 长期记忆表数据访问
 */
@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY category, id")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY category, id")
    suspend fun getAll(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category AND title = :title LIMIT 1")
    suspend fun find(category: String, title: String): MemoryEntity?

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()

    /** 备份导入：保留原 id 批量写入，同 id 覆盖 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importAll(memories: List<MemoryEntity>)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
}

/**
 * 事项操作审计日志表数据访问（只追加，不提供单条修改）
 */
@Dao
interface AuditDao {

    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>>

    @Insert
    suspend fun insert(log: AuditLogEntity): Long

    @Query("DELETE FROM audit_log")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Int
}
