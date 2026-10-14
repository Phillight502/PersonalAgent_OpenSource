package org.example.myapplication.data.repository

import kotlinx.coroutines.flow.Flow
import org.example.myapplication.data.local.db.AuditDao
import org.example.myapplication.data.local.db.AuditLogEntity

/**
 * 事项操作审计仓库：只追加写入，界面只读查看/清空
 */
class AuditRepository(private val dao: AuditDao) {

    fun observeRecent(limit: Int = 200): Flow<List<AuditLogEntity>> = dao.observeRecent(limit)

    suspend fun log(
        actor: String,
        action: String,
        targetId: Long? = null,
        summary: String? = null,
        detail: String? = null
    ) {
        dao.insert(
            AuditLogEntity(
                actor = actor,
                action = action,
                targetId = targetId,
                summary = summary,
                detail = detail
            )
        )
    }

    suspend fun clear() = dao.clear()

    suspend fun count(): Int = dao.count()
}
