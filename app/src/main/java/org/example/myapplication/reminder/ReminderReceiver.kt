package org.example.myapplication.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.AppDatabase
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.repository.ItemRepository

/**
 * 提醒闹钟与通知按钮的统一接收者
 *
 * 处理四类动作：
 * - ACTION_REMIND      正常闹钟：校验事项仍有效 → 发通知 → 重复事项排下一次
 * - ACTION_SNOOZE_ALARM 延后闹钟：再次发通知（复用同一通知 id）
 * - ACTION_DONE        通知「标记完成」按钮：事项归档（仓库挂钩会自动取消闹钟与通知）
 * - ACTION_SNOOZE_10   通知「延后10分钟」按钮：10 分钟后再响，并撤掉当前通知
 *
 * 防误响：正常/延后广播到达时会读库重新校验资格；正常广播还会比对广播携带的
 * 触发时刻与库内当前推算值，事项被改期后旧广播直接丢弃。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val itemId = intent.getLongExtra(ReminderScheduler.EXTRA_ITEM_ID, -1L)
        if (itemId <= 0) {
            Log.w(TAG, "收到无效广播，缺少 itemId：$intent")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                handle(appContext, intent.action ?: return@launch, itemId, intent)
            } catch (e: Exception) {
                Log.e(TAG, "处理提醒广播失败 itemId=$itemId action=${intent.action}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(
        appContext: Context,
        action: String,
        itemId: Long,
        intent: Intent
    ) {
        val db = AppDatabase.getInstance(appContext)
        val preferencesManager = PreferencesManager(appContext)
        val scheduler = ReminderScheduler(appContext, preferencesManager, db.itemDao())
        val itemRepository = ItemRepository(db.itemDao(), scheduler, db.auditDao())

        when (action) {
            ReminderScheduler.ACTION_STATUS_REFRESH -> {
                // 常驻通知状态切换闹钟：重算 Hero 状态并安排下一次刷新
                ScheduleStatusNotifications.refresh(appContext)
            }

            ReminderScheduler.ACTION_DONE -> {
                val item = db.itemDao().getById(itemId)
                if (item == null) {
                    // 测试通知等虚拟 id：事项不存在，仅撤销残留通知
                    NotificationManagerCompat.from(appContext).cancel(itemId.toInt())
                    ReminderEventLog.record(appContext, "标记完成：事项不存在，已撤销通知 id=$itemId")
                } else {
                    itemRepository.complete(itemId)
                    ReminderEventLog.record(
                        appContext, "通知按钮·标记完成 id=$itemId「${item.title}」"
                    )
                    Log.d(TAG, "通过通知按钮完成事项 id=$itemId")
                }
            }

            ReminderScheduler.ACTION_SNOOZE_10 -> {
                val item = db.itemDao().getById(itemId)
                // 用 isReminderItem 而非 isEligible：提醒已响过、原时刻已过点时仍应允许延后
                if (item != null && scheduler.isReminderItem(item)) {
                    val at = System.currentTimeMillis() +
                        ReminderScheduler.SNOOZE_MINUTES * 60_000L
                    scheduler.scheduleSnooze(itemId, at)
                    // 当前通知先撤下，10 分钟后再次弹出
                    NotificationManagerCompat.from(appContext).cancel(itemId.toInt())
                    ReminderEventLog.record(
                        appContext, "通知按钮·延后10分钟 id=$itemId「${item.title}」"
                    )
                } else {
                    ReminderEventLog.record(
                        appContext, "延后失败：事项不存在或已失去提醒资格 id=$itemId"
                    )
                }
            }

            ReminderScheduler.ACTION_DEBUG_REMIND -> {
                // 调试闹钟：不校验触发时刻一致性、不排下一次，直接弹通知用于真机验证
                val item = db.itemDao().getById(itemId)
                if (item == null) {
                    ReminderEventLog.record(appContext, "调试闹钟触发但测试事项已不存在 id=$itemId")
                    return
                }
                ReminderNotifications.ensureChannel(appContext)
                ReminderNotifications.showReminder(appContext, scheduler, item, isSnooze = false)
                ReminderEventLog.record(
                    appContext, "调试闹钟触发 id=$itemId「${item.title}」"
                )
            }

            ReminderScheduler.ACTION_REMIND,
            ReminderScheduler.ACTION_SNOOZE_ALARM -> {
                val item = db.itemDao().getById(itemId)
                val isSnooze = intent.getBooleanExtra(
                    ReminderScheduler.EXTRA_IS_SNOOZE, false
                )
                // 正常/延后广播统一只要求事项仍是有效提醒载体（ACTIVE+提醒开+非全天+时间合法）。
                // 不能用 isEligible：它要求存在未来触发点，而广播到点时单次事项的触发时刻已过，
                // 会被误判失效导致永不弹通知；改期旧广播由下面的触发时刻一致性比对拦截。
                val validItem: ItemEntity = when {
                    item == null -> null
                    scheduler.isReminderItem(item) -> item
                    else -> null
                } ?: run {
                    // 事项已删除 / 归档 / 提醒被关：清掉残留广播与通知
                    scheduler.cancel(itemId)
                    ReminderEventLog.record(
                        appContext, "广播到达但事项已失效，已清理残留 id=$itemId"
                    )
                    return
                }
                if (!isSnooze) {
                    // 改期/改提前量后，旧闹钟可能已被新闹钟取代：比对触发时刻，不一致即丢弃
                    val broadcastAt = intent.getLongExtra(
                        ReminderScheduler.EXTRA_TRIGGER_AT, -1L
                    )
                    val expected = scheduler.anchorTriggerAt(validItem, broadcastAt - 1L)
                    if (broadcastAt <= 0 || expected != broadcastAt) {
                        Log.d(TAG, "广播触发时刻与库内不一致，丢弃旧广播 id=$itemId")
                        ReminderEventLog.record(
                            appContext, "旧广播触发时刻不一致，已丢弃 id=$itemId「${validItem.title}」"
                        )
                        return
                    }
                }

                ReminderNotifications.ensureChannel(appContext)
                ReminderNotifications.showReminder(appContext, scheduler, validItem, isSnooze)
                ReminderEventLog.record(
                    appContext,
                    if (isSnooze) "延后闹钟再次提醒 id=$itemId「${validItem.title}」"
                    else "闹钟触发 id=$itemId「${validItem.title}」"
                )
                Log.d(TAG, "已弹出提醒通知 id=$itemId isSnooze=$isSnooze")

                // 重复事项：排下一次（非重复的一次性闹钟到此结束）
                if (!isSnooze) {
                    scheduler.schedule(validItem)
                }
            }

            else -> Log.w(TAG, "未知广播动作：$action")
        }

        // 除刷新动作自身外，任何提醒/通知按钮处理都可能改变 Hero 状态，顺带刷新常驻通知
        if (action != ReminderScheduler.ACTION_STATUS_REFRESH) {
            ScheduleStatusNotifications.refresh(appContext)
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
    }
}
