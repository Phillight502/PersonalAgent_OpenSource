package org.example.myapplication.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.local.db.ItemType
import org.example.myapplication.data.local.db.Priority
import org.example.myapplication.data.local.db.Recurrence
import org.example.myapplication.data.local.db.Scope
import org.example.myapplication.data.local.db.Source
import org.example.myapplication.data.local.db.Status
import org.example.myapplication.data.logic.ScheduleLogic
import org.example.myapplication.data.repository.ItemRepository
import org.example.myapplication.reminder.ExactAlarmGuideDialog
import org.example.myapplication.reminder.ReminderScheduler
import java.util.Calendar

private val allItemTypes = listOf(
    ItemType.SCHEDULE to "日程",
    ItemType.TODO to "待办",
    ItemType.PLAN to "计划",
    ItemType.WISH to "心愿"
)

/** 事项类型展示信息 */
private fun typeLabel(type: String): String = when (type) {
    ItemType.SCHEDULE -> "日程"
    ItemType.TODO -> "待办"
    ItemType.PLAN -> "计划"
    ItemType.WISH -> "心愿"
    else -> "事项"
}

private fun typeColor(type: String): Color = when (type) {
    ItemType.SCHEDULE -> Color(0xFF2E7D32)
    ItemType.TODO -> Color(0xFFC62828)
    ItemType.PLAN -> Color(0xFF1565C0)
    ItemType.WISH -> Color(0xFF6A1B9A)
    else -> Color.Gray
}

private fun recurrenceLabel(r: String): String = when (r) {
    Recurrence.DAILY -> "每天"
    Recurrence.WEEKLY -> "每周"
    Recurrence.MONTHLY -> "每月"
    Recurrence.YEARLY -> "每年"
    else -> ""
}

private fun priorityLabel(p: Int): String = when (p) {
    Priority.HIGH -> "高"
    Priority.LOW -> "低"
    else -> "中"
}

private fun priorityColor(p: Int): Color = when (p) {
    Priority.HIGH -> Color(0xFFD32F2F)
    Priority.LOW -> Color(0xFF9E9E9E)
    else -> Color(0xFFF9A825)
}

/** 提前提醒分钟数选项：分钟值 → 文案 */
private val remindOptions = listOf(
    0 to "准时提醒",
    15 to "提前15分钟",
    30 to "提前30分钟",
    60 to "提前1小时",
    120 to "提前2小时",
    1440 to "提前1天"
)

// ------------------------------------------------------------------
// Now 视图：分组数据模型
// ------------------------------------------------------------------

/** 时间轴上的一个事项实例：重复事项已展开到 [date] 当天 */
internal data class BoardEntry(
    val item: ItemEntity,
    val date: String,           // 实例日期 yyyy-MM-dd；无日期事项为空串
    val startMillis: Long,      // 排序/倒计时键；无具体时间取当天 23:59
    val interval: LongRange?    // 占时段日程的当天区间（毫秒）
)

internal data class FutureDay(val date: String, val entries: List<BoardEntry>)

internal data class ScheduleBoard(
    val inProgress: List<BoardEntry>,
    val allDayToday: List<ItemEntity>,
    val overdue: List<BoardEntry>,
    val todayUpcoming: List<BoardEntry>,
    val tomorrow: List<BoardEntry>,
    val futureDays: List<FutureDay>,
    val undated: List<ItemEntity>
)

private val boardDayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
private val boardMinFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val clockFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
private val weekdayNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

private fun datePlus(base: String, days: Int): String {
    val cal = Calendar.getInstance().apply { time = boardDayFmt.parse(base)!! }
    cal.add(Calendar.DAY_OF_YEAR, days)
    return boardDayFmt.format(cal.time)
}

private fun weekdayOf(date: String): String {
    val cal = Calendar.getInstance().apply { time = boardDayFmt.parse(date)!! }
    return weekdayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
}

internal fun monthDayOf(date: String): String {
    val parts = date.split("-")
    return "${parts[1].toInt()}月${parts[2].toInt()}日"
}

/** 分段/分组标题：今天 / 明天 / M月d日 周X */
private fun relativeDayLabel(date: String, today: String, tomorrow: String): String = when (date) {
    today -> "今天"
    tomorrow -> "明天"
    else -> "${monthDayOf(date)} ${weekdayOf(date)}"
}

/** 距现在的口语化时长：还有 X 分钟 / X 小时 Y 分 / X 天 */
internal fun remainingText(deltaMillis: Long): String {
    val mins = (deltaMillis / 60_000L).toInt()
    return when {
        mins <= 0 -> "马上开始"
        mins < 60 -> "还有 $mins 分钟"
        mins < 60 * 24 -> "还有 ${mins / 60} 小时" +
            if (mins % 60 != 0) " ${mins % 60} 分" else ""
        else -> "还有 ${mins / 60 / 24} 天"
    }
}

/** 过期待处理条上的说明文案 */
private fun overdueLabel(entry: BoardEntry, now: Long, today: String, yesterday: String): String {
    val time = entry.item.itemTime?.takeIf { it.isNotBlank() }
    return if (entry.date == today) {
        val mins = ((now - entry.startMillis) / 60_000L).toInt().coerceAtLeast(1)
        val span = when {
            mins < 60 -> "$mins 分钟"
            mins < 60 * 24 -> "${mins / 60} 小时"
            else -> "${mins / 60 / 24} 天"
        }
        "已超时 $span"
    } else {
        val head = if (entry.date == yesterday) "昨天" else monthDayOf(entry.date)
        listOfNotNull(head, time).joinToString(" ")
    }
}

/** 日程时间区间文案：14:00–15:00；结束 ≤ 开始按次日结束 */
internal fun intervalLabel(item: ItemEntity): String {
    val start = item.itemTime?.takeIf { it.isNotBlank() } ?: return "全天"
    val end = item.endTime?.takeIf { it.isNotBlank() } ?: return start
    return if (end <= start) "$start–次日$end" else "$start–$end"
}

/**
 * 把有效事项按"现在 / 今天 / 明天 / 以后 / 无日期 / 待处理"分组。
 * 全量内存计算，重复事项复用 [ScheduleLogic] 的实例展开规则。
 */
internal fun buildBoard(items: List<ItemEntity>, now: Long): ScheduleBoard {
    val today = boardDayFmt.format(Date(now))
    val tomorrow = datePlus(today, 1)

    val inProgress = mutableListOf<BoardEntry>()
    val allDayToday = mutableListOf<ItemEntity>()
    val overdue = mutableListOf<BoardEntry>()
    val todayUpcoming = mutableListOf<BoardEntry>()
    val tomorrowList = mutableListOf<BoardEntry>()
    val futureByDate = java.util.TreeMap<String, MutableList<BoardEntry>>()
    val undated = mutableListOf<ItemEntity>()

    fun entryFor(item: ItemEntity, date: String): BoardEntry {
        val interval = if (item.type == ItemType.SCHEDULE && !item.isAllDay) {
            ScheduleLogic.intervalOn(item, date)
        } else null
        val start = interval?.first
            ?: item.itemTime?.takeIf { it.isNotBlank() }?.let {
                runCatching { boardMinFmt.parse("$date $it")?.time }.getOrNull()
            }
            ?: runCatching { boardMinFmt.parse("$date 23:59")?.time }.getOrNull()
            ?: now
        return BoardEntry(item, date, start, interval)
    }

    fun placeFuture(date: String, entry: BoardEntry) {
        when (date) {
            tomorrow -> tomorrowList += entry
            else -> futureByDate.getOrPut(date) { mutableListOf() } += entry
        }
    }

    items.forEach { item ->
        val anchor = item.itemDate?.takeIf { it.isNotBlank() }
        if (anchor == null) {
            undated += item
            return@forEach
        }
        val nextDate = ScheduleLogic.nextOccurrence(item, today)
        if (nextDate == null) {
            // 锚点已过且不重复：昨天及更早未处理完的事项，进待处理区
            overdue += entryFor(item, anchor)
            return@forEach
        }
        if (item.isAllDay) {
            if (nextDate == today) allDayToday += item
            else placeFuture(nextDate, entryFor(item, nextDate))
            return@forEach
        }

        var entry = entryFor(item, nextDate)
        // 今天的日程已经结束：单次事项直接从"接下来"视图隐藏，重复事项顺延到下一实例
        if (nextDate == today && entry.interval != null && entry.interval!!.last <= now) {
            if (item.recurrence == Recurrence.NONE) return@forEach
            val following = ScheduleLogic.nextOccurrence(item, tomorrow) ?: return@forEach
            entry = entryFor(item, following)
            placeFuture(following, entry)
            return@forEach
        }

        when {
            entry.interval != null && now in entry.interval -> inProgress += entry
            nextDate == today -> {
                // 待办截止时刻已过 → 待处理；无具体时间的待办按当天结束前都算未截止
                if (item.type == ItemType.TODO &&
                    !item.itemTime.isNullOrBlank() && entry.startMillis <= now
                ) {
                    overdue += entry
                } else {
                    todayUpcoming += entry
                }
            }
            else -> placeFuture(nextDate, entry)
        }
    }

    val entryComparator = compareBy<BoardEntry>({ it.startMillis }, { it.item.id })
    return ScheduleBoard(
        inProgress = inProgress.sortedWith(entryComparator),
        allDayToday = allDayToday.sortedBy { it.title },
        overdue = overdue.sortedWith(entryComparator),
        todayUpcoming = todayUpcoming.sortedWith(entryComparator),
        tomorrow = tomorrowList.sortedWith(entryComparator),
        futureDays = futureByDate.map { (date, list) ->
            FutureDay(date, list.sortedWith(entryComparator))
        },
        undated = undated.sortedWith(compareBy({ it.type }, { it.title }))
    )
}

/**
 * 日程主页（Now 视图）：
 * 顶部 Hero 回答"现在在干嘛 / 下一项是什么"，下面按 今天剩余 / 明天 / 以后 分段；
 * 过期未处理事项红色置顶，类型筛选收进顶栏图标，归档收进溢出菜单。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(
    itemRepository: ItemRepository,
    reminderScheduler: ReminderScheduler,
    preferencesManager: PreferencesManager
) {
    val scope = rememberCoroutineScope()

    // 表单实时冲突检测：取候选事项当天全部 ACTIVE 事项（含重复锚点在过去的实例），
    // 编辑时通过 excludeId 排除自身；只提示不阻断保存
    val checkConflicts: suspend (ItemEntity) -> ScheduleLogic.ConflictResult? = { target ->
        target.itemDate?.takeIf { it.isNotBlank() }?.let { date ->
            ScheduleLogic.checkConflicts(
                target = target,
                dayItems = itemRepository.activeOnDate(date),
                bufferMinutes = preferencesManager.scheduleBufferMinutes,
                excludeId = target.id
            )
        }
    }

    var archiveView by remember { mutableStateOf(false) }
    var enabledTypes by remember {
        mutableStateOf(allItemTypes.map { it.first }.toSet())
    }
    var showFilter by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ItemEntity?>(null) }

    // 类型筛选在内存做，数据流始终观察全部有效事项
    val activeItems by itemRepository.observeActive(null).collectAsState(initial = emptyList())
    val archivedItems by itemRepository.observeArchived().collectAsState(initial = emptyList())

    // 每 30 秒走一次时钟，驱动 Hero 进度条/倒计时；回到前台立即刷新一次
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) now = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }

    val board = remember(activeItems, enabledTypes, now) {
        buildBoard(activeItems.filter { it.type in enabledTypes }, now)
    }
    val today = boardDayFmt.format(Date(now))
    val tomorrow = datePlus(today, 1)
    val yesterday = datePlus(today, -1)

    var tomorrowExpanded by remember { mutableStateOf(false) }
    var futureExpanded by remember { mutableStateOf(false) }
    var undatedExpanded by remember { mutableStateOf(false) }

    val openEdit: (ItemEntity) -> Unit = {
        editingItem = it
        showForm = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (archiveView) "归档事项" else "我的安排",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (archiveView) {
                                "已完成/已取消 ${archivedItems.size} 条"
                            } else {
                                "${monthDayOf(today)} ${weekdayOf(today)} · " +
                                    clockFmt.format(Date(now))
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (!archiveView) {
                        IconButton(onClick = { showFilter = true }) {
                            Icon(
                                Icons.Default.FilterAlt,
                                contentDescription = "筛选类型",
                                tint = if (enabledTypes.size < allItemTypes.size)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (archiveView) "返回当前安排" else "归档事项")
                                },
                                leadingIcon = { Icon(Icons.Default.Inventory2, null) },
                                onClick = {
                                    menuOpen = false
                                    archiveView = !archiveView
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (!archiveView) {
                FloatingActionButton(
                    onClick = {
                        editingItem = null
                        showForm = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加事项")
                }
            }
        }
    ) { innerPadding ->
        if (archiveView) {
            // 归档视图：沿用信息完整的旧卡片，方便回看/恢复
            if (archivedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inventory2,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("归档还是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "完成或取消的事项会出现在这里",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
                ) {
                    items(archivedItems, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            archived = true,
                            onComplete = { },
                            onCancel = { },
                            onRestore = { scope.launch { itemRepository.restore(item.id) } },
                            onDelete = { scope.launch { itemRepository.delete(item.id) } },
                            onEdit = { }
                        )
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)
        ) {
            // ① Hero：现在 / 下一项
            item(key = "hero") {
                NowHeroCard(
                    board = board,
                    now = now,
                    onEdit = openEdit,
                    onComplete = { scope.launch { itemRepository.complete(it.id) } },
                    onCancel = { scope.launch { itemRepository.cancel(it.id) } },
                    onDelete = { scope.launch { itemRepository.delete(it.id) } }
                )
            }

            // ② 今天的全天安排（胶囊行）
            if (board.allDayToday.isNotEmpty()) {
                item(key = "allday") {
                    AllDayChipsRow(items = board.allDayToday, onEdit = openEdit)
                }
            }

            // ③ 过期/遗留未处理
            if (board.overdue.isNotEmpty()) {
                item(key = "overdue") {
                    OverdueCard(
                        entries = board.overdue,
                        now = now,
                        today = today,
                        yesterday = yesterday,
                        onComplete = { scope.launch { itemRepository.complete(it.id) } },
                        onEdit = openEdit
                    )
                }
            }

            // ④ 今天剩余
            if (board.todayUpcoming.isNotEmpty()) {
                item(key = "header-today") {
                    SectionHeader("今天剩余", board.todayUpcoming.size)
                }
                items(board.todayUpcoming, key = { "today-${it.item.id}" }) { entry ->
                    val highlight = board.inProgress.isEmpty() &&
                        entry.item.id == board.todayUpcoming.first().item.id
                    SlimItemRow(
                        entry = entry,
                        highlight = highlight,
                        onComplete = { scope.launch { itemRepository.complete(it.id) } },
                        onEdit = openEdit,
                        onCancel = { scope.launch { itemRepository.cancel(it.id) } },
                        onDelete = { scope.launch { itemRepository.delete(it.id) } }
                    )
                }
            }

            // ⑤ 明天（默认只露 3 条）
            if (board.tomorrow.isNotEmpty()) {
                item(key = "header-tomorrow") {
                    SectionHeader(
                        title = "明天",
                        count = board.tomorrow.size,
                        expanded = tomorrowExpanded,
                        onToggle = { tomorrowExpanded = !tomorrowExpanded }
                    )
                }
                val visible = if (tomorrowExpanded) board.tomorrow
                else board.tomorrow.take(3)
                items(visible, key = { "tmr-${it.item.id}" }) { entry ->
                    SlimItemRow(
                        entry = entry,
                        highlight = false,
                        onComplete = { scope.launch { itemRepository.complete(it.id) } },
                        onEdit = openEdit,
                        onCancel = { scope.launch { itemRepository.cancel(it.id) } },
                        onDelete = { scope.launch { itemRepository.delete(it.id) } }
                    )
                }
                if (!tomorrowExpanded && board.tomorrow.size > 3) {
                    item(key = "tmr-more") {
                        TextButton(
                            onClick = { tomorrowExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("还有 ${board.tomorrow.size - 3} 项，展开")
                        }
                    }
                }
            }

            // ⑥ 以后（默认折叠）+ 无日期事项
            val futureCount = board.futureDays.sumOf { it.entries.size } + board.undated.size
            if (futureCount > 0) {
                item(key = "header-future") {
                    SectionHeader(
                        title = "以后",
                        count = futureCount,
                        expanded = futureExpanded,
                        onToggle = { futureExpanded = !futureExpanded }
                    )
                }
                if (futureExpanded) {
                    board.futureDays.forEach { day ->
                        item(key = "day-${day.date}") {
                            DaySubHeader(relativeDayLabel(day.date, today, tomorrow))
                        }
                        items(day.entries, key = { "future-${it.item.id}" }) { entry ->
                            SlimItemRow(
                                entry = entry,
                                highlight = false,
                                onComplete = { scope.launch { itemRepository.complete(it.id) } },
                                onEdit = openEdit,
                                onCancel = { scope.launch { itemRepository.cancel(it.id) } },
                                onDelete = { scope.launch { itemRepository.delete(it.id) } }
                            )
                        }
                    }
                    if (board.undated.isNotEmpty()) {
                        item(key = "undated-header") {
                            DaySubHeader("没有期限")
                        }
                        val undatedVisible = if (undatedExpanded) board.undated
                        else board.undated.take(2)
                        items(undatedVisible, key = { "undated-${it.id}" }) { item ->
                            SlimItemRow(
                                entry = BoardEntry(item, "", now, null),
                                highlight = false,
                                onComplete = { scope.launch { itemRepository.complete(it.id) } },
                                onEdit = openEdit,
                                onCancel = { scope.launch { itemRepository.cancel(it.id) } },
                                onDelete = { scope.launch { itemRepository.delete(it.id) } }
                            )
                        }
                        if (!undatedExpanded && board.undated.size > 2) {
                            item(key = "undated-more") {
                                TextButton(
                                    onClick = { undatedExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("还有 ${board.undated.size - 2} 项，展开")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 新增/编辑表单（原弹窗复用）；只勾选了单一类型时，新增默认该类型
    if (showForm) {
        val defaultType = enabledTypes.singleOrNull() ?: ItemType.SCHEDULE
        ItemFormDialog(
            initial = editingItem,
            defaultType = defaultType,
            reminderScheduler = reminderScheduler,
            onCheckConflicts = checkConflicts,
            onDismiss = { showForm = false },
            onSave = { entity ->
                scope.launch {
                    if (entity.id == 0L) itemRepository.add(entity)
                    else itemRepository.update(entity)
                }
                showForm = false
            }
        )
    }

    if (showFilter) {
        TypeFilterDialog(
            selected = enabledTypes,
            onToggle = { type ->
                enabledTypes = if (type in enabledTypes) {
                    // 至少保留一个类型，避免整页空白
                    enabledTypes.minus(type).ifEmpty { enabledTypes }
                } else {
                    enabledTypes.plus(type)
                }
            },
            onDismiss = { showFilter = false }
        )
    }
}

// ------------------------------------------------------------------
// Now 视图组件
// ------------------------------------------------------------------

/** Hero 大卡片：进行中（多个时横向翻页）/ 空档 / 今天结束 / 无安排 */
@Composable
private fun NowHeroCard(
    board: ScheduleBoard,
    now: Long,
    onEdit: (ItemEntity) -> Unit,
    onComplete: (ItemEntity) -> Unit,
    onCancel: (ItemEntity) -> Unit,
    onDelete: (ItemEntity) -> Unit
) {
    val active = board.inProgress
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active.isNotEmpty())
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            when {
                active.size == 1 -> InProgressContent(
                    active.first(), now, onEdit, onComplete, onCancel, onDelete
                )
                active.size > 1 -> {
                    val pagerState = rememberPagerState { active.size }
                    HorizontalPager(state = pagerState) { page ->
                        InProgressContent(
                            active[page], now, onEdit, onComplete, onCancel, onDelete
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        repeat(active.size) { i ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (i == pagerState.currentPage) 7.dp else 5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                            alpha = if (i == pagerState.currentPage) 1f else 0.4f
                                        )
                                    )
                            )
                        }
                    }
                }
                else -> FreeContent(board, now, onEdit)
            }
        }
    }
}

/** Hero：进行中内容（标题 + 时间地点 + 进度条 + 剩余分钟） */
@Composable
private fun InProgressContent(
    entry: BoardEntry,
    now: Long,
    onEdit: (ItemEntity) -> Unit,
    onComplete: (ItemEntity) -> Unit,
    onCancel: (ItemEntity) -> Unit,
    onDelete: (ItemEntity) -> Unit
) {
    val item = entry.item
    val range = entry.interval
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(item) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "进行中",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                clockFmt.format(Date(now)),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            // 操作菜单：进行中事项只在 Hero 出现，编辑弹窗内也无删除入口，故在此提供
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        modifier = Modifier.size(19.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onEdit(item) }
                    )
                    DropdownMenuItem(
                        text = { Text("标记完成") },
                        leadingIcon = { Icon(Icons.Default.Check, null) },
                        onClick = { menuOpen = false; onComplete(item) }
                    )
                    DropdownMenuItem(
                        text = { Text("取消事项") },
                        leadingIcon = { Icon(Icons.Default.Cancel, null) },
                        onClick = { menuOpen = false; onCancel(item) }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; confirmDelete = true }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            item.title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2
        )
        val sub = buildList {
            add(intervalLabel(item))
            item.location?.takeIf { it.isNotBlank() }?.let { add("📍 $it") }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            sub.joinToString("  ·  "),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (range != null) {
            Spacer(modifier = Modifier.height(14.dp))
            val total = (range.last - range.first).coerceAtLeast(1L)
            val fraction = ((now - range.first).toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            val remainMin = ((range.last - now) / 60_000L).toInt()
            val remainText = when {
                remainMin <= 0 -> "即将结束"
                remainMin < 60 -> "还剩 $remainMin 分钟"
                else -> "还剩 ${remainMin / 60} 小时 ${remainMin % 60} 分"
            }
            Text(
                remainText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${item.title}」？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(item)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/** Hero：空档 / 今天结束 / 近两天无安排 / 完全空 */
@Composable
private fun FreeContent(
    board: ScheduleBoard,
    now: Long,
    onEdit: (ItemEntity) -> Unit
) {
    val next = board.todayUpcoming.firstOrNull()
    val tomorrowFirst = board.tomorrow.firstOrNull()
    val futureFirst = board.futureDays.firstOrNull()?.entries?.firstOrNull()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "空档中",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                clockFmt.format(Date(now)),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        when {
            next != null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(next.item) }
            ) {
                Text(
                    "下一项",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    next.item.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(4.dp))
                val timePart = if (next.item.type == ItemType.TODO) {
                    "截止 ${next.item.itemTime ?: "今天内"}"
                } else {
                    next.item.itemTime ?: "随时"
                }
                Text(
                    listOfNotNull(
                        timePart,
                        next.item.location?.takeIf { it.isNotBlank() }
                    ).joinToString("  ·  "),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    remainingText(next.startMillis - now),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                val otherTodoCount = board.todayUpcoming.count {
                    it.item.type == ItemType.TODO && it.item.id != next.item.id
                }
                if (otherTodoCount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "另有 $otherTodoCount 件待办今天截止",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            tomorrowFirst != null -> Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "今天的安排都结束了",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "明天 ${tomorrowFirst.item.itemTime ?: "随时"} · " +
                        tomorrowFirst.item.title,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            futureFirst != null || board.undated.isNotEmpty() -> Column {
                Text(
                    "近两天没有固定安排",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (futureFirst != null) {
                    Text(
                        "${monthDayOf(futureFirst.date)} ${futureFirst.item.itemTime ?: ""} " +
                            futureFirst.item.title,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (board.undated.isNotEmpty()) {
                    Text(
                        "没有期限：${board.undated.take(2).joinToString("、") { it.title }}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            else -> Column {
                Text(
                    "近期没有安排",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "点右下角 ＋ 新增，或在聊天里告诉 AI 你的安排",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Hero 下方：今天的全天事项胶囊 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllDayChipsRow(
    items: List<ItemEntity>,
    onEdit: (ItemEntity) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "全天",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(typeColor(item.type).copy(alpha = 0.12f))
                        .clickable { onEdit(item) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Event,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = typeColor(item.type)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(item.title, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** 过期/遗留未处理事项提示条（最多展示 5 条，其余显示数量） */
@Composable
private fun OverdueCard(
    entries: List<BoardEntry>,
    now: Long,
    today: String,
    yesterday: String,
    onComplete: (ItemEntity) -> Unit,
    onEdit: (ItemEntity) -> Unit
) {
    val visible = entries.take(5)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "${entries.size} 项待处理",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            visible.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(entry.item) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.item.title,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            overdueLabel(entry, now, today, yesterday),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = { onComplete(entry.item) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "标记完成",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (entries.size > visible.size) {
                Text(
                    "另有 ${entries.size - visible.size} 项，可在筛选或归档中查看",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** 分段标题：可折叠时整行可点，右侧箭头 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "  $count",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.weight(1f))
        if (expanded != null) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "以后"段内的日期 / 无日期 小标题 */
@Composable
private fun DaySubHeader(label: String) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

/** 瘦身事项行：类型色条 + 时间 + 标题/副信息 + 完成/更多；点整行编辑 */
@Composable
private fun SlimItemRow(
    entry: BoardEntry,
    highlight: Boolean,
    onComplete: (ItemEntity) -> Unit,
    onEdit: (ItemEntity) -> Unit,
    onCancel: (ItemEntity) -> Unit,
    onDelete: (ItemEntity) -> Unit
) {
    val item = entry.item
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(item) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(typeColor(item.type))
            )
            Spacer(modifier = Modifier.width(10.dp))

            // 时间列
            Column(modifier = Modifier.width(56.dp)) {
                Text(
                    text = item.itemTime?.takeIf { it.isNotBlank() && !item.isAllDay }
                        ?: "随时",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.type == ItemType.TODO) typeColor(ItemType.TODO)
                    else MaterialTheme.colorScheme.onSurface
                )
                val caption = when {
                    item.isAllDay -> "全天"
                    item.type == ItemType.TODO -> "截止"
                    !item.endTime.isNullOrBlank() -> "至${item.endTime}"
                    else -> null
                }
                if (caption != null) {
                    Text(
                        caption,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            // 标题 + 精简副信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
            ) {
                Text(
                    item.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildList {
                    item.location?.takeIf { it.isNotBlank() }?.let { add("📍 $it") }
                    recurrenceLabel(item.recurrence).takeIf { it.isNotEmpty() }
                        ?.let { add("🔁 $it") }
                    if (item.reminderEnabled) {
                        val label = remindOptions
                            .firstOrNull { it.first == item.remindMinutes }?.second
                            ?: "已开提醒"
                        add("🔔 $label")
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString("   "),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = { onComplete(item) },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "标记完成",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(20.dp)
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(19.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onEdit(item) }
                    )
                    DropdownMenuItem(
                        text = { Text("取消事项") },
                        leadingIcon = { Icon(Icons.Default.Cancel, null) },
                        onClick = { menuOpen = false; onCancel(item) }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; confirmDelete = true }
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${item.title}」？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(item)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/** 类型筛选弹窗：勾选要在主页看到的事项类型（至少保留一个） */
@Composable
private fun TypeFilterDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("只看哪类事项", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                allItemTypes.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = value in selected,
                            onCheckedChange = { onToggle(value) }
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/**
 * 单条事项卡片
 */
@Composable
private fun ItemCard(
    item: ItemEntity,
    archived: Boolean,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSourceImage by remember { mutableStateOf(false) }
    val sourceBitmap = item.sourceImage?.let { bytes ->
        remember(item.id) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧日期块：有日期显示日/月；无日期（如心愿）显示类型图标
            if (!item.itemDate.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = item.itemDate.substring(8, 10),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${item.itemDate.substring(5, 7)}月",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(typeColor(item.type).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = typeColor(item.type),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 中间内容区
            Column(modifier = Modifier.weight(1f)) {
                // 标题行：优先级圆点 + 标题 + 类型标签
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(priorityColor(item.priority))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (archived && item.status == Status.DONE)
                            TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = typeLabel(item.type),
                        fontSize = 10.sp,
                        color = typeColor(item.type),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(typeColor(item.type).copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // 时间信息（含结束区间；结束早于开始按次日结束展示）
                val timeText = buildString {
                    if (item.isAllDay) append("全天")
                    if (!item.itemDate.isNullOrBlank() && !item.isAllDay) {
                        append(item.itemDate)
                        if (!item.itemTime.isNullOrBlank()) {
                            append(" ${item.itemTime}")
                            item.endTime?.takeIf { it.isNotBlank() }?.let { end ->
                                append("–$end")
                                if (end <= item.itemTime) append("（次日）")
                            }
                        }
                    } else if (!item.itemTime.isNullOrBlank()) {
                        append(item.itemTime)
                        item.endTime?.takeIf { it.isNotBlank() }?.let { end ->
                            append("–$end")
                            if (end <= item.itemTime) append("（次日）")
                        }
                    }
                    if (isBlank() && item.itemDate.isNullOrBlank()) append("无固定时间")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "🕐 $timeText",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 第二行：地点 / 重复 / 提醒 / 来源
                val meta = buildList {
                    item.location?.takeIf { it.isNotBlank() }?.let { add("📍 $it") }
                    item.attendees?.takeIf { it.isNotBlank() }?.let { add("👥 $it") }
                    add(if (item.scope == Scope.WORK) "💼 工作" else "🏠 私人")
                    recurrenceLabel(item.recurrence).takeIf { it.isNotEmpty() }?.let { add("🔁 $it") }
                    if (item.reminderEnabled) {
                        val label = remindOptions.firstOrNull { o -> o.first == item.remindMinutes }?.second
                            ?: "已开启提醒"
                        add("🔔 $label")
                    }
                    if (item.source == Source.AI_IMAGE) add("🖼 图片识别")
                    else if (item.source == Source.AI_TEXT) add("🤖 AI 记录")
                }
                if (meta.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = meta.joinToString("  "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 来源图片缩略图
                if (sourceBitmap != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Image(
                        bitmap = sourceBitmap.asImageBitmap(),
                        contentDescription = "来源图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSourceImage = true }
                    )
                }
            }

            // 右侧操作区
            if (archived) {
                // 归档卡片：恢复 + 删除
                IconButton(onClick = onRestore) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = "恢复",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "彻底删除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // 有效卡片：完成按钮 + 更多菜单
                IconButton(onClick = onComplete) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "标记完成",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("取消事项") },
                            leadingIcon = { Icon(Icons.Default.Cancel, null) },
                            onClick = { menuOpen = false; onCancel() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menuOpen = false; confirmDelete = true }
                        )
                    }
                }
            }
        }
    }

    // 删除二次确认
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${item.title}」？") },
            text = { Text(if (archived) "将从归档中彻底删除，无法恢复。" else "删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    // 来源图片全屏查看
    if (showSourceImage && sourceBitmap != null) {
        Dialog(
            onDismissRequest = { showSourceImage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { showSourceImage = false },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = sourceBitmap.asImageBitmap(),
                    contentDescription = "来源图片大图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }
        }
    }
}

/**
 * 事项新增/编辑表单弹窗
 * 编辑时通过 [initial] 传入已有实体；保存时回调完整实体
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ItemFormDialog(
    initial: ItemEntity?,
    defaultType: String,
    reminderScheduler: ReminderScheduler,
    onCheckConflicts: suspend (ItemEntity) -> ScheduleLogic.ConflictResult?,
    onDismiss: () -> Unit,
    onSave: (ItemEntity) -> Unit
) {
    val context = LocalContext.current
    val calendar = remember { Calendar.getInstance() }

    // 通知权限请求（Android 13+）：不阻塞保存，未授权时事项照存，授权后通知自动生效
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝也不阻断保存，设置页可再次引导 */ }
    var showExactAlarmGuide by remember { mutableStateOf(false) }

    /** 启用提醒前补齐系统权限：通知权限 + 精确闹钟引导 */
    fun ensureReminderPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !reminderScheduler.canPostNotifications()
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!reminderScheduler.canScheduleExact()) {
            showExactAlarmGuide = true
        }
    }

    var type by remember { mutableStateOf(initial?.type ?: defaultType) }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var date by remember { mutableStateOf(initial?.itemDate) }
    var time by remember { mutableStateOf(initial?.itemTime) }
    var endTime by remember { mutableStateOf(initial?.endTime) }
    var allDay by remember { mutableStateOf(initial?.isAllDay ?: false) }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var attendees by remember { mutableStateOf(initial?.attendees ?: "") }
    var itemScope by remember { mutableStateOf(initial?.scope ?: Scope.PERSONAL) }
    var priority by remember { mutableStateOf(initial?.priority ?: Priority.NORMAL) }
    var recurrence by remember { mutableStateOf(initial?.recurrence ?: Recurrence.NONE) }
    var reminderEnabled by remember { mutableStateOf(initial?.reminderEnabled ?: false) }
    var remindMinutes by remember { mutableStateOf(initial?.remindMinutes ?: 30) }

    val titleError = title.isBlank()
    // 只有非全天且选了具体时间的事项才能真正响铃
    val reminderAvailable = !allDay && !time.isNullOrBlank()
    // 结束时间只在有开始时间的非全天事项上存在
    val endTimeAvailable = reminderAvailable
    // 结束 ≤ 开始：按次日结束解释（仅展示口径，存储原值）
    val endCrossDay = endTimeAvailable &&
        endTime != null && time != null && endTime!! <= time!!

    // 实时冲突预览：仅"日程 + 有日期时间 + 非全天"参与硬冲突计算
    var conflictResult by remember {
        mutableStateOf<ScheduleLogic.ConflictResult?>(null)
    }
    LaunchedEffect(type, date, time, endTime, allDay) {
        conflictResult = if (
            type == ItemType.SCHEDULE && !allDay &&
            !date.isNullOrBlank() && !time.isNullOrBlank()
        ) {
            val candidate = (
                initial ?: ItemEntity(
                    type = type,
                    title = title.ifBlank { "（未命名事项）" },
                    source = Source.MANUAL,
                    createdAt = 0L
                )
                ).copy(
                    type = type,
                    itemDate = date,
                    itemTime = time,
                    endTime = endTime,
                    isAllDay = allDay
                )
            onCheckConflicts(candidate)
        } else {
            null
        }
    }
    val reminderHint = when {
        allDay -> "全天事项不发送通知，AI 会在对话中提醒你"
        time.isNullOrBlank() -> "选择具体时间后才能开启提醒"
        else -> "开启提醒"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增事项" else "编辑事项", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 类型选择
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ItemType.SCHEDULE, ItemType.TODO, ItemType.PLAN, ItemType.WISH
                    ).forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(typeLabel(t), fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题 *") },
                    singleLine = true,
                    isError = titleError,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 日期 / 时间
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> date = "%04d-%02d-%02d".format(y, m + 1, d) },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    }) {
                        Text(date ?: "选择日期")
                    }
                    if (date != null) {
                        TextButton(onClick = { date = null }) { Text("清除", fontSize = 12.sp) }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (!allDay) {
                        TextButton(onClick = {
                            TimePickerDialog(
                                context,
                                { _, h, min -> time = "%02d:%02d".format(h, min) },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true
                            ).show()
                        }) {
                            Text(time ?: "选择时间")
                        }
                        if (time != null) {
                            TextButton(onClick = {
                                time = null
                                endTime = null  // 无开始时间则结束时间一并清空
                            }) { Text("清除", fontSize = 12.sp) }
                        }
                    }
                }
                // 结束时间：仅有开始时间的非全天事项可选；留空按 1 小时占位计算
                if (endTimeAvailable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("至", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = {
                            val (h, m) = endTime?.split(":")
                                ?.let { it[0].toIntOrNull() to (it.getOrNull(1)?.toIntOrNull()) }
                                ?: (calendar.get(Calendar.HOUR_OF_DAY) to
                                    calendar.get(Calendar.MINUTE))
                            TimePickerDialog(
                                context,
                                { _, pickedH, pickedMin ->
                                    endTime = "%02d:%02d".format(pickedH, pickedMin)
                                },
                                h ?: calendar.get(Calendar.HOUR_OF_DAY),
                                m ?: calendar.get(Calendar.MINUTE),
                                true
                            ).show()
                        }) {
                            Text(endTime ?: "结束时间（默认1小时）")
                        }
                        if (endTime != null) {
                            TextButton(onClick = { endTime = null }) {
                                Text("清除", fontSize = 12.sp)
                            }
                        }
                        if (endCrossDay) {
                            Text(
                                text = "次日结束",
                                fontSize = 11.sp,
                                color = Color(0xFF8D6E00)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("全天", fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = allDay, onCheckedChange = {
                        allDay = it
                        if (it) {
                            time = null
                            endTime = null  // 全天事项不占时段
                            reminderEnabled = false  // 全天事项不发通知
                        }
                    })
                }
                Spacer(modifier = Modifier.height(4.dp))

                // 冲突预览：硬冲突红色、缓冲不足/全天事项黄色；仅提示，不阻止保存
                conflictResult?.takeIf { !it.isEmpty }?.let { result ->
                    val hard = result.conflicts.filter { it.hard }
                    val soft = result.conflicts.filter { !it.hard }
                    if (hard.isNotEmpty()) {
                        ConflictBanner(
                            messages = hard.map { ScheduleLogic.conflictMessage(it, date!!) },
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                            textColor = MaterialTheme.colorScheme.error,
                            prefix = "⛔"
                        )
                    }
                    if (soft.isNotEmpty()) {
                        ConflictBanner(
                            messages = soft.map { ScheduleLogic.conflictMessage(it, date!!) },
                            containerColor = Color(0xFFF9A825).copy(alpha = 0.14f),
                            textColor = Color(0xFF8D6E00),
                            prefix = "⚠️"
                        )
                    }
                    result.allDayItems.takeIf { it.isNotEmpty() }?.let { allDayList ->
                        ConflictBanner(
                            messages = listOf(
                                "当天还有全天安排：" +
                                    allDayList.take(3).joinToString("、") { it.title } +
                                    if (allDayList.size > 3) " 等" else ""
                            ),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            prefix = "📌"
                        )
                    }
                    Text(
                        text = "冲突仅作提示，仍可保存",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = attendees,
                    onValueChange = { attendees = it },
                    label = { Text("参与人物（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 工作 / 私人范围
                Text("范围", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Scope.WORK to "工作", Scope.PERSONAL to "私人").forEach { (value, label) ->
                        FilterChip(
                            selected = itemScope == value,
                            onClick = { itemScope = value },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // 优先级
                Text("优先级", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Priority.LOW, Priority.NORMAL, Priority.HIGH).forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(priorityLabel(p), fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // 重复规则
                Text("重复", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Recurrence.NONE, Recurrence.DAILY, Recurrence.WEEKLY,
                        Recurrence.MONTHLY, Recurrence.YEARLY
                    ).forEach { r ->
                        FilterChip(
                            selected = recurrence == r,
                            onClick = { recurrence = r },
                            label = {
                                Text(if (r == Recurrence.NONE) "不重复" else recurrenceLabel(r),
                                    fontSize = 12.sp)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // 提醒：仅非全天且有具体时间时可用
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (reminderAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(reminderHint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = reminderEnabled,
                        enabled = reminderAvailable,
                        onCheckedChange = {
                            reminderEnabled = it
                            if (it) ensureReminderPermissions()
                        }
                    )
                }
                if (reminderEnabled && reminderAvailable) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        remindOptions.forEach { (minutes, label) ->
                            FilterChip(
                                selected = remindMinutes == minutes,
                                onClick = { remindMinutes = minutes },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注/描述（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !titleError,
                onClick = {
                    val now = System.currentTimeMillis()
                    // 兜底：全天或无具体时间的事项强制关闭提醒，防止列表出现永不响铃的假🔔
                    val finalReminder = reminderEnabled && !allDay && !time.isNullOrBlank()
                    val base = initial ?: ItemEntity(
                        type = type,
                        title = title.trim(),
                        source = Source.MANUAL,
                        createdAt = now
                    )
                    onSave(
                        base.copy(
                            type = type,
                            title = title.trim(),
                            description = description.trim().ifBlank { null },
                            itemDate = date,
                            itemTime = if (allDay) null else time,
                            // 结束时间仅属于日程：非日程类型/全天/无开始时间一律清空（早于开始按次日解释）
                            endTime = if (type == ItemType.SCHEDULE && !allDay && !time.isNullOrBlank()) endTime else null,
                            isAllDay = allDay,
                            location = location.trim().ifBlank { null },
                            attendees = attendees.trim().ifBlank { null },
                            scope = itemScope,
                            priority = priority,
                            recurrence = recurrence,
                            reminderEnabled = finalReminder,
                            remindMinutes = if (finalReminder) remindMinutes else null,
                            updatedAt = now
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    // 精确闹钟未授权引导（不阻断表单使用）
    if (showExactAlarmGuide) {
        ExactAlarmGuideDialog(
            scheduler = reminderScheduler,
            onDismiss = { showExactAlarmGuide = false }
        )
    }
}

/**
 * 表单内的冲突提示条：逐条列出警告文案，底色区分硬冲突/软警告/全天提示
 */
@Composable
private fun ConflictBanner(
    messages: List<String>,
    containerColor: Color,
    textColor: Color,
    prefix: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        messages.forEach { msg ->
            Text(
                text = "$prefix $msg",
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = textColor
            )
        }
    }
}
