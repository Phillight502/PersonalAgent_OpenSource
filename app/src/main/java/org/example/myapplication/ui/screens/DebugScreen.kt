package org.example.myapplication.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.example.myapplication.data.agent.AssistantAgent
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.local.db.ItemType
import org.example.myapplication.data.local.db.Recurrence
import org.example.myapplication.data.local.db.Source
import org.example.myapplication.data.local.db.Status
import org.example.myapplication.data.remote.AiService
import org.example.myapplication.data.repository.AuditRepository
import org.example.myapplication.data.repository.ItemRepository
import org.example.myapplication.reminder.ReminderEventLog
import org.example.myapplication.reminder.ReminderNotifications
import org.example.myapplication.reminder.ReminderScheduler
import org.example.myapplication.util.SystemTtsSpeaker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Debug 功能自测页
 *
 * 覆盖：只读自检、权限状态、网络与 AI 连通性、通知展示、真实闹钟链路、
 * 重复调度推算、数据库 CRUD、全量重排/撤销、AI 工具链路、TTS、闹钟触发记录。
 * 所有写库的测试数据标题统一加 [ItemRepository.TEST_PREFIX] 前缀，可用底部按钮一键清理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    preferencesManager: PreferencesManager,
    aiService: AiService,
    itemRepository: ItemRepository,
    reminderScheduler: ReminderScheduler,
    assistantAgent: AssistantAgent,
    auditRepository: AuditRepository,
    /** 从设置页隐藏入口打开时传入，顶栏显示返回；作为底部 Tab 时为 null */
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var statusText by remember { mutableStateOf("正在读取…") }
    var eventText by remember { mutableStateOf("") }
    var testUrl by remember { mutableStateOf("https://www.baidu.com") }

    // 需要二次确认的危险操作：提示文案 + 动作
    var confirmRequest by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    val clockFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }
    val auditFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA) }
    // 事项操作审计：Room Flow 驱动，AI/手动写操作后自动刷新，最多展示最近 100 条
    val auditLogs by auditRepository.observeRecent(100).collectAsState(initial = emptyList())
    fun appendLog(text: String) {
        val stamp = clockFmt.format(Date())
        logLines = (logLines + text.trimEnd().split("\n").map { "$stamp  $it" })
            .takeLast(MAX_LOG_LINES)
    }

    /** 统一的异步测试执行器：加锁防重入、异常兜底进日志 */
    fun runBusy(title: String, block: suspend () -> String) {
        if (busy) return
        coroutineScope.launch {
            busy = true
            appendLog("▶ $title")
            try {
                appendLog(block())
            } catch (e: Exception) {
                appendLog("✗ 异常：${e::class.java.simpleName} ${e.message ?: ""}")
            } finally {
                busy = false
            }
        }
    }

    /** 安排一条真实的调试测试闹钟（15 秒等任意毫秒时刻走专用调试通道） */
    fun scheduleTestAlarm(seconds: Int) {
        runBusy("安排 $seconds 秒后的调试闹钟") {
            val targetAt = System.currentTimeMillis() + seconds * 1000L
            val cal = Calendar.getInstance().apply { timeInMillis = targetAt }
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
            val item = ItemEntity(
                type = ItemType.SCHEDULE,
                title = "${ItemRepository.TEST_PREFIX}闹钟${seconds}秒",
                itemDate = dateFmt.format(cal.time),
                itemTime = timeFmt.format(cal.time),
                reminderEnabled = true,
                remindMinutes = 0,
                source = Source.MANUAL
            )
            val id = itemRepository.insertForTest(item)
            reminderScheduler.scheduleDebugAt(id, targetAt)
            val mode = if (reminderScheduler.canScheduleExact()) "精确闹钟" else "不精确闹钟（降级）"
            "✓ 测试事项已入库 id=$id；$mode 将于 ${clockFmt.format(cal.time)} 触发（约 $seconds 秒后）。\n" +
                "请保持应用在后台，到点查看通知栏，触发结果见下方「闹钟触发记录」。"
        }
    }

    suspend fun refreshStatus() {
        val lines = buildList {
            add("通知权限：" + if (reminderScheduler.canPostNotifications()) "已授予 ✓" else "未授予 ✗")
            add("精确闹钟：" + if (reminderScheduler.canScheduleExact()) "可用 ✓" else "未授权，将降级为不精确闹钟 ⚠")
            add("电池白名单：" + if (reminderScheduler.isIgnoringBatteryOptimizations()) "已加入 ✓" else "未加入（休眠时可能延迟）⚠")
            add("通知渠道「事项提醒」：" + if (ReminderNotifications.channelExists(context)) "已创建 ✓" else "不存在 ✗")
            add("提醒总开关：" + if (preferencesManager.reminderEnabled) "开" else "关")
            add("待调度提醒事项：${reminderScheduler.needingReminderCount()} 条")
            runCatching { itemRepository.activeAll().size }
                .onSuccess { add("数据库有效事项：$it 条 ✓") }
                .onFailure { add("数据库读取失败 ✗ ${it.message}") }
        }
        statusText = lines.joinToString("\n")
    }

    fun refreshEvents() {
        eventText = ReminderEventLog.get(context).joinToString("\n")
    }

    LaunchedEffect(Unit) {
        refreshStatus()
        refreshEvents()
    }

    // TTS 引擎随页面创建、离开即释放
    val speaker = remember { SystemTtsSpeaker(context, preferencesManager.ttsEngine) }
    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }

    // ===== 调度推算表单状态 =====
    val recurrenceOptions = listOf(
        "不重复" to Recurrence.NONE,
        "每天" to Recurrence.DAILY,
        "每周" to Recurrence.WEEKLY,
        "每月" to Recurrence.MONTHLY,
        "每年" to Recurrence.YEARLY
    )
    var recurrenceSel by remember { mutableStateOf(Recurrence.NONE) }
    val defaultAnchor = remember {
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.time)
    }
    var anchorDate by remember { mutableStateOf(defaultAnchor) }
    var anchorTime by remember { mutableStateOf("09:00") }
    var leadMinutesInput by remember { mutableStateOf("0") }
    var customSeconds by remember { mutableStateOf("30") }
    var ttsText by remember {
        mutableStateOf("这是一条语音播报测试，如果你听到这段话，说明语音功能正常。")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Debug - 功能自测",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ===== API Key 状态 =====
            SectionCard("API Key 状态", containerColor = MaterialTheme.colorScheme.primaryContainer) {
                val apiKey = preferencesManager.apiKey
                StatusLine("已配置", apiKey.isNotBlank())
                Text("长度：${apiKey.length} 字符", fontSize = 12.sp)
                Text(
                    "前10位：${if (apiKey.length >= 10) apiKey.substring(0, 10) + "…" else apiKey}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text("选中模型：${preferencesManager.selectedModel}", fontSize = 12.sp)
                Text("视觉模型：${preferencesManager.visionModel}", fontSize = 12.sp)
            }

            // ===== 一键自检（只读） =====
            SectionCard("一键自检", "只做只读检查，不写库、不发通知") {
                TestButton("运行全部只读检查", busy) {
                    runBusy("一键自检") {
                        buildString {
                            append(mark(preferencesManager.apiKey.isNotBlank(), "API Key 已配置"))
                            append(mark(reminderScheduler.canPostNotifications(), "通知权限已授予"))
                            append(mark(reminderScheduler.canScheduleExact(), "精确闹钟权限", "未授权将降级为不精确闹钟"))
                            append(
                                if (reminderScheduler.isIgnoringBatteryOptimizations()) "✓ 已加入电池优化白名单\n"
                                else "⚠ 未加入电池优化白名单（休眠时提醒可能延迟）\n"
                            )
                            append(mark(ReminderNotifications.channelExists(context), "通知渠道已创建"))
                            append("ℹ 提醒总开关：${if (preferencesManager.reminderEnabled) "开" else "关"}\n")
                            val db = runCatching { itemRepository.activeAll().size }
                            append(
                                db.fold(
                                    onSuccess = { "✓ 数据库可读，有效事项 $it 条\n" },
                                    onFailure = { "✗ 数据库读取失败：${it.message}\n" }
                                )
                            )
                            append("ℹ 待调度提醒事项：${reminderScheduler.needingReminderCount()} 条\n")
                        }.trimEnd()
                    }
                }
            }

            // ===== 系统权限状态 =====
            SectionCard("系统权限与调度状态") {
                Text(
                    statusText,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { coroutineScope.launch { busy = true; refreshStatus(); busy = false } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("刷新状态") }
            }

            // ===== 基础网络 =====
            SectionCard("基础网络连接测试", "测试设备是否能访问外网") {
                OutlinedTextField(
                    value = testUrl,
                    onValueChange = { testUrl = it },
                    label = { Text("测试 URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                TestButton("测试网络连接", busy) {
                    runBusy("网络连接测试 → $testUrl") {
                        testNetworkConnection(testUrl)
                    }
                }
            }

            // ===== AI API =====
            SectionCard("AI API 连接测试", "发送一条最简请求验证智谱 GLM 是否正常响应（消耗少量 token）") {
                TestButton("测试 AI API", busy) {
                    runBusy("AI API 连接测试") {
                        val response = aiService.sendMessage(
                            apiKey = preferencesManager.apiKey,
                            message = "Hello, please reply with just 'OK'",
                            model = preferencesManager.selectedModel,
                            systemPrompt = "You are a test assistant. Reply with just 'OK'."
                        )
                        if (response.isSuccess) {
                            "✓ AI API 响应正常\n回复：${response.answer}\n" +
                                "Token：总计 ${response.totalTokens} / 输入 ${response.inputTokens} / 输出 ${response.outputTokens}"
                        } else {
                            "✗ AI API 测试失败\n${response.errorMessage}\n排查：Key 是否正确、额度是否充足、先做网络测试"
                        }
                    }
                }
            }

            // ===== 通知展示 =====
            SectionCard("通知展示测试", "立即弹一条测试通知，验证铃声/震动/横幅与两个动作按钮") {
                TestButton("发送测试通知", busy) {
                    runBusy("发送测试通知") {
                        ReminderNotifications.showTestNotification(context, reminderScheduler)
                        if (reminderScheduler.canPostNotifications()) {
                            "✓ 测试通知已发送，请下拉通知栏查看；可点「标记完成」「延后10分钟」验证按钮"
                        } else {
                            "⚠ 已调用通知接口但系统通知权限未开启，通知会被系统丢弃；请点下方按钮去开启"
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(reminderScheduler.notificationSettingsIntent())
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开系统通知设置") }
            }

            // ===== 真实闹钟 =====
            SectionCard(
                "真实闹钟链路测试",
                "会创建【测试】闹钟事项并注册真实系统闹钟，到点弹出的就是正式同款通知；测完用底部按钮清理"
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf(15, 60, 300).forEach { seconds ->
                        OutlinedButton(
                            onClick = { scheduleTestAlarm(seconds) },
                            enabled = !busy
                        ) { Text(if (seconds < 60) "${seconds}秒" else "${seconds / 60}分钟") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customSeconds,
                    onValueChange = { customSeconds = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("自定义延迟（秒）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                TestButton("按自定义秒数安排闹钟", busy) {
                    val seconds = customSeconds.toIntOrNull()
                    if (seconds == null || seconds <= 0) {
                        appendLog("✗ 请输入大于 0 的秒数")
                    } else {
                        scheduleTestAlarm(seconds)
                    }
                }
            }

            // ===== 调度推算 =====
            SectionCard("重复调度推算验证", "纯计算不写库：从锚点推算未来 5 次触发时刻，可验证月底不漂移") {
                Text("重复规则", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    recurrenceOptions.forEach { (label, value) ->
                        FilterChip(
                            selected = recurrenceSel == value,
                            onClick = { recurrenceSel = value },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = anchorDate,
                    onValueChange = { anchorDate = it },
                    label = { Text("锚点日期 yyyy-MM-dd") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = anchorTime,
                    onValueChange = { anchorTime = it },
                    label = { Text("锚点时间 HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = leadMinutesInput,
                    onValueChange = { leadMinutesInput = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("提前提醒分钟数（0=准时）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                TestButton("推算未来 5 次", busy) {
                    runBusy("调度推算：$recurrenceSel，锚点 $anchorDate $anchorTime") {
                        val lead = leadMinutesInput.toIntOrNull()
                        if (lead == null || lead < 0) return@runBusy "✗ 提前分钟数不合法"
                        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                            .apply { isLenient = false }
                        runCatching { parser.parse("${anchorDate.trim()} ${anchorTime.trim()}") }
                            .getOrNull() ?: return@runBusy "✗ 锚点日期/时间格式不合法"
                        val item = ItemEntity(
                            type = ItemType.SCHEDULE,
                            title = "推算用临时事项",
                            itemDate = anchorDate.trim(),
                            itemTime = anchorTime.trim(),
                            recurrence = recurrenceSel,
                            reminderEnabled = true,
                            remindMinutes = lead
                        )
                        val triggers = reminderScheduler.futureTriggers(item, 5)
                        if (triggers.isEmpty()) {
                            "✗ 没有未来触发时刻（锚点已过期或超出 10 年推算上限）"
                        } else {
                            val displayFmt = SimpleDateFormat("yyyy年M月d日 HH:mm EEEE", Locale.CHINA)
                            "未来 ${triggers.size} 次触发（提前 $lead 分钟）：\n" +
                                triggers.mapIndexed { i, t -> "${i + 1}. ${displayFmt.format(Date(t))}" }
                                    .joinToString("\n")
                        }
                    }
                }
            }

            // ===== 数据库 CRUD =====
            SectionCard("数据库 CRUD 链路测试", "走正式仓库：插入（含挂闹钟）→ 查询 → 改期 → 完成 → 删除，全程验证") {
                TestButton("运行 CRUD 全流程", busy) {
                    runBusy("数据库 CRUD 链路测试") {
                        val sb = StringBuilder()
                        val cal = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }
                        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
                        val base = ItemEntity(
                            type = ItemType.TODO,
                            title = "${ItemRepository.TEST_PREFIX}CRUD流程",
                            itemDate = dateFmt.format(cal.time),
                            itemTime = timeFmt.format(cal.time),
                            reminderEnabled = true,
                            remindMinutes = 0,
                            source = Source.MANUAL
                        )
                        val id = itemRepository.add(base)
                        val inserted = itemRepository.getById(id)
                        sb.appendLine(if (inserted != null) "✓ 1.插入成功 id=$id（同时注册了闹钟）" else "✗ 1.插入后查不到")

                        cal.add(Calendar.HOUR_OF_DAY, 1)
                        itemRepository.update(
                            inserted!!.copy(
                                title = "${ItemRepository.TEST_PREFIX}CRUD流程-已改期",
                                itemTime = timeFmt.format(cal.time)
                            )
                        )
                        val updated = itemRepository.getById(id)
                        sb.appendLine(
                            if (updated?.title?.endsWith("已改期") == true) "✓ 2.改期成功，新时间 ${updated.itemTime}（旧闹钟已取消重排）"
                            else "✗ 2.改期校验失败"
                        )

                        itemRepository.complete(id)
                        val done = itemRepository.getById(id)
                        sb.appendLine(if (done?.status == Status.DONE) "✓ 3.标记完成成功，已进归档（闹钟与通知已撤销）" else "✗ 3.完成状态异常")

                        itemRepository.delete(id)
                        sb.appendLine(if (itemRepository.getById(id) == null) "✓ 4.删除成功" else "✗ 4.删除后仍可查到")
                        sb.toString().trimEnd()
                    }
                }
            }

            // ===== 全量闹钟管理 =====
            SectionCard("全量闹钟管理", "回前台会自动重排；这里提供手动入口") {
                TestButton("立即全量重排", busy) {
                    runBusy("全量重排") {
                        reminderScheduler.rescheduleAll()
                        "✓ 已按库内当前数据全量重排，待调度事项 ${reminderScheduler.needingReminderCount()} 条"
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        confirmRequest = "将撤销当前所有事项的系统闹钟和在用通知（不删除数据），之后可用「立即全量重排」恢复。确认执行？" to {
                            runBusy("撤销全部闹钟") {
                                reminderScheduler.cancelAll()
                                "✓ 已撤销全部闹钟与通知（数据未动，可重排恢复）"
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("撤销全部闹钟") }
            }

            // ===== AI 工具链路 =====
            SectionCard(
                "AI 工具链路测试",
                "真实走一遍 Function Calling：会消耗 token 并真实创建一条【测试】事项（强制免确认）"
            ) {
                TestButton("发送：建一条明天下午3点的测试事项", busy) {
                    runBusy("AI 工具链路测试") {
                        if (preferencesManager.apiKey.isBlank()) {
                            "✗ API Key 未配置，无法测试"
                        } else {
                            val outcome = assistantAgent.send(
                                apiKey = preferencesManager.apiKey,
                                textModel = preferencesManager.selectedModel,
                                visionModel = preferencesManager.visionModel,
                                userText = "帮我建一个明天下午3点的【测试】AI链路事项，准时提醒我。",
                                imageBase64 = null,
                                imageBytes = null,
                                confirmCreate = false,
                                confirmMutation = false
                            )
                            when (outcome) {
                                is AssistantAgent.Outcome.Reply ->
                                    "✓ 工具链路跑通，AI 回复：\n${outcome.text}"
                                is AssistantAgent.Outcome.ConfirmActions ->
                                    "⚠ 返回了确认卡片（测试强制免确认，不应出现），请人工确认后重试"
                                is AssistantAgent.Outcome.Failure ->
                                    "✗ AI 链路失败：${outcome.message}"
                            }
                        }
                    }
                }
            }

            // ===== TTS =====
            SectionCard("TTS 语音播报测试", "使用系统 TTS 引擎，不受对话页语音开关影响") {
                OutlinedTextField(
                    value = ttsText,
                    onValueChange = { ttsText = it },
                    label = { Text("测试文本") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val started = speaker.speak(ttsText) { success ->
                                appendLog(if (success) "✓ TTS 朗读完成" else "✗ TTS 朗读过程中出错")
                            }
                            appendLog(if (started) "▶ TTS 已开始/排队朗读" else "✗ TTS 引擎不可用（无可用引擎或语言数据缺失）")
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("播放") }
                    OutlinedButton(
                        onClick = { speaker.stop(); appendLog("■ TTS 已停止") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("停止") }
                }
            }

            // ===== 闹钟触发记录 =====
            SectionCard("闹钟触发记录", "由系统在收到闹钟/通知按钮广播时写入，不连 logcat 也能验证；最多保留 100 条") {
                Text(
                    eventText.ifBlank { "（暂无记录）" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { refreshEvents() },
                        modifier = Modifier.weight(1f)
                    ) { Text("刷新记录") }
                    OutlinedButton(
                        onClick = {
                            ReminderEventLog.clear(context)
                            refreshEvents()
                            appendLog("已清空闹钟触发记录")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("清空记录") }
                }
            }

            // ===== 事项操作审计日志 =====
            SectionCard("事项操作日志", "记录手动与 AI 对事项的增改删，最近 100 条；清空只清日志不影响事项") {
                if (auditLogs.isEmpty()) {
                    Text(
                        "（暂无操作记录）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    auditLogs.forEach { log ->
                        Text(
                            text = buildString {
                                append(auditFmt.format(Date(log.createdAt)))
                                append("  ")
                                append(auditActorLabel(log.actor))
                                append("·")
                                append(auditActionLabel(log.action))
                                log.summary?.takeIf { it.isNotBlank() }?.let { append("  $it") }
                                log.detail?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        confirmRequest = "将清空全部事项操作日志（不影响事项数据），确认？" to {
                            runBusy("清空事项操作日志") {
                                auditRepository.clear()
                                "✓ 已清空事项操作日志"
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清空操作日志") }
            }

            // ===== 测试数据清理 =====
            SectionCard("测试数据清理", "删除所有标题以【测试】开头的事项（含已归档），并取消其闹钟与通知") {
                OutlinedButton(
                    onClick = {
                        confirmRequest = "将删除全部【测试】事项并取消相关闹钟，此操作不可恢复。确认？" to {
                            runBusy("清理测试数据") {
                                val count = itemRepository.deleteTestItems()
                                refreshStatus()
                                "✓ 已清理 $count 条测试事项"
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("一键清理全部测试事项") }
            }

            // ===== 运行日志 =====
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("运行日志", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = logLines.joinToString("\n").ifBlank { "点击任意测试按钮，结果会汇总到这里" },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { logLines = emptyList() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("清空日志") }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    confirmRequest?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirmRequest = null },
            title = { Text("请确认") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRequest = null
                    action()
                }) { Text("确认执行") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRequest = null }) { Text("取消") }
            }
        )
    }
}

/** 测试卡片：统一标题、说明与内容容器 */
@Composable
private fun SectionCard(
    title: String,
    description: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (description != null) {
                Text(
                    description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** 带忙碌指示的全宽主按钮 */
@Composable
private fun TestButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 8.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        }
        Text(text)
    }
}

/** 「名称：值 ✓/✗」状态行 */
@Composable
private fun StatusLine(name: String, ok: Boolean) {
    Text(
        "$name：${if (ok) "是 ✓" else "否 ✗"}",
        fontSize = 12.sp
    )
}

/** 自检结果行：通过 ✓ / 失败 ✗（失败时附说明） */
private fun mark(ok: Boolean, name: String, failHint: String? = null): String =
    if (ok) "✓ $name\n" else "✗ $name${if (failHint != null) "（$failHint）" else ""}\n"

/** 审计日志：操作来源中文标签 */
private fun auditActorLabel(actor: String): String = when (actor) {
    "MANUAL" -> "手动"
    "AI" -> "AI"
    "SYSTEM" -> "系统"
    else -> actor
}

/** 审计日志：动作中文标签 */
private fun auditActionLabel(action: String): String = when (action) {
    "CREATE" -> "新建"
    "UPDATE" -> "修改"
    "COMPLETE" -> "完成"
    "CANCEL" -> "取消"
    "DELETE" -> "删除"
    "RESTORE" -> "恢复"
    "CLEAR" -> "清空"
    "IMPORT" -> "导入"
    else -> action
}

private const val MAX_LOG_LINES = 300

/**
 * 基础网络连通性测试
 * 对指定 URL 发起 GET 请求，返回状态码、耗时、响应大小和响应头
 */
suspend fun testNetworkConnection(url: String): String = withContext(Dispatchers.IO) {
    return@withContext try {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val startTime = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val endTime = System.currentTimeMillis()
            val responseBody = response.body?.string() ?: ""
            """
                ✓ 网络连接正常

                目标 URL: $url
                状态码: ${response.code}
                响应时间: ${endTime - startTime}ms
                响应大小: ${responseBody.length} 字节

                结论: 设备网络正常；若 AI API 测试失败，问题在 API 配置或服务端。
            """.trimIndent()
        }
    } catch (e: Exception) {
        """
            ✗ 网络连接失败

            目标 URL: $url
            错误类型: ${e::class.java.simpleName}
            错误信息: ${e.message ?: "未知错误"}

            排查: 检查网络连接 / 防火墙 / 更换 URL 重试
        """.trimIndent()
    }
}
