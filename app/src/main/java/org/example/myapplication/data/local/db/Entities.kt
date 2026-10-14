package org.example.myapplication.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 统一事项表：日程 / 待办 / 计划 / 心愿
 *
 * - [ItemEntity.type]      区分四种事项，取值见 [ItemType]
 * - [ItemEntity.itemDate]  日程为发生日期，待办为截止日期；心愿可空。格式 yyyy-MM-dd
 * - [ItemEntity.itemTime]  日程开始时间或待办截止时间，可空。格式 HH:mm
 * - [ItemEntity.endTime]   日程结束时间，可空。格式 HH:mm；早于开始时间时按次日结束解释
 * - [ItemEntity.attendees] 参与人物自由文本（如"王总、小李"），可空
 * - [ItemEntity.scope]     事务范围 WORK / PERSONAL，见 [Scope]
 * - [ItemEntity.status]    ACTIVE / DONE / CANCELLED，后两者进入归档
 * - [ItemEntity.source]    数据来源 MANUAL / AI_TEXT / AI_IMAGE
 * - [ItemEntity.sourceImage] 图片识别事项的来源留档（已压缩 JPEG），手动事项为空
 */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val description: String? = null,
    val itemDate: String? = null,
    val itemTime: String? = null,
    val endTime: String? = null,
    val isAllDay: Boolean = false,
    val location: String? = null,
    val attendees: String? = null,
    val scope: String = Scope.PERSONAL,
    val priority: Int = Priority.NORMAL,
    val recurrence: String = Recurrence.NONE,
    val reminderEnabled: Boolean = false,
    val remindMinutes: Int? = null,
    val status: String = Status.ACTIVE,
    val source: String = Source.MANUAL,
    val sourceImage: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
    // 必须保留 data class 全字段结构相等（含 sourceImage 的内容比较）：
    // Room Flow 发射的新列表经 collectAsState 写入 State 时按 equals 判变，
    // 若只按 id 比较，改期/改标题后新旧列表被判为相等，日程页不会实时重组。
)

/** 单一会话的聊天消息表；错误/加载态气泡不入库 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,          // user / assistant
    val text: String,
    val image: ByteArray? = null,  // 用户发送的压缩 JPEG；文字消息为空
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean = other is ChatMessageEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * 长期记忆表：用户画像事实 / 人脉 / 纪念日 / 自由笔记
 * [MemoryEntity.memoryDate] 仅纪念日使用（MM-dd 或 yyyy-MM-dd），其余为空
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,      // PROFILE / CONTACT / ANNIVERSARY / NOTE
    val title: String? = null,
    val content: String,
    val memoryDate: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 事项操作审计日志（P0 前置）：记录谁对哪条事项做了什么。
 * - [AuditLogEntity.actor]      操作来源 MANUAL（手动）/ AI（助理工具）/ SYSTEM
 * - [AuditLogEntity.action]     动作类型，见 [AuditAction]
 * - [AuditLogEntity.targetType] 操作对象类型，见 [AuditTarget]
 * - [AuditLogEntity.detail]     变更摘要（如改期前后值），可空
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actor: String,
    val action: String,
    val targetType: String = AuditTarget.ITEM,
    val targetId: Long? = null,
    val summary: String? = null,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** 事项类型 */
object ItemType {
    const val SCHEDULE = "SCHEDULE" // 日程：有日期+开始时间
    const val TODO = "TODO"         // 待办：有截止时间
    const val PLAN = "PLAN"         // 计划：中长期
    const val WISH = "WISH"         // 心愿：想做但无明确时间
}

/** 事务范围：工作 / 私人 */
object Scope {
    const val WORK = "WORK"
    const val PERSONAL = "PERSONAL"
}

/** 事项状态 */
object Status {
    const val ACTIVE = "ACTIVE"
    const val DONE = "DONE"
    const val CANCELLED = "CANCELLED"
}

/** 优先级 */
object Priority {
    const val LOW = 0
    const val NORMAL = 1
    const val HIGH = 2
}

/** 简单重复预设 */
object Recurrence {
    const val NONE = "NONE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
    const val YEARLY = "YEARLY"
}

/** 数据来源 */
object Source {
    const val MANUAL = "MANUAL"
    const val AI_TEXT = "AI_TEXT"
    const val AI_IMAGE = "AI_IMAGE"
}

/** 长期记忆分类 */
object MemoryCategory {
    const val PROFILE = "PROFILE"
    const val CONTACT = "CONTACT"
    const val ANNIVERSARY = "ANNIVERSARY"
    const val NOTE = "NOTE"
}

/** 聊天角色 */
object ChatRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/** 审计日志操作来源 */
object AuditActor {
    const val MANUAL = "MANUAL" // 用户在界面手动操作
    const val AI = "AI"         // AI 通过 Function Calling 落库
    const val SYSTEM = "SYSTEM" // 备份导入等系统批量操作
}

/** 审计日志动作类型 */
object AuditAction {
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val COMPLETE = "COMPLETE"
    const val CANCEL = "CANCEL"
    const val DELETE = "DELETE"
    const val RESTORE = "RESTORE"
    const val CLEAR = "CLEAR"
    const val IMPORT = "IMPORT"
}

/** 审计日志操作对象类型 */
object AuditTarget {
    const val ITEM = "ITEM"
}
