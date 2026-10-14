package org.example.myapplication.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.example.myapplication.MainActivity
import org.example.myapplication.R
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.local.db.ItemType

/**
 * 事项通知：渠道创建、通知构建与展示
 *
 * 单一通知渠道「事项提醒」（IMPORTANCE_HIGH，跟随系统铃声与震动）。
 * 每条事项的通知 id 固定为 item.id，延后提醒复用同一通知（后发覆盖先发）。
 */
object ReminderNotifications {

    const val CHANNEL_ID = "item_reminders"
    private const val CHANNEL_NAME = "事项提醒"

    /** Debug 测试通知使用的虚拟通知/事项 id（不写库），需在 Int 正数范围内 */
    private const val DEBUG_NOTIFICATION_ID = 2_000_000_001

    /** 通知点击跳转的目标底部 Tab：1 = 日程页 */
    const val EXTRA_TAB = "extra_open_tab"
    const val TAB_SCHEDULE = 1

    /** 创建通知渠道（O+ 必需；重复调用安全） */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "事项到达设定时间时提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
        }
        manager.createNotificationChannel(channel)
    }

    /** 「事项提醒」渠道是否已存在（O 以下恒 true；Debug 自检用） */
    fun channelExists(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(CHANNEL_ID) != null
    }

    /**
     * 立即发送一条测试通知（Debug 页用），验证渠道展示与两个动作按钮。
     * 使用不写库的虚拟事项 id，点「标记完成」时接收端仅撤销通知。
     */
    fun showTestNotification(context: Context, scheduler: ReminderScheduler) {
        ensureChannel(context)
        val now = java.util.Calendar.getInstance()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(now.time)
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            .format(now.time)
        val item = ItemEntity(
            id = DEBUG_NOTIFICATION_ID.toLong(),
            type = ItemType.SCHEDULE,
            title = "测试通知",
            itemDate = date,
            itemTime = time
        )
        showReminder(context, scheduler, item, isSnooze = false)
    }

    /**
     * 展示一条事项提醒通知。调用方需自行确保已获得 POST_NOTIFICATIONS 权限，
     * 未授权时 NotificationManager.notify 会被系统静默丢弃，不抛异常。
     */
    fun showReminder(
        context: Context,
        scheduler: ReminderScheduler,
        item: ItemEntity,
        isSnooze: Boolean
    ) {
        ensureChannel(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TAB, TAB_SCHEDULE)
        }
        val pendingContent = PendingIntent.getActivity(
            context,
            item.id.toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = buildString {
            append("【${typeTag(item.type)}】")
            append(formatDateTime(item.itemDate, item.itemTime))
            item.location?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            if (isSnooze) insert(0, "（延后提醒）")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(item.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingContent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_notification_reminder,
                "标记完成",
                scheduler.doneActionPendingIntent(item.id)
            )
            .addAction(
                R.drawable.ic_notification_reminder,
                "延后10分钟",
                scheduler.snoozeActionPendingIntent(item.id)
            )

        NotificationManagerCompat.from(context).notify(item.id.toInt(), builder.build())
    }

    private fun typeTag(type: String): String = when (type) {
        ItemType.SCHEDULE -> "日程"
        ItemType.TODO -> "待办"
        ItemType.PLAN -> "计划"
        ItemType.WISH -> "心愿"
        else -> "事项"
    }

    /** "yyyy-MM-dd" + "HH:mm" → "9月7日 15:00"；解析失败时回退原始拼接 */
    private fun formatDateTime(date: String?, time: String?): String {
        if (date.isNullOrBlank()) return time.orEmpty()
        val parts = date.split("-")
        val text = if (parts.size == 3) {
            runCatching { "${parts[1].toInt()}月${parts[2].toInt()}日" }.getOrDefault(date)
        } else {
            date
        }
        return if (!time.isNullOrBlank()) "$text $time" else text
    }
}

/**
 * AI 聊天回复通知：独立静默渠道「AI 回复」（IMPORTANCE_LOW，无声无震动）。
 *
 * 用途（均只在聊天页不可见时由 ChatViewModel 触发）：
 * - AI 文字回复到达：通知栏展示最新一条（固定 id，新回复覆盖旧回复），
 *   系统/厂商启动器据此自动显示 App 图标角标；
 * - AI 返回待确认事项：提示用户回来决策；
 * - 请求失败：提示回到聊天页点击重试。
 * 点击通知统一打开助理 Tab；进入聊天页 ON_RESUME 时通知被撤销。
 */
object AiReplyNotifications {

    const val CHANNEL_ID = "ai_replies"
    private const val CHANNEL_NAME = "AI 回复"

    /** 固定通知 id：始终只保留一条 AI 聊天通知（最新一条覆盖旧的） */
    private const val NOTIFICATION_ID = 2_000_000_010

    /** 通知点击跳转的目标底部 Tab：0 = 助理页 */
    private const val TAB_CHAT = 0

    /** 创建静默通知渠道（O+ 必需；重复调用安全） */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AI 回复完成后的静默通知（不响铃）"
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** AI 文字回复完成：标题固定，内容为回复全文（展开可见），折叠态显示摘要 */
    fun showReply(context: Context, text: String) {
        val body = text.trim()
        notify(
            context = context,
            title = "AI 助理",
            text = body,
            collapsedText = body.replace("\n", " ").take(120)
        )
    }

    /** AI 返回了待确认事项，需要用户回到聊天页决策 */
    fun showConfirmRequest(context: Context, actionCount: Int) {
        notify(
            context = context,
            title = "AI 需要你的确认",
            text = "有 $actionCount 个事项等待确认，点击查看"
        )
    }

    /** 请求失败：点击进入聊天页，通过错误气泡重试 */
    fun showFailure(context: Context) {
        notify(
            context = context,
            title = "AI 回复失败",
            text = "点击进入聊天页重新发送"
        )
    }

    /** 进入聊天页（已读）时撤销通知，角标随之清除 */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notify(
        context: Context,
        title: String,
        text: String,
        collapsedText: String = text
    ) {
        ensureChannel(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ReminderNotifications.EXTRA_TAB, TAB_CHAT)
        }
        val pendingContent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(title)
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingContent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            // 同 id 通知被新回复覆盖时不再次提示（渠道本身静默，双保险）
            .setOnlyAlertOnce(true)

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }
}

/**
 * 精确闹钟未授权时的引导对话框
 * 仅在用户启用提醒的三个时机（手动表单 / AI 落库 / 总开关）弹出
 */
@Composable
fun ExactAlarmGuideDialog(scheduler: ReminderScheduler, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许精确提醒") },
        text = {
            Text(
                "系统未授予精确闹钟权限，提醒在手机休眠时可能延迟数分钟。\n\n" +
                    "建议在系统设置中允许本应用「闹钟和提醒」，授权后提醒将准时触发。"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching { context.startActivity(scheduler.exactAlarmSettingsIntent()) }
            }) { Text("去授权") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        }
    )
}
