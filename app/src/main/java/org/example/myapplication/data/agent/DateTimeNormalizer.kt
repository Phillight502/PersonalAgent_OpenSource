package org.example.myapplication.data.agent

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * AI 输出的日期/时间文本归一化
 *
 * 模型绝大多数时候会按工具 schema 返回 yyyy-MM-dd / HH:mm，但偶尔会回传
 * "2026/9/18"、"9月18日"、"下午3点半" 等格式；直接入库会导致提醒无法解析、
 * 列表按字符串排序错乱。这里统一容错解析为标准格式，无法识别时返回 null，
 * 由调用方告知模型向用户追问，而不是把脏数据静默收下。
 *
 * 纯 JVM 逻辑、无 Android 依赖，方便单测。
 */
object DateTimeNormalizer {

    private val outDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val outTimeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    /**
     * 归一化日期。支持：
     * - 标准：2026-09-18
     * - 斜杠/点/短横：2026/9/18、2026.9.18
     * - 中文：2026年9月18日、9月18日（补当前年）、9/18（补当前年）
     * - 相对词：今天/今日、明天/明日、后天、大后天
     * 非法日期（如 2 月 30 日）返回 null。
     */
    fun normalizeDate(raw: String?, now: Calendar = Calendar.getInstance()): String? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null

        // 相对日期词
        relativeDays[text]?.let { delta ->
            return (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, delta)
            }.let { outDateFmt.format(it.time) }
        }

        // yyyy 年 M 月 d 日
        Regex("""^(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?$""")
            .matchEntire(text)?.let { m ->
                return buildDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
        // 4 位年 + 分隔符 + 月 + 分隔符 + 日
        Regex("""^(\d{4})[./-](\d{1,2})[./-](\d{1,2})$""")
            .matchEntire(text)?.let { m ->
                return buildDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
        // M 月 d 日（补当前年）
        Regex("""^(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?$""")
            .matchEntire(text)?.let { m ->
                return buildDate(now.get(Calendar.YEAR), m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        // M/d（补当前年）
        Regex("""^(\d{1,2})/(\d{1,2})$""")
            .matchEntire(text)?.let { m ->
                return buildDate(now.get(Calendar.YEAR), m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        // 标准格式本身
        if (text.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) {
            return runCatching {
                outDateFmt.isLenient = false
                outDateFmt.parse(text)
                text
            }.getOrNull()
        }
        return null
    }

    /**
     * 归一化时间。支持：
     * - 15:30、3:05、3点、3点半、3点05
     * - 带时段：下午3点、晚上8点半、上午9:30、中午12点、凌晨1点
     * 非法时间返回 null。
     */
    fun normalizeTime(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null

        // 时段词决定 12→24 小时换算
        val period: Int? = when {
            text.contains("凌晨") -> Period.MORNING
            text.contains("早上") || text.contains("上午") -> Period.MORNING
            text.contains("中午") -> Period.NOON
            text.contains("下午") || text.contains("傍晚") -> Period.PM
            text.contains("晚上") || text.contains("晚间") -> Period.PM
            else -> null
        }

        // 提取"小时:分钟"或"小时点[半|分钟]"
        val clockMatch = Regex("""(\d{1,2})\s*[:：点时]\s*(\d{1,2})?\s*(半)?分?钟?""").find(text)
            ?: return null
        var hour = clockMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = when {
            clockMatch.groupValues[3] == "半" -> 30
            clockMatch.groupValues[2].isNotBlank() -> clockMatch.groupValues[2].toInt()
            else -> 0
        }
        if (minute !in 0..59) return null

        hour = when (period) {
            Period.PM -> if (hour < 12) hour + 12 else hour
            Period.NOON -> if (hour < 11) hour + 12 else hour   // 中午一般指 11~13 点
            Period.MORNING -> hour                              // 凌晨/早上/上午按字面
            else -> hour                                        // 无时段词：模型按 24 小时制理解
        }
        if (hour !in 0..23) return null

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return outTimeFmt.format(cal.time)
    }

    /** 严格构造日期，月日非法（如 2/30、13 月）返回 null */
    private fun buildDate(year: Int, month: Int, day: Int): String? {
        if (month !in 1..12 || day !in 1..31) return null
        val cal = Calendar.getInstance().apply {
            isLenient = false
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return runCatching { outDateFmt.format(cal.time) }.getOrNull()
    }

    private object Period {
        const val MORNING = 0
        const val NOON = 1
        const val PM = 2
    }

    private val relativeDays = mapOf(
        "今天" to 0, "今日" to 0,
        "明天" to 1, "明日" to 1,
        "后天" to 2, "后日" to 2,
        "大后天" to 3
    )
}
