package org.example.myapplication.data.repository

import kotlinx.coroutines.flow.Flow
import org.example.myapplication.data.local.db.ChatDao
import org.example.myapplication.data.local.db.ChatMessageEntity

/**
 * 聊天记录仓库（单一会话）
 */
class ChatRepository(private val dao: ChatDao) {

    /** 界面观察：全部历史正序 */
    fun observeAll(): Flow<List<ChatMessageEntity>> = dao.observeAll()

    /**
     * AI 上下文：最近 [limit] 条，返回正序
     * （DAO 按倒序取最近 N 条，这里翻回时间正序便于拼接 messages）
     */
    suspend fun recentForContext(limit: Int = 20): List<ChatMessageEntity> =
        dao.getRecent(limit).reversed()

    /** 全部消息（导出备份用） */
    suspend fun getAll(): List<ChatMessageEntity> = dao.getAll()

    suspend fun add(role: String, text: String, image: ByteArray? = null): Long =
        dao.insert(ChatMessageEntity(role = role, text = text, image = image))

    suspend fun delete(id: Long) = dao.deleteById(id)

    /** 备份导入：保留原 id 批量写入，同 id 覆盖 */
    suspend fun importAll(messages: List<ChatMessageEntity>) = dao.importAll(messages)

    suspend fun clear() = dao.clear()

    suspend fun count(): Int = dao.count()

    /** 未读 AI 回复数：id 大于已读水位 [afterId] 的 assistant 消息 */
    fun observeAssistantCountAfter(afterId: Long): Flow<Int> =
        dao.observeAssistantCountAfter(afterId)

    /** 最新一条 AI 回复的 id；无消息时返回 null */
    suspend fun latestAssistantId(): Long? = dao.latestAssistantId()
}
