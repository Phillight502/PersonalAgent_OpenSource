package org.example.myapplication.data.logic

import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.local.db.ItemType
import org.example.myapplication.data.local.db.Recurrence
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日程时间智能（P1）：区间计算、重复实例展开、冲突检测、空闲时段求补集。
 *
 * 纯 Kotlin 逻辑（仅依赖 java.util.Calendar 与实体），不碰 Android 框架，方便后续补单测。
 *
 * 关键语义（已与产品确认）：
 * - 只有 SCHEDULE、有日期+具体开始时间、非全天事项占用时间段；
 *   待办/计划/心愿不占时段；全天事项只作为当天软提示，不构成冲突。
 * - 重复事项按"当天是否有实例"展开：WEEKLY 同星期、MONTHLY 同号（小月自然无 31 号实例）、
 *   YEARLY 同月日（非闰年自然无 2/29 实例）、DAILY 锚点之后每天。
 * - endTime 早于/等于开始时间按次日结束解释；无 endTime 按 [DEFAULT_DURATION_MINUTES] 占位。
 * - 冲突两级：区间重叠=硬冲突；间隔小于缓冲分钟=软警告。只提示不阻断。
 */
object ScheduleLogic {

    /** 无 endTime 事项参与区间计算的默认占位时长（分钟），不回写数据库 */
    const val DEFAULT_DURATION_MINUTES = 60

    /** find_free_slots 默认查询窗口 */
    const val DEFAULT_EARLIEST = "09:00"
    const val DEFAULT_LATEST = "18:00"

    /** 空闲时段最多返回段数 */
    private const val MAX_SLOTS = 5

    private const val MILLIS_PER_MINUTE = 60_000L

    /** 一条冲突：[hard] 真=时间重叠，假=间隔不足缓冲；[gapMinutes] 仅软警告时有值 */
    data class Conflict(
        val item: ItemEntity,
        val hard: Boolean,
        val gapMinutes: Int? = null
    )

    /**
     * 某日冲突结果：
     * @param conflicts 与目标事项冲突的占时段事项（硬冲突在前）
     * @param allDayItems 当天的全天事项（仅提示，不阻断）
     */
    data class ConflictResult(
        val conflicts: List<Conflict>,
        val allDayItems: List<ItemEntity>
    ) {
        val hasHard: Boolean get() = conflicts.any { it.hard }
        val isEmpty: Boolean get() = conflicts.isEmpty() && allDayItems.isEmpty()
    }

    /** 一段空闲时间，时间均为 HH:mm（同一天内） */
    data class FreeSlot(val start: String, val end: String)

    // ------------------------------------------------------------------
    // 基础判断
    // ------------------------------------------------------------------

    /** 事项是否在某天占用一个具体时间段（参与冲突/空档计算） */
    private fun isBlockingOn(item: ItemEntity, date: String): Boolean =
        item.type == ItemType.SCHEDULE &&
            !item.isAllDay &&
            !item.itemDate.isNullOrBlank() &&
            !item.itemTime.isNullOrBlank() &&
            occursOnDate(item, date)

    /**
     * 重复/单次事项在 [date]（yyyy-MM-dd）当天是否有实例（锚点当天或之后）。
     */
    fun occursOnDate(item: ItemEntity, date: String): Boolean {
        val anchor = item.itemDate?.takeIf { it.isNotBlank() } ?: return false
        if (anchor > date) return false
        if (anchor == date) return true

        val anchorCal = parseDate(anchor) ?: return false
        val targetCal = parseDate(date) ?: return false
        return when (item.recurrence) {
            Recurrence.DAILY -> true
            Recurrence.WEEKLY ->
                anchorCal.get(Calendar.DAY_OF_WEEK) == targetCal.get(Calendar.DAY_OF_WEEK)
            Recurrence.MONTHLY ->
                anchorCal.get(Calendar.DAY_OF_MONTH) == targetCal.get(Calendar.DAY_OF_MONTH)
            Recurrence.YEARLY ->
                anchorCal.get(Calendar.MONTH) == targetCal.get(Calendar.MONTH) &&
                    anchorCal.get(Calendar.DAY_OF_MONTH) == targetCal.get(Calendar.DAY_OF_MONTH)
            else -> false // NONE 且日期不同
        }
    }

    /**
     * 事项从 [from]（含，yyyy-MM-dd）起下一个有实例的日期；锚点在未来直接返回锚点，
     * 锚点已过且不重复返回 null。重复规则自然跳过不存在的日期：
     * MONTHLY 无 31 号的月份跳过（如 1/31 → 3/31 约需找 2 个月）；
     * YEARLY 非闰年跳过 2/29（最多向后找 6 年覆盖一个闰年周期）。
     */
    fun nextOccurrence(item: ItemEntity, from: String): String? {
        val anchor = item.itemDate?.takeIf { it.isNotBlank() } ?: return null
        if (anchor >= from) return anchor
        if (item.recurrence == Recurrence.NONE) return null

        val anchorCal = parseDate(anchor) ?: return null
        val fromCal = parseDate(from) ?: return null

        return when (item.recurrence) {
            Recurrence.DAILY -> from

            Recurrence.WEEKLY -> {
                val add = (anchorCal.get(Calendar.DAY_OF_WEEK) -
                    fromCal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                (fromCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_MONTH, add)
                }.timeInMillis.let { formatDate(it) }
            }

            Recurrence.MONTHLY -> {
                val dayOfMonth = anchorCal.get(Calendar.DAY_OF_MONTH)
                var result: String? = null
                // 逐月探测：先把日置 1 避免月末加月份时溢出回卷，最多看 12 个月
                repeat(12) { step ->
                    if (result == null) {
                        val c = (fromCal.clone() as Calendar).apply {
                            set(Calendar.DAY_OF_MONTH, 1)
                            add(Calendar.MONTH, step)
                        }
                        if (c.getActualMaximum(Calendar.DAY_OF_MONTH) >= dayOfMonth) {
                            c.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            val d = formatDate(c.timeInMillis)
                            if (d >= from) result = d
                        }
                    }
                }
                result
            }

            Recurrence.YEARLY -> {
                val month = anchorCal.get(Calendar.MONTH)
                val dayOfMonth = anchorCal.get(Calendar.DAY_OF_MONTH)
                var result: String? = null
                repeat(6) { step ->
                    if (result == null) {
                        val c = (fromCal.clone() as Calendar).apply {
                            set(Calendar.DAY_OF_MONTH, 1)
                            add(Calendar.YEAR, step)
                        }
                        c.set(Calendar.MONTH, month)
                        if (c.getActualMaximum(Calendar.DAY_OF_MONTH) >= dayOfMonth) {
                            c.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            val d = formatDate(c.timeInMillis)
                            if (d >= from) result = d
                        }
                    }
                }
                result
            }

            else -> null
        }
    }

    /**
     * 事项在 [date] 当天的占用区间（毫秒）；不占时段返回 null。
     * 结束 ≤ 开始时按次日结束；无结束时间按默认时长当作 1 小时。
     */
    fun intervalOn(item: ItemEntity, date: String): LongRange? {
        if (item.isAllDay ||
            item.itemDate.isNullOrBlank() ||
            item.itemTime.isNullOrBlank() ||
            !occursOnDate(item, date)
        ) {
            return null
        }
        val start = parseDateTime(date, item.itemTime) ?: return null
        val rawEnd = item.endTime?.takeIf { it.isNotBlank() }
        val end = if (rawEnd != null) {
            val startMinutes = toMinutes(item.itemTime)
            val endMinutes = toMinutes(rawEnd)
            if (endMinutes <= startMinutes) {
                // 跨天：结束时间在次日凌晨
                start + (endMinutes + 24 * 60 - startMinutes) * MILLIS_PER_MINUTE
            } else {
                parseDateTime(date, rawEnd)
            }
        } else {
            start + DEFAULT_DURATION_MINUTES * MILLIS_PER_MINUTE
        }
        return start..(end ?: return null)
    }

    // ------------------------------------------------------------------
    // 冲突检测
    // ------------------------------------------------------------------

    /**
     * 检测 [target]（可为未落库候选）在其日期当天与 [dayItems] 的冲突。
     * 调用方需保证 dayItems 为当天的 ACTIVE 事项（getBetween 结果即可）；
     * [excludeId] 用于编辑改期时排除事项自身。
     * target 不占时段（无日期/无时间/全天/非日程）时只返回当天全天事项提示。
     */
    fun checkConflicts(
        target: ItemEntity,
        dayItems: List<ItemEntity>,
        bufferMinutes: Int,
        excludeId: Long = target.id
    ): ConflictResult {
        val date = target.itemDate?.takeIf { it.isNotBlank() }
            ?: return ConflictResult(emptyList(), emptyList())

        val others = dayItems.filter { it.id != excludeId && occursOnDate(it, date) }
        val allDayItems = others.filter { it.isAllDay }

        val targetInterval = if (target.type == ItemType.SCHEDULE && !target.isAllDay) {
            intervalOn(target, date)
        } else null

        val conflicts = if (targetInterval == null) {
            emptyList()
        } else {
            others.mapNotNull { other ->
                val otherInterval = intervalOn(other, date) ?: return@mapNotNull null
                when {
                    targetInterval.first < otherInterval.last &&
                        targetInterval.last > otherInterval.first ->
                        Conflict(other, hard = true)
                    bufferMinutes > 0 -> {
                        val bufferMillis = bufferMinutes * MILLIS_PER_MINUTE
                        // 两区间不重叠时两个方向差值一正一负，取 max 才是真实间隔；
                        // 首尾相接时间隔为 0，同样应触发缓冲提示
                        val gap = maxOf(
                            otherInterval.first - targetInterval.last,
                            targetInterval.first - otherInterval.last
                        )
                        if (gap in 0 until bufferMillis) {
                            Conflict(other, hard = false, gapMinutes = (gap / MILLIS_PER_MINUTE).toInt())
                        } else null
                    }
                    else -> null
                }
            }.sortedWith(compareBy({ !it.hard }, { it.item.itemTime ?: "99:99" }))
        }
        return ConflictResult(conflicts, allDayItems)
    }

    /** 冲突的中文说明文案（AI warnings 与 UI 提示条共用） */
    fun conflictMessage(conflict: Conflict, date: String): String {
        val range = intervalOn(conflict.item, date)
        val timeText = range?.let {
            // 结束时间落在次日（跨天事项）时明确标注，避免读成同一天
            val endLabel = if (formatDate(it.last) != date) "次日${formatTime(it.last)}" else formatTime(it.last)
            "${formatTime(it.first)}–$endLabel"
        } ?: ""
        return if (conflict.hard) {
            "与「${conflict.item.title}」（$timeText）时间重叠"
        } else {
            "与「${conflict.item.title}」仅间隔 ${conflict.gapMinutes} 分钟，时间排得比较紧"
        }
    }

    // ------------------------------------------------------------------
    // 空闲时段
    // ------------------------------------------------------------------

    /**
     * 求 [date] 当天 [earliest]–[latest] 窗口内、长度 ≥ [durationMinutes] 的空闲段。
     * 忙区间两端各扩 [bufferMinutes]；查今天时左边界裁剪到 [nowMillis]。最多 5 段。
     */
    fun findFreeSlots(
        date: String,
        durationMinutes: Int = DEFAULT_DURATION_MINUTES,
        earliest: String = DEFAULT_EARLIEST,
        latest: String = DEFAULT_LATEST,
        dayItems: List<ItemEntity>,
        bufferMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): List<FreeSlot> {
        val dayStart = parseDate(date)?.timeInMillis ?: return emptyList()
        val earliestMinutes = toMinutes(earliest)
        val latestMinutes = toMinutes(latest).coerceAtLeast(earliestMinutes)
        val windowStart = dayStart + earliestMinutes * MILLIS_PER_MINUTE
        val windowEnd = dayStart + latestMinutes * MILLIS_PER_MINUTE

        // 当天占用区间（含重复实例），两端扩缓冲后合并
        val busy = dayItems
            .filter { isBlockingOn(it, date) }
            .mapNotNull { intervalOn(it, date) }
            .map { range ->
                val bufferedStart = range.first - bufferMinutes * MILLIS_PER_MINUTE
                val bufferedEnd = range.last + bufferMinutes * MILLIS_PER_MINUTE
                bufferedStart..bufferedEnd
            }
            .sortedBy { it.first }

        val merged = mutableListOf<LongRange>()
        for (range in busy) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }

        // 查今天时已经过去的时间不可用
        val lowerBound = if (date == formatDate(nowMillis)) {
            maxOf(windowStart, nowMillis)
        } else windowStart

        val slots = mutableListOf<FreeSlot>()
        var cursor = maxOf(lowerBound, windowStart)
        for (range in merged) {
            if (range.last <= cursor) continue
            if (range.first >= windowEnd) break
            if (range.first - cursor >= durationMinutes * MILLIS_PER_MINUTE) {
                slots += FreeSlot(formatTime(cursor), formatTime(range.first))
                if (slots.size >= MAX_SLOTS) return slots
            }
            cursor = maxOf(cursor, range.last)
            if (cursor >= windowEnd) break
        }
        if (cursor < windowEnd &&
            windowEnd - cursor >= durationMinutes * MILLIS_PER_MINUTE &&
            slots.size < MAX_SLOTS
        ) {
            slots += FreeSlot(formatTime(cursor), formatTime(windowEnd))
        }
        return slots
    }

    // ------------------------------------------------------------------
    // 全天合理性评审
    // ------------------------------------------------------------------

    /** 评审阈值：当天占时段总时长超过 6 小时视为过载 */
    private const val REVIEW_OVERLOAD_MINUTES = 360
    private const val REVIEW_LUNCH_START = "11:30"
    private const val REVIEW_LUNCH_END = "13:30"
    private const val REVIEW_LUNCH_MIN_GAP = 30
    /** 相邻事项跨地点且间隔少于该分钟数时提示通勤风险 */
    private const val REVIEW_LOCATION_GAP_MINUTES = 30
    private const val REVIEW_EARLY_START = "08:30"
    private const val REVIEW_LATE_END = "20:00"

    /** 全天时间轴上的一条占时段事项 */
    data class TimelineEntry(
        val start: String,
        /** 跨天结束时为"次日HH:mm" */
        val end: String,
        val title: String,
        val location: String?
    )

    /**
     * 一条评审问题。
     * @param warn true=建议处理（硬冲突/过载），false=知情提示（午休/跨地点/边界）
     * @param suggestion 可执行的改期建议（移动到哪个空档）；没有可执行建议时为 null，只做文字提示
     */
    data class DayIssue(
        val warn: Boolean,
        val text: String,
        val suggestion: Suggestion? = null
    ) {
        data class Suggestion(
            val itemId: Long,
            val itemTitle: String,
            /** 原开始时间 HH:mm */
            val fromStart: String,
            /** 建议开始时间 HH:mm */
            val toStart: String,
            /** 建议结束时间 HH:mm */
            val toEnd: String,
            /** 建议落到的日期 yyyy-MM-dd（过载时可能是次日） */
            val toDate: String
        )
    }

    /** 某日全天评审结果 */
    data class DayReview(
        val date: String,
        val blockingCount: Int,
        /** 当天占时段总分钟数（重叠区间按并集计，不重复累计） */
        val totalScheduledMinutes: Int,
        val timeline: List<TimelineEntry>,
        val allDayItems: List<ItemEntity>,
        val issues: List<DayIssue>
    ) {
        val isHealthy: Boolean get() = issues.isEmpty()
    }

    /**
     * 全天合理性评审（确定性规则，不交给模型自由发挥）：
     * 1. 两两扫描硬冲突（时间重叠）与软警告（间隔不足缓冲）；
     * 2. 会议过载（总时长 > [REVIEW_OVERLOAD_MINUTES] 分钟）；
     * 3. 午休窗口 11:30–13:30 没有连续 30 分钟空档；
     * 4. 相邻事项跨地点且间隔 < [REVIEW_LOCATION_GAP_MINUTES] 分钟；
     * 5. 最早开始早于 [REVIEW_EARLY_START] 或最晚结束晚于 [REVIEW_LATE_END]。
     * 硬冲突为后者生成迁回当天空档的建议；过载为最后一场生成次日空档建议；其余只给文字。
     */
    fun reviewDay(
        date: String,
        dayItems: List<ItemEntity>,
        bufferMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
        /**
         * 过载建议要把事项挪到次日时，需要次日当天的真实事项来算空档；
         * 不传则按次日无占用估算（纯逻辑/测试场景）。
         */
        nextDayItemsProvider: ((String) -> List<ItemEntity>)? = null
    ): DayReview {
        val dayStartCal = parseDate(date)
            ?: return DayReview(date, 0, 0, emptyList(), emptyList(), emptyList())
        val dayStart = dayStartCal.timeInMillis

        data class Block(val item: ItemEntity, val range: LongRange)

        val blocks = dayItems
            .filter { isBlockingOn(it, date) }
            .mapNotNull { item -> intervalOn(item, date)?.let { Block(item, it) } }
            .sortedBy { it.range.first }

        val allDay = dayItems.filter { it.isAllDay && occursOnDate(it, date) }

        val timeline = blocks.map { b ->
            val endLabel = if (formatDate(b.range.last) != date) {
                "次日${formatTime(b.range.last)}"
            } else {
                formatTime(b.range.last)
            }
            TimelineEntry(formatTime(b.range.first), endLabel, b.item.title, b.item.location)
        }

        val totalMinutes = (mergeRanges(blocks.map { it.range }).sumOf { it.last - it.first } /
            MILLIS_PER_MINUTE).toInt()

        val issues = mutableListOf<DayIssue>()
        val suggestedIds = mutableSetOf<Long>()

        // 1. 两两扫描：blocks 已按开始时间排序，j 开始早于 i 结束即重叠
        for (i in blocks.indices) {
            for (j in i + 1..blocks.lastIndex) {
                val a = blocks[i]
                val b = blocks[j]
                if (b.range.first < a.range.last) {
                    val suggestion = moveSuggestion(
                        b.item, b.range, date, dayItems, bufferMinutes, nowMillis, suggestedIds
                    )
                    issues += DayIssue(
                        warn = true,
                        text = "「${a.item.title}」（${formatTime(a.range.first)}–" +
                            "${endLabel(date, a.range.last)}）和「${b.item.title}」" +
                            "（${formatTime(b.range.first)}–${endLabel(date, b.range.last)}）时间重叠",
                        suggestion = suggestion
                    )
                } else if (bufferMinutes > 0) {
                    val gap = ((b.range.first - a.range.last) / MILLIS_PER_MINUTE).toInt()
                    if (gap in 0 until bufferMinutes) {
                        issues += DayIssue(
                            warn = true,
                            text = "「${a.item.title}」和「${b.item.title}」之间只隔 $gap 分钟，赶场太紧"
                        )
                    }
                }
            }
        }

        // 2. 过载：建议把最晚一场移到次日空档
        if (totalMinutes > REVIEW_OVERLOAD_MINUTES) {
            val last = blocks.last()
            val nextCal = Calendar.getInstance().apply {
                time = dayStartCal.time
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val nextDate = formatDate(nextCal.timeInMillis)
            val duration = ((last.range.last - last.range.first) / MILLIS_PER_MINUTE)
                .toInt().coerceIn(15, 240)
            val tomorrowItems = nextDayItemsProvider?.invoke(nextDate).orEmpty()
            val tomorrowSlot = findFreeSlots(
                nextDate, duration, DEFAULT_EARLIEST, DEFAULT_LATEST,
                tomorrowItems, bufferMinutes, nowMillis
            ).firstOrNull()
            val suggestion = tomorrowSlot?.let {
                if (suggestedIds.add(last.item.id)) {
                    DayIssue.Suggestion(
                        itemId = last.item.id,
                        itemTitle = last.item.title,
                        fromStart = last.item.itemTime ?: formatTime(last.range.first),
                        toStart = it.start,
                        toEnd = it.end,
                        toDate = nextDate
                    )
                } else null
            }
            val hoursText = "%.1f".format(Locale.CHINA, totalMinutes / 60.0)
            issues += DayIssue(
                warn = true,
                text = "当天排了 $blocks.size 场、总计约 $hoursText 小时，日程偏满，建议精简一场或挪到其他天",
                suggestion = suggestion
            )
        }

        // 3. 午休被占
        val lunchStart = dayStart + toMinutes(REVIEW_LUNCH_START) * MILLIS_PER_MINUTE
        val lunchEnd = dayStart + toMinutes(REVIEW_LUNCH_END) * MILLIS_PER_MINUTE
        val lunchGap = maxGapMinutes(blocks.map { it.range }, lunchStart, lunchEnd)
        if (blocks.isNotEmpty() && lunchGap < REVIEW_LUNCH_MIN_GAP) {
            issues += DayIssue(
                warn = false,
                text = "中午 11:30–13:30 之间没有连续 30 分钟空档，可能挤不出吃饭和休息的时间"
            )
        }

        // 4. 跨地点紧凑
        for (i in 0 until blocks.size - 1) {
            val a = blocks[i]
            val b = blocks[i + 1]
            val locA = a.item.location?.trim().orEmpty()
            val locB = b.item.location?.trim().orEmpty()
            if (locA.isNotBlank() && locB.isNotBlank() && locA != locB) {
                val gap = ((b.range.first - a.range.last) / MILLIS_PER_MINUTE).toInt()
                if (gap in 0 until REVIEW_LOCATION_GAP_MINUTES) {
                    issues += DayIssue(
                        warn = false,
                        text = "「${a.item.title}」在$locA、「${b.item.title}」在$locB，" +
                            "两场只隔 $gap 分钟，跨地点通勤可能来不及"
                    )
                }
            }
        }

        // 5. 边界时段（是否违反用户偏好在提示词层由模型结合记忆判断，这里只报事实）
        val earliestStart = toMinutes(formatTime(blocks.first().range.first))
        if (earliestStart < toMinutes(REVIEW_EARLY_START)) {
            issues += DayIssue(
                warn = false,
                text = "最早的一场 ${formatTime(blocks.first().range.first)} 就开始了，开始得比较早"
            )
        }
        // 最晚结束：跨天到凌晨本身就算"很晚"；同日则按分钟数比较
        val crossMidnight = blocks.firstOrNull { formatDate(it.range.last) != date }
        val latestSameDayEnd = blocks.maxOf { toMinutes(formatTime(it.range.last)) }
        if (crossMidnight != null) {
            issues += DayIssue(
                warn = false,
                text = "「${crossMidnight.item.title}」要到次日${formatTime(crossMidnight.range.last)}" +
                    "才结束，熬得比较晚"
            )
        } else if (latestSameDayEnd > toMinutes(REVIEW_LATE_END)) {
            val hh = latestSameDayEnd / 60
            val mm = latestSameDayEnd % 60
            issues += DayIssue(
                warn = false,
                text = "最晚的一场到 %02d:%02d 才结束，收工比较晚".format(hh, mm)
            )
        }

        return DayReview(date, blocks.size, totalMinutes, timeline, allDay, issues)
    }

    /** 区间并集合并（用于总时长统计与空档计算） */
    private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
        val merged = mutableListOf<LongRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /** 窗口 [windowStart, windowEnd] 内未被 [ranges] 覆盖的最长连续空档（分钟） */
    private fun maxGapMinutes(ranges: List<LongRange>, windowStart: Long, windowEnd: Long): Int {
        var cursor = windowStart
        var best = 0L
        for (r in mergeRanges(ranges)) {
            if (r.last <= cursor) continue
            if (r.first >= windowEnd) break
            if (r.first > cursor) {
                best = maxOf(best, minOf(r.first, windowEnd) - cursor)
            }
            cursor = maxOf(cursor, minOf(r.last, windowEnd))
            if (cursor >= windowEnd) break
        }
        best = maxOf(best, windowEnd - cursor)
        return (best / MILLIS_PER_MINUTE).toInt()
    }

    /** 为冲突中较晚的一场 [item] 找当天可用空档，生成改期建议；同一事项只建议一次 */
    private fun moveSuggestion(
        item: ItemEntity,
        currentRange: LongRange,
        date: String,
        dayItems: List<ItemEntity>,
        bufferMinutes: Int,
        nowMillis: Long,
        suggestedIds: MutableSet<Long>
    ): DayIssue.Suggestion? {
        if (!suggestedIds.add(item.id)) return null
        val duration = ((currentRange.last - currentRange.first) / MILLIS_PER_MINUTE)
            .toInt().coerceIn(15, 240)
        val slot = findFreeSlots(
            date, duration, DEFAULT_EARLIEST, DEFAULT_LATEST,
            dayItems, bufferMinutes, nowMillis
        ).firstOrNull { it.start != item.itemTime } ?: return null
        return DayIssue.Suggestion(
            itemId = item.id,
            itemTitle = item.title,
            fromStart = item.itemTime ?: formatTime(currentRange.first),
            toStart = slot.start,
            toEnd = slot.end,
            toDate = date
        )
    }

    private fun endLabel(date: String, endMillis: Long): String =
        if (formatDate(endMillis) != date) "次日${formatTime(endMillis)}" else formatTime(endMillis)

    // ------------------------------------------------------------------
    // 解析/格式化工具
    // ------------------------------------------------------------------

    private fun parseDate(raw: String): Calendar? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(raw)?.let {
            Calendar.getInstance().apply { time = it }
        }
    }.getOrNull()

    private fun parseDateTime(date: String, time: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse("$date $time")?.time
    }.getOrNull()

    /** "HH:mm" → 当天分钟数；非法返回 0 */
    private fun toMinutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        if (parts.size != 2) return 0
        val h = parts[0].toIntOrNull() ?: return 0
        val m = parts[1].toIntOrNull() ?: return 0
        return h * 60 + m
    }

    /** 毫秒 → HH:mm（跨天的结束时间原样显示，调用方自行标注"次日"） */
    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))

    /** 毫秒 → yyyy-MM-dd */
    fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))
}
