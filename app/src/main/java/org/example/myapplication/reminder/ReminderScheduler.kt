package org.example.myapplication.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.ItemDao
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.local.db.Recurrence
import org.example.myapplication.data.local.db.Status
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 事项提醒调度器
 *
 * 职责：
 * - 资格判定：仅 ACTIVE、开启提醒、非全天、有合法日期+具体时间的事项可调度
 * - 触发时刻：事项时间 − 提前提醒分钟数；重复事项始终从【原始锚点】推算未来第一次，
 *   避免按月累加导致月底日期漂移（1/31 → 2/28 → 3/28）
 * - 闹钟实现：统一用 setAlarmClock（系统级闹钟），Doze 与国产 ROM 省电/冻结策略下
 *   仍准时触发，不依赖精确闹钟权限
 * - 统一管理 4 类 PendingIntent：正常闹钟 / 延后闹钟 / 通知「完成」按钮 / 通知「延后」按钮
 *
 * 任何注册/取消都对系统服务是毫秒级操作，所以调度方法本身为阻塞函数；
 * 只有需要读库的 rescheduleAll/cancelAll 为 suspend。
 */
class ReminderScheduler(
    context: Context,
    private val preferencesManager: PreferencesManager,
    private val itemDao: ItemDao
) {
    private val appContext = context.applicationContext
    private val alarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
        isLenient = false
    }

    // ============================== 资格与时刻 ==============================

    /** 事项是否具备被调度提醒的资格（不含总开关） */
    fun isEligible(item: ItemEntity): Boolean =
        item.status == Status.ACTIVE &&
            item.reminderEnabled &&
            !item.isAllDay &&
            !item.itemDate.isNullOrBlank() &&
            !item.itemTime.isNullOrBlank() &&
            anchorTriggerAt(item, System.currentTimeMillis()) != null

    /**
     * 事项是否仍是有效的提醒载体（不要求触发时刻在未来）。
     * 提醒响过之后原始时刻已过点，但用户仍可能点「延后10分钟」——
     * 此校验用于通知延后链路，区别于 [isEligible]。
     */
    fun isReminderItem(item: ItemEntity): Boolean {
        if (item.status != Status.ACTIVE || !item.reminderEnabled || item.isAllDay) return false
        val date = item.itemDate?.takeIf { it.isNotBlank() } ?: return false
        val time = item.itemTime?.takeIf { it.isNotBlank() } ?: return false
        return runCatching { dateTimeFmt.parse("$date $time") != null }.getOrDefault(false)
    }

    /**
     * 事项的提醒触发时刻（毫秒）。
     * - 单次事项：早等于 now 返回 null（静默跳过）
     * - 重复事项：从原始日期时间锚点按周期推算未来第一次
     * 不具备资格 / 时间格式非法 / 超远未来（约 10 年 3650 次）同样返回 null
     */
    fun anchorTriggerAt(item: ItemEntity, from: Long): Long? =
        futureTriggers(item, 1, from).firstOrNull()

    /**
     * 从 [from] 起未来的前 [count] 个触发时刻（Debug 推算验证用）。
     * 单次事项最多返回 1 个；重复事项从【原始锚点】推算，月底不漂移。
     */
    fun futureTriggers(item: ItemEntity, count: Int, from: Long = System.currentTimeMillis()): List<Long> {
        if (count <= 0) return emptyList()
        val date = item.itemDate?.takeIf { it.isNotBlank() } ?: return emptyList()
        val time = item.itemTime?.takeIf { it.isNotBlank() } ?: return emptyList()
        val anchor = try {
            dateTimeFmt.parse("$date $time")?.time ?: return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "事项时间格式非法，跳过推算：id=${item.id} date=$date time=$time")
            return emptyList()
        }
        val leadMinutes = (item.remindMinutes ?: 0).coerceAtLeast(0).toLong()

        if (item.recurrence == Recurrence.NONE) {
            return listOf(anchor - leadMinutes * MILLIS_PER_MINUTE).filter { it > from }
        }

        val (field, amount) = when (item.recurrence) {
            Recurrence.DAILY -> Calendar.DAY_OF_MONTH to 1
            Recurrence.WEEKLY -> Calendar.WEEK_OF_YEAR to 1
            Recurrence.MONTHLY -> Calendar.MONTH to 1
            Recurrence.YEARLY -> Calendar.YEAR to 1
            else -> return emptyList()
        }
        val base = Calendar.getInstance().apply { timeInMillis = anchor }
        val result = mutableListOf<Long>()
        repeat(MAX_OCCURRENCES) { n ->
            val candidate = (base.clone() as Calendar).apply { add(field, amount * n) }
            val trigger = candidate.timeInMillis - leadMinutes * MILLIS_PER_MINUTE
            if (trigger > from) {
                result.add(trigger)
                if (result.size >= count) return result
            }
        }
        Log.w(TAG, "重复事项超出推算上限：id=${item.id}")
        return result
    }

    // ============================== 注册 / 取消 ==============================

    /** 调度单个事项（总开关关闭时忽略；单次过期静默跳过） */
    fun schedule(item: ItemEntity) {
        if (!preferencesManager.reminderEnabled) return
        if (!item.reminderEnabled || item.isAllDay) return
        val triggerAt = anchorTriggerAt(item, System.currentTimeMillis()) ?: return
        val pi = PendingIntent.getBroadcast(
            appContext,
            normalRequestCode(item.id),
            buildAlarmIntent(ACTION_REMIND, item.id, triggerAt, isSnooze = false),
            PENDING_INTENT_FLAGS
        )
        setAlarm(triggerAt, pi)
        Log.d(TAG, "已调度提醒 id=${item.id} at=$triggerAt recurring=${item.recurrence}")
    }

    /** 通知「延后10分钟」：在指定时刻再响一次（用户显式操作，不受总开关限制） */
    fun scheduleSnooze(itemId: Long, triggerAt: Long) {
        val pi = PendingIntent.getBroadcast(
            appContext,
            snoozeRequestCode(itemId),
            buildAlarmIntent(ACTION_SNOOZE_ALARM, itemId, triggerAt, isSnooze = true),
            PENDING_INTENT_FLAGS
        )
        setAlarm(triggerAt, pi)
        Log.d(TAG, "已调度延后提醒 id=$itemId at=$triggerAt")
    }

    /**
     * 仅 Debug 页使用：在指定的任意毫秒时刻触发一条测试通知。
     * 绕过 HH:mm 整点限制与总开关，接收端不做"广播触发时刻一致性"校验；
     * 正式业务流程绝不调用。
     */
    fun scheduleDebugAt(itemId: Long, triggerAt: Long) {
        val pi = PendingIntent.getBroadcast(
            appContext,
            debugRequestCode(itemId),
            buildAlarmIntent(ACTION_DEBUG_REMIND, itemId, triggerAt, isSnooze = false),
            PENDING_INTENT_FLAGS
        )
        setAlarm(triggerAt, pi)
        Log.d(TAG, "已调度调试闹钟 id=$itemId at=$triggerAt")
    }

    /** 取消事项的全部闹钟（正常+延后）、通知按钮 PendingIntent 与在用通知 */
    fun cancel(itemId: Long) {
        val am = alarmManager
        am.cancel(
            PendingIntent.getBroadcast(
                appContext, normalRequestCode(itemId),
                Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_REMIND),
                PENDING_INTENT_FLAGS
            )
        )
        am.cancel(
            PendingIntent.getBroadcast(
                appContext, snoozeRequestCode(itemId),
                Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_SNOOZE_ALARM),
                PENDING_INTENT_FLAGS
            )
        )
        am.cancel(
            PendingIntent.getBroadcast(
                appContext, doneRequestCode(itemId),
                Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_DONE),
                PENDING_INTENT_FLAGS
            )
        )
        am.cancel(
            PendingIntent.getBroadcast(
                appContext, snoozeActionRequestCode(itemId),
                Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_SNOOZE_10),
                PENDING_INTENT_FLAGS
            )
        )
        am.cancel(
            PendingIntent.getBroadcast(
                appContext, debugRequestCode(itemId),
                Intent(appContext, ReminderReceiver::class.java).setAction(ACTION_DEBUG_REMIND),
                PENDING_INTENT_FLAGS
            )
        )
        NotificationManagerCompat.from(appContext).cancel(itemId.toInt())
    }

    /** 当前库中具备提醒资格的事项数（Debug 自检用） */
    suspend fun needingReminderCount(): Int = itemDao.getNeedingReminder().size

    /** 全量重调度：开机/时间变更/回前台同步/开关重新开启时使用 */
    suspend fun rescheduleAll() {
        val items = itemDao.getNeedingReminder()
        items.forEach { schedule(it) }
        Log.d(TAG, "全量重调度完成，共 ${items.size} 条")
    }

    /** 总开关关闭：取消所有具备提醒资格事项的闹钟并撤销其通知 */
    suspend fun cancelAll() {
        itemDao.getNeedingReminder().forEach { cancel(it.id) }
    }

    /**
     * 日程常驻通知的状态刷新闹钟：到点由 [ReminderReceiver] 重新计算 Hero 状态并更新通知。
     * 同样走 setAlarmClock，息屏/国产 ROM 冻结下也能准时切换"下一项→进行中→结束"。
     */
    fun scheduleStatusRefresh(triggerAt: Long) {
        val pi = PendingIntent.getBroadcast(
            appContext,
            STATUS_REFRESH_REQUEST_CODE,
            Intent(appContext, ReminderReceiver::class.java)
                .setAction(ACTION_STATUS_REFRESH),
            PENDING_INTENT_FLAGS
        )
        setAlarm(triggerAt, pi)
        Log.d(TAG, "已调度常驻通知刷新 at=$triggerAt")
    }

    /** 撤销常驻通知的状态刷新闹钟（关闭常驻通知时调用） */
    fun cancelStatusRefresh() {
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                appContext,
                STATUS_REFRESH_REQUEST_CODE,
                Intent(appContext, ReminderReceiver::class.java)
                    .setAction(ACTION_STATUS_REFRESH),
                PENDING_INTENT_FLAGS
            )
        )
    }

    /**
     * 注册底层闹钟：统一使用 [AlarmManager.setAlarmClock]。
     *
     * 为什么不用 setExactAndAllowWhileIdle：
     * - 后者在 Doze 下有"每 App 每分钟一条"的限流，且在 OPPO/小米等国产 ROM 的
     *   应用冻结/智能省电策略下，息屏后台时会被推迟到亮屏或回前台才投递
     *   （表现为：后台到点收不到，打开 App 才补弹通知）；
     * - setAlarmClock 是系统级闹钟，Doze 深度休眠也准时触发、无限流，
     *   厂商省电策略默认按"闹钟"放行，到点会唤醒并拉起进程投递广播；
     * - API 21+ 恒为精确闹钟，不依赖 SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM 授权。
     * 代价：部分设备状态栏可能显示小闹钟图标，提醒类应用普遍采用此方案。
     */
    private fun setAlarm(triggerAt: Long, pi: PendingIntent) {
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), pi)
    }

    // ============================== PendingIntent 工厂 ==============================

    private fun buildAlarmIntent(
        action: String,
        itemId: Long,
        triggerAt: Long,
        isSnooze: Boolean
    ): Intent = Intent(appContext, ReminderReceiver::class.java)
        .setAction(action)
        .putExtra(EXTRA_ITEM_ID, itemId)
        .putExtra(EXTRA_TRIGGER_AT, triggerAt)
        .putExtra(EXTRA_IS_SNOOZE, isSnooze)

    /** 通知「标记完成」按钮的广播 Intent */
    fun doneActionIntent(itemId: Long): Intent =
        Intent(appContext, ReminderReceiver::class.java)
            .setAction(ACTION_DONE)
            .putExtra(EXTRA_ITEM_ID, itemId)

    /** 通知「延后10分钟」按钮的广播 Intent */
    fun snoozeActionIntent(itemId: Long): Intent =
        Intent(appContext, ReminderReceiver::class.java)
            .setAction(ACTION_SNOOZE_10)
            .putExtra(EXTRA_ITEM_ID, itemId)

    /** 通知「标记完成」按钮 PendingIntent */
    fun doneActionPendingIntent(itemId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            appContext, doneRequestCode(itemId), doneActionIntent(itemId), PENDING_INTENT_FLAGS
        )

    /** 通知「延后10分钟」按钮 PendingIntent */
    fun snoozeActionPendingIntent(itemId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            snoozeActionRequestCode(itemId),
            snoozeActionIntent(itemId),
            PENDING_INTENT_FLAGS
        )

    // ============================== 权限与系统设置 ==============================

    /** Android 12+ 是否可以使用精确闹钟 */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /** Android 13+ 通知运行时权限（低版本恒 true；O+ 渠道被关也会反映为 false） */
    fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    /** 精确闹钟授权页（per-app 请求）；不支持时回退应用详情页 */
    fun exactAlarmSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${appContext.packageName}"))
                .takeIf { it.resolveActivity(appContext.packageManager) != null }
                ?: appDetailsIntent()
        } else {
            appDetailsIntent()
        }

    /** 本应用系统通知设置页；O 以下回退应用详情页 */
    fun notificationSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                .takeIf { it.resolveActivity(appContext.packageManager) != null }
                ?: appDetailsIntent()
        } else {
            appDetailsIntent()
        }

    /** 是否已加入电池优化白名单 */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(appContext.packageName) ?: true
    }

    /**
     * 申请忽略电池优化的直接弹窗 Intent；部分 ROM 禁止该 action，
     * 调用方 startActivity 失败时应改用 [batteryOptimizationListIntent]
     */
    fun requestIgnoreBatteryOptimizationsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${appContext.packageName}"))

    /** 电池优化设置列表（兜底入口） */
    fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 系统应用详情页：国产 ROM 耗电管理/后台活动/自启动设置的兜底入口 */
    fun appDetailsSettingsIntent(): Intent = appDetailsIntent()

    /**
     * 国产 ROM「自启动 / 关联启动」管理页 Intent；无法识别机型或目标组件不存在时返回 null，
     * 调用方应回退 [appDetailsSettingsIntent]。
     *
     * 背景：OPPO ColorOS 等 ROM 默认禁止第三方应用自启动，应用被划掉或重启后
     * 收不到开机广播、后台闹钟会被冻结；在该页面把本应用加白名单是提醒准时的前提。
     * 组件名随 ROM 版本变化，故按厂商列出多个候选逐个探测。
     */
    fun vendorAutoStartIntent(): Intent? {
        val candidates = when (Build.MANUFACTURER?.uppercase(Locale.US)) {
            "OPPO", "ONEPLUS", "REALME" -> listOf(
                "com.coloros.safecenter" to
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter" to
                    "com.coloros.safecenter.permission.startupapp.StartupAppListActivity",
                "com.oppo.safe" to
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
            "XIAOMI", "REDMI", "POCO" -> listOf(
                "com.miui.securitycenter" to
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "VIVO", "IQOO" -> listOf(
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
            "HUAWEI", "HONOR" -> listOf(
                "com.huawei.systemmanager" to
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            else -> emptyList()
        }
        return candidates.firstNotNullOfOrNull { (pkg, cls) ->
            Intent()
                .setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .takeIf { it.resolveActivity(appContext.packageManager) != null }
        }
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${appContext.packageName}"))

    private fun normalRequestCode(itemId: Long): Int = itemId.toInt()
    private fun snoozeRequestCode(itemId: Long): Int = (itemId xor MASK_SNOOZE_ALARM).toInt()
    private fun doneRequestCode(itemId: Long): Int = (itemId xor MASK_DONE).toInt()
    private fun snoozeActionRequestCode(itemId: Long): Int =
        (itemId xor MASK_SNOOZE_ACTION).toInt()
    private fun debugRequestCode(itemId: Long): Int =
        (itemId xor MASK_DEBUG).toInt()

    companion object {
        private const val TAG = "ReminderScheduler"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_OCCURRENCES = 3650

        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_TRIGGER_AT = "extra_trigger_at"
        const val EXTRA_IS_SNOOZE = "extra_is_snooze"

        const val ACTION_REMIND = "org.example.myapplication.action.REMIND"
        const val ACTION_SNOOZE_ALARM = "org.example.myapplication.action.SNOOZE_ALARM"
        const val ACTION_DONE = "org.example.myapplication.action.DONE"
        const val ACTION_SNOOZE_10 = "org.example.myapplication.action.SNOOZE_10"
        const val ACTION_DEBUG_REMIND = "org.example.myapplication.action.DEBUG_REMIND"
        const val ACTION_STATUS_REFRESH = "org.example.myapplication.action.STATUS_REFRESH"

        // 常驻通知刷新闹钟使用固定 requestCode（每次覆盖上一条，全局只保留一条）
        private const val STATUS_REFRESH_REQUEST_CODE = 0x07FFF_0001

        // 同一事项的 5 类 PendingIntent 用高位掩码区分，避免 requestCode 冲突
        private const val MASK_SNOOZE_ALARM = 0x4000_0000L
        private const val MASK_DONE = 0x2000_0000L
        private const val MASK_SNOOZE_ACTION = 0x1000_0000L
        private const val MASK_DEBUG = 0x0800_0000L

        private val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** 通知延后时长：10 分钟 */
        const val SNOOZE_MINUTES = 10
    }
}
