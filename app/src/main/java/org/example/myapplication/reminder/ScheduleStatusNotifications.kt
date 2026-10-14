package org.example.myapplication.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.example.myapplication.MainActivity
import org.example.myapplication.R
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.AppDatabase
import org.example.myapplication.data.local.db.ItemType
import org.example.myapplication.ui.screens.BoardEntry
import org.example.myapplication.ui.screens.FutureDay
import org.example.myapplication.ui.screens.ScheduleBoard
import org.example.myapplication.ui.screens.buildBoard
import org.example.myapplication.ui.screens.intervalLabel
import org.example.myapplication.ui.screens.monthDayOf
import org.example.myapplication.ui.screens.remainingText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 日程常驻通知：通知栏长期保留一条静默、不可侧滑清除的通知，
 * 内容复刻日程页顶部 Hero——进行中 / 下一项 / 今天结束 / 近两天无安排 / 今日无安排。
 *
 * 刷新时机：事项增删改（MainActivity 监听数据 Flow）、回前台、开机、
 * 提醒闹钟到点，以及本对象注册的"状态切换刷新闹钟"（事项开始/结束/跨零点，
 * 并带 30 分钟兜底——无切换时也至少每 30 分钟自动重算刷新一次）。
 * 纯常驻通知方案：不以前台服务保活，进程被杀后通知仍由系统保留，
 * 下一次刷新（setAlarmClock）到点会重新拉起进程更新内容。
 */
object ScheduleStatusNotifications {

    const val CHANNEL_ID = "schedule_status"
    private const val CHANNEL_NAME = "日程常驻"

    /** 固定通知 id：常驻通知全局唯一，刷新即同 id 覆盖 */
    private const val NOTIFICATION_ID = 2_000_000_020

    /** 兜底刷新间隔：即使没有状态切换，最多隔 30 分钟也重算并刷新一次 */
    private const val MAX_REFRESH_INTERVAL_MS = 30 * 60_000L

    /** 创建静默常驻渠道（O+ 必需；重复调用安全） */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "在通知栏常驻显示当前进行中或下一项日程"
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 按最新库数据刷新常驻通知，并安排下一次状态切换刷新闹钟。
     * 开关关闭时撤销通知与刷新闹钟。在 IO 协程中调用（需读库）。
     */
    suspend fun refresh(context: Context) {
        withContext(Dispatchers.Default) {
            val appContext = context.applicationContext
            val preferencesManager = PreferencesManager(appContext)
            if (!preferencesManager.statusNotificationEnabled) {
                hide(appContext)
                return@withContext
            }

            val db = AppDatabase.getInstance(appContext)
            val items = db.itemDao().getAllActive()
            val now = System.currentTimeMillis()
            val board = buildBoard(items, now)

            ensureChannel(appContext)
            notify(appContext, board, now)

            // 安排最近一个状态切换点（开始/结束/跨零点），到点后由刷新广播重新走本方法
            val scheduler = ReminderScheduler(appContext, preferencesManager, db.itemDao())
            scheduler.scheduleStatusRefresh(nextTransitionAt(board, now))
        }
    }

    /** 撤销常驻通知并取消刷新闹钟（开关关闭时调用） */
    fun hide(context: Context) {
        val appContext = context.applicationContext
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        val db = AppDatabase.getInstance(appContext)
        ReminderScheduler(appContext, PreferencesManager(appContext), db.itemDao())
            .cancelStatusRefresh()
    }

    // ============================== 通知内容 ==============================

    private fun notify(context: Context, board: ScheduleBoard, now: Long) {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ReminderNotifications.EXTRA_TAB, ReminderNotifications.TAB_SCHEDULE)
        }
        val pendingContent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snapshot = buildSnapshot(board, now)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(snapshot.title)
            .setContentText(snapshot.collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(snapshot.expanded))
            .setContentIntent(pendingContent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)          // 常驻：不可侧滑清除
            .setAutoCancel(false)
            .setShowWhen(false)        // 不显示时间戳，信息型通知
            .setSilent(true)           // 双保险：渠道已静默，覆盖刷新不发声
            .setOnlyAlertOnce(true)

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private data class Snapshot(val title: String, val collapsed: String, val expanded: String)

    /** 与日程页 NowHeroCard 同一套状态机，输出通知标题/折叠文案/展开文案 */
    private fun buildSnapshot(board: ScheduleBoard, now: Long): Snapshot {
        val inProgress = board.inProgress.firstOrNull()
        if (inProgress != null) {
            val item = inProgress.item
            val meta = listOfNotNull(
                intervalLabel(item),
                item.location?.takeIf { it.isNotBlank() }?.let { "📍 $it" }
            ).joinToString(" · ")
            val remain = inProgress.interval?.let { range ->
                val remainMin = ((range.last - now) / 60_000L).toInt()
                when {
                    remainMin <= 0 -> "即将结束"
                    remainMin < 60 -> "还剩 $remainMin 分钟"
                    else -> "还剩 ${remainMin / 60} 小时 ${remainMin % 60} 分"
                }
            }
            val lines = buildList {
                add("进行中 · $meta")
                remain?.let { add(it) }
                if (board.inProgress.size > 1) add("共 ${board.inProgress.size} 项进行中")
            }
            return Snapshot(item.title, lines.first(), lines.joinToString("\n"))
        }

        val next = board.todayUpcoming.firstOrNull()
        if (next != null) {
            val item = next.item
            val timePart = if (item.type == ItemType.TODO) {
                "截止 ${item.itemTime ?: "今天内"}"
            } else {
                item.itemTime ?: "随时"
            }
            val meta = listOfNotNull(
                timePart,
                item.location?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            val lines = buildList {
                add("下一项 · $meta")
                add(remainingText(next.startMillis - now))
                val otherTodoCount = board.todayUpcoming.count {
                    it.item.type == ItemType.TODO && it.item.id != item.id
                }
                if (otherTodoCount > 0) add("另有 $otherTodoCount 件待办今天截止")
            }
            return Snapshot(item.title, lines.first(), lines.joinToString("\n"))
        }

        val tomorrowFirst = board.tomorrow.firstOrNull()
        if (tomorrowFirst != null) {
            val item = tomorrowFirst.item
            val text = "明天 ${item.itemTime ?: "随时"} · ${item.title}"
            return Snapshot("今天的安排都结束了", text, text)
        }

        val futureFirst: BoardEntry? = board.futureDays.firstOrNull()?.entries?.firstOrNull()
        if (futureFirst != null || board.undated.isNotEmpty()) {
            val lines = buildList {
                futureFirst?.let {
                    add("${monthDayOf(it.date)} ${it.item.itemTime ?: ""} · ${it.item.title}".trim())
                }
                if (board.undated.isNotEmpty()) {
                    add("没有期限：${board.undated.take(2).joinToString("、") { it.title }}")
                }
            }
            return Snapshot("近两天没有固定安排", lines.first(), lines.joinToString("\n"))
        }

        return Snapshot(
            title = "今日无安排",
            collapsed = "今天没有固定日程，点击安排",
            expanded = "今天没有固定日程\n点击打开日程页，安排今天要做的事"
        )
    }

    /**
     * 下一次需要重算 Hero 状态的时刻：
     * - 进行中事项的最早结束时刻（进行中 → 空档/下一项）
     * - 今天下一项的开始时刻（空档 → 进行中）
     * - 明天零点（跨天分组重算，空状态也保证次日刷新）
     * - 明天/未来第一条的开始时刻（零点刷新后也会重排，列入更稳妥）
     */
    private fun nextTransitionAt(board: ScheduleBoard, now: Long): Long {
        val candidates = mutableListOf<Long>()
        board.inProgress.mapNotNullTo(candidates) { it.interval?.last }
        board.todayUpcoming.firstOrNull()?.let { candidates += it.startMillis }
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        candidates += midnight
        board.tomorrow.firstOrNull()?.let { candidates += it.startMillis }
        board.futureDays.firstOrNull()?.let { day: FutureDay ->
            day.entries.firstOrNull()?.let { candidates += it.startMillis }
        }
        // 预留 1 秒缓冲，避免边界相等导致重复刷新同一状态
        val nextTransition = candidates.filter { it > now + 1_000L }.minOrNull() ?: midnight
        // 与 30 分钟兜底时刻取较早者：无状态切换时也保证每 30 分钟刷新一次
        return minOf(nextTransition, now + MAX_REFRESH_INTERVAL_MS)
    }
}
