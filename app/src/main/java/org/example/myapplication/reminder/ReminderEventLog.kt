package org.example.myapplication.reminder

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 提醒触发事件日志（Debug 页用）
 *
 * 真机测试闹钟时不一定连着 Android Studio，[ReminderReceiver] 每收到一次广播
 * 就在此追加一条带时分秒的记录，持久化在 SharedPreferences 中（最多 [MAX_EVENTS] 条），
 * 用户可在 Debug 页查看/清空。正式流程无任何依赖。
 */
object ReminderEventLog {

    private const val PREFS_NAME = "reminder_debug_log"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 100
    private const val SEPARATOR = "\n"

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    /** 追加一条事件（自动加时间戳，超出上限丢弃最旧的） */
    fun record(context: Context, text: String) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val events = read(prefs).toMutableList()
        events.add("${timeFmt.format(Date())}  $text")
        while (events.size > MAX_EVENTS) events.removeAt(0)
        prefs.edit().putString(KEY_EVENTS, events.joinToString(SEPARATOR)).apply()
    }

    /** 按时间正序返回全部事件 */
    fun get(context: Context): List<String> {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return read(prefs)
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_EVENTS).apply()
    }

    private fun read(prefs: android.content.SharedPreferences): List<String> =
        prefs.getString(KEY_EVENTS, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
}
