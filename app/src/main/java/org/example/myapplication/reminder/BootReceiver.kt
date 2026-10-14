package org.example.myapplication.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.AppDatabase

/**
 * 系统事件后重调度全部提醒
 *
 * 监听：开机完成、应用被更新替换、系统时间被修改、时区变更、部分国产 ROM 的快速开机。
 * 时间被用户往前调整导致的单次过期提醒会静默跳过；重复事项滚动到未来首次触发。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in HANDLED_ACTIONS) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                val preferencesManager = PreferencesManager(appContext)
                val scheduler = ReminderScheduler(appContext, preferencesManager, db.itemDao())
                scheduler.rescheduleAll()
                // 开机/替换/时间变更后恢复常驻通知并重新安排状态刷新闹钟
                ScheduleStatusNotifications.refresh(appContext)
                Log.i(TAG, "系统事件触发重调度：$action")
            } catch (e: Exception) {
                Log.e(TAG, "开机/时间变更重调度失败：$action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
