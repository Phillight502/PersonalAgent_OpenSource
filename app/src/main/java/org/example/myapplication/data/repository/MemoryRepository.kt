package org.example.myapplication.data.repository

import kotlinx.coroutines.flow.Flow
import org.example.myapplication.data.local.db.MemoryDao
import org.example.myapplication.data.local.db.MemoryEntity

/**
 * 长期记忆仓库：画像事实 / 人脉 / 纪念日 / 笔记
 */
class MemoryRepository(private val dao: MemoryDao) {

    fun observeAll(): Flow<List<MemoryEntity>> = dao.observeAll()

    suspend fun getAll(): List<MemoryEntity> = dao.getAll()

    /**
     * 保存记忆：同「分类 + 标题」视为同一条，更新内容（避免 AI 重复保存）；
     * title 为空的自由笔记每次新增
     */
    suspend fun save(
        category: String,
        title: String?,
        content: String,
        memoryDate: String? = null
    ): Long {
        val normalizedTitle = title?.trim()?.ifBlank { null }
        if (normalizedTitle != null) {
            val existing = dao.find(category, normalizedTitle)
            if (existing != null) {
                dao.update(
                    existing.copy(
                        content = content,
                        memoryDate = memoryDate ?: existing.memoryDate,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                return existing.id
            }
        }
        return dao.insert(
            MemoryEntity(
                category = category,
                title = normalizedTitle,
                content = content,
                memoryDate = memoryDate
            )
        )
    }

    suspend fun getById(id: Long): MemoryEntity? = dao.getById(id)

    /** 保存编辑（界面记忆管理用），刷新 updatedAt */
    suspend fun update(memory: MemoryEntity) {
        dao.update(memory.copy(updatedAt = System.currentTimeMillis()))
    }

    /** 删除单条记忆（AI 忘记工具 / 界面记忆管理用） */
    suspend fun delete(id: Long) = dao.deleteById(id)

    /** 备份导入：保留原 id 批量写入，同 id 覆盖 */
    suspend fun importAll(memories: List<MemoryEntity>) = dao.importAll(memories)

    suspend fun clear() = dao.clear()

    suspend fun count(): Int = dao.count()
}
