package org.example.myapplication.data.repository

import kotlinx.coroutines.flow.Flow
import org.example.myapplication.data.local.db.AuditAction
import org.example.myapplication.data.local.db.AuditActor
import org.example.myapplication.data.local.db.AuditDao
import org.example.myapplication.data.local.db.ItemDao
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.local.db.Scope
import org.example.myapplication.data.local.db.Status
import org.example.myapplication.reminder.ReminderScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 事项仓库：UI 与 AI 工具层统一通过它读写 items 表。
 *
 * 所有写操作在此与 [ReminderScheduler] 挂钩，手动操作与 AI 落库（含确认后续跑）
 * 都能自动同步系统闹钟：
 * - add/update/restore → 按新值调度
 * - complete/cancel/delete/clear → 取消闹钟并撤销在用通知
 *
 * 正式写操作同时写入 audit_log（[actor] 区分 MANUAL/AI/SYSTEM）；
 * Debug 测试通道（insertForTest/deleteTestItems）不记审计，避免污染台账。
 */
class ItemRepository(
    private val dao: ItemDao,
    private val reminderScheduler: ReminderScheduler,
    private val auditDao: AuditDao
) {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /** 首页列表：指定类型的有效事项（type 为 null 时为全部） */
    fun observeActive(type: String?): Flow<List<ItemEntity>> =
        dao.observe(Status.ACTIVE, type)

    /** 归档列表（已完成/已取消） */
    fun observeArchived(): Flow<List<ItemEntity>> = dao.observeArchived()

    /** 未来 [days] 天（含今天）的有效事项，供 AI 背景注入 */
    suspend fun upcoming(days: Int = 7): List<ItemEntity> {
        val cal = Calendar.getInstance()
        val from = dateFmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, days - 1)
        val to = dateFmt.format(cal.time)
        return dao.getBetween(from, to)
    }

    /** 全部事项（导出备份用） */
    suspend fun getAll(): List<ItemEntity> = dao.getAll()

    /** 全部有效事项（含无日期，无日期排最后），供 AI query_items(all) */
    suspend fun activeAll(): List<ItemEntity> = dao.getAllActive()

    /**
     * 指定日期可能落在当天的全部有效事项（锚点 ≤ 当天，含全天、含过去锚点的重复事项），
     * 供冲突检测/空档查询；是否为当天实例由 ScheduleLogic 判定。
     */
    suspend fun activeOnDate(date: String): List<ItemEntity> = dao.getAnchoredOnOrBefore(date)

    suspend fun getById(id: Long): ItemEntity? = dao.getById(id)

    /** 是否存在需要提醒的有效事项（UI 据此补请通知/精确闹钟权限） */
    suspend fun hasItemsNeedingReminder(): Boolean = dao.getNeedingReminder().isNotEmpty()

    suspend fun add(item: ItemEntity, actor: String = AuditActor.MANUAL): Long {
        val id = dao.insert(item)
        reminderScheduler.schedule(item.copy(id = id))
        audit(actor, AuditAction.CREATE, id, item.title)
        return id
    }

    /** 追加一条事项操作审计 */
    private suspend fun audit(
        actor: String,
        action: String,
        targetId: Long,
        summary: String?,
        detail: String? = null
    ) {
        auditDao.insert(
            org.example.myapplication.data.local.db.AuditLogEntity(
                actor = actor,
                action = action,
                targetId = targetId,
                summary = summary,
                detail = detail
            )
        )
    }

    /**
     * 幂等查重：同类型、同标题（去空白）、同日期时间/全天状态的有效事项已存在时返回它。
     * AI 失败重试/网络超时重发时避免重复建同一条事项。
     */
    suspend fun findDuplicate(item: ItemEntity): ItemEntity? =
        dao.getAllActive().firstOrNull { existing ->
            existing.type == item.type &&
                existing.title.trim() == item.title.trim() &&
                existing.itemDate == item.itemDate &&
                existing.itemTime == item.itemTime &&
                existing.isAllDay == item.isAllDay
        }

    /**
     * Debug 测试专用：直接入库但不走正常调度（测试闹钟由
     * [ReminderScheduler.scheduleDebugAt] 在任意毫秒时刻单独注册）。
     */
    suspend fun insertForTest(item: ItemEntity): Long = dao.insert(item)

    /**
     * Debug 清理：删除所有标题以 [TEST_PREFIX] 开头的测试事项（含归档中的），
     * 同时取消其闹钟与通知。返回删除条数。
     */
    suspend fun deleteTestItems(): Int {
        val targets = dao.getAll().filter { it.title.startsWith(TEST_PREFIX) }
        targets.forEach {
            reminderScheduler.cancel(it.id)
            dao.deleteById(it.id)
        }
        return targets.size
    }

    suspend fun update(item: ItemEntity, actor: String = AuditActor.MANUAL) {
        val before = dao.getById(item.id)
        val toSave = item.copy(updatedAt = System.currentTimeMillis())
        // 先取消旧时刻（处理改期/关闭提醒/改全天），再按新值调度
        reminderScheduler.cancel(toSave.id)
        dao.update(toSave)
        reminderScheduler.schedule(toSave)
        audit(actor, AuditAction.UPDATE, item.id, item.title, before?.let { changeDetail(it, toSave) })
    }

    /** 标记完成 */
    suspend fun complete(id: Long, actor: String = AuditActor.MANUAL) {
        val before = dao.getById(id)
        val now = System.currentTimeMillis()
        dao.updateStatus(id, Status.DONE, now, now)
        reminderScheduler.cancel(id)
        audit(actor, AuditAction.COMPLETE, id, before?.title)
    }

    /** 标记取消 */
    suspend fun cancel(id: Long, actor: String = AuditActor.MANUAL) {
        val before = dao.getById(id)
        val now = System.currentTimeMillis()
        dao.updateStatus(id, Status.CANCELLED, now, now)
        reminderScheduler.cancel(id)
        audit(actor, AuditAction.CANCEL, id, before?.title)
    }

    /** 归档恢复 */
    suspend fun restore(id: Long, actor: String = AuditActor.MANUAL) {
        dao.restore(id, System.currentTimeMillis())
        val restored = dao.getById(id)
        restored?.let { reminderScheduler.schedule(it) }
        audit(actor, AuditAction.RESTORE, id, restored?.title)
    }

    suspend fun delete(id: Long, actor: String = AuditActor.MANUAL) {
        val before = dao.getById(id)
        reminderScheduler.cancel(id)
        dao.deleteById(id)
        audit(actor, AuditAction.DELETE, id, before?.title)
    }

    /** 清空全部事项：先按现有数据撤闹钟，再清表 */
    suspend fun clear(actor: String = AuditActor.MANUAL) {
        reminderScheduler.cancelAll()
        dao.clear()
        audit(actor, AuditAction.CLEAR, 0, "清空全部事项")
    }

    /**
     * 备份导入：按备份原 id 合并覆盖（同 id 替换，其余保留）。
     * 先撤全部闹钟再按导入后的数据全量重排，保证闹钟台账与库一致。
     * 旧版本备份可能缺 scope 字段（Gson 反序列化为空/非法值），统一兜底为私人。
     */
    suspend fun importItems(items: List<ItemEntity>) {
        reminderScheduler.cancelAll()
        val normalized = items.map { item ->
            if (item.scope == Scope.WORK) item else item.copy(scope = Scope.PERSONAL)
        }
        dao.importAll(normalized)
        reminderScheduler.rescheduleAll()
        audit(AuditActor.SYSTEM, AuditAction.IMPORT, 0, "备份导入事项 ${items.size} 条")
    }

    /** 提取修改前后的关键字段差异，供审计日志详情展示 */
    private fun changeDetail(before: ItemEntity, after: ItemEntity): String? {
        val parts = mutableListOf<String>()
        if (before.title != after.title) parts += "标题"
        if (before.itemDate != after.itemDate) {
            parts += "日期 ${before.itemDate ?: "无"}→${after.itemDate ?: "无"}"
        }
        if (before.itemTime != after.itemTime) {
            parts += "时间 ${before.itemTime ?: "无"}→${after.itemTime ?: "无"}"
        }
        if (before.endTime != after.endTime) parts += "结束时间"
        if (before.location != after.location) parts += "地点"
        if (before.attendees != after.attendees) parts += "参与人物"
        if (before.scope != after.scope) {
            parts += "范围 ${scopeLabel(before.scope)}→${scopeLabel(after.scope)}"
        }
        if (before.type != after.type) parts += "类型"
        if (before.priority != after.priority) parts += "优先级"
        if (before.recurrence != after.recurrence) parts += "重复规则"
        if (before.reminderEnabled != after.reminderEnabled ||
            before.remindMinutes != after.remindMinutes
        ) {
            parts += "提醒设置"
        }
        return parts.joinToString("；").ifBlank { null }
    }

    /** 审计日志中使用的范围中文标签 */
    private fun scopeLabel(scope: String): String =
        if (scope == Scope.WORK) "工作" else "私人"

    suspend fun count(): Int = dao.count()

    suspend fun activeCount(): Int = dao.activeCount()

    companion object {
        /** Debug 测试事项统一标题前缀，清理时按此识别 */
        const val TEST_PREFIX = "【测试】"

        /** 工具方法：毫秒时间戳 → yyyy-MM-dd */
        fun formatDate(date: Date = Date()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(date)
    }
}
