package org.example.myapplication.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.util.Base64
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.ChatMessageEntity
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.local.db.MemoryCategory
import org.example.myapplication.data.local.db.MemoryEntity
import org.example.myapplication.data.local.db.Priority
import org.example.myapplication.data.local.db.Recurrence
import org.example.myapplication.data.local.db.Source
import org.example.myapplication.data.local.db.Status
import org.example.myapplication.data.repository.AuditRepository
import org.example.myapplication.data.repository.ChatRepository
import org.example.myapplication.data.repository.ItemRepository
import org.example.myapplication.data.repository.MemoryRepository
import org.example.myapplication.reminder.ExactAlarmGuideDialog
import org.example.myapplication.reminder.ReminderScheduler
import org.example.myapplication.reminder.ScheduleStatusNotifications
import org.example.myapplication.util.SystemTtsSpeaker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用设置页面
 * 分为四个分组：AI 模型配置、提醒与权限、数据与隐私、关于
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferencesManager: PreferencesManager,
    itemRepository: ItemRepository,
    chatRepository: ChatRepository,
    memoryRepository: MemoryRepository,
    auditRepository: AuditRepository,
    reminderScheduler: ReminderScheduler,
    /** 连续点击"关于"7 次后打开隐藏的 Debug 页 */
    onOpenDebug: () -> Unit
) {
    // 以下状态从本地配置初始化，修改时同步写回 PreferencesManager
    var apiKey by remember { mutableStateOf(preferencesManager.apiKey) }
    // API Key 默认圆点隐藏，点眼睛才短暂显示明文
    var showApiKey by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    var selectedModel by remember { mutableStateOf(preferencesManager.selectedModel) }
    var visionModel by remember { mutableStateOf(preferencesManager.visionModel) }
    var thinkingEnabled by remember { mutableStateOf(preferencesManager.thinkingEnabled) }
    var reminderEnabled by remember { mutableStateOf(preferencesManager.reminderEnabled) }
    var statusNotifEnabled by remember {
        mutableStateOf(preferencesManager.statusNotificationEnabled)
    } // 日程常驻通知开关
    var confirmBeforeSave by remember { mutableStateOf(preferencesManager.confirmBeforeSave) }
    var confirmBeforeMutation by remember { mutableStateOf(preferencesManager.confirmBeforeMutation) }
    var ttsEnabled by remember { mutableStateOf(preferencesManager.ttsEnabled) }
    var bufferMinutes by remember {
        mutableStateOf(preferencesManager.scheduleBufferMinutes)
    } // 相邻日程缓冲分钟数
    var showBufferDialog by remember { mutableStateOf(false) } // 缓冲时间选择弹窗
    var showSavedHint by remember { mutableStateOf(false) }  // 是否显示"已保存"提示
    var showDataDialog by remember { mutableStateOf(false) } // 是否打开本地数据管理弹窗
    var showMemoryDialog by remember { mutableStateOf(false) } // 是否打开记忆管理弹窗
    var showLegalHub by remember { mutableStateOf(false) } // 是否打开"隐私"二级页
    // 非 null 时全屏展示隐私政策 / 用户协议（WebView 加载 assets 本地 HTML）
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    var showRevokeConfirm by remember { mutableStateOf(false) } // 隐私页"撤回授权"二次确认
    var showTtsTestDialog by remember { mutableStateOf(false) } // 是否打开语音引擎自检弹窗
    var showExactAlarmGuide by remember { mutableStateOf(false) } // 精确闹钟授权引导
    var showKeepAliveDialog by remember { mutableStateOf(false) } // 国产 ROM 后台保活引导
    // 电池白名单状态：从系统设置返回时刷新
    var batteryWhitelisted by remember {
        mutableStateOf(reminderScheduler.isIgnoringBatteryOptimizations())
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Android 13+ 通知权限请求；结果不影响开关本身，未授权仅通知不展示
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 回到前台时刷新电池白名单状态（用户可能刚在系统设置中改过）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryWhitelisted = reminderScheduler.isIgnoringBatteryOptimizations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 打开电池优化白名单：优先直弹授权框，部分 ROM 禁用该 action 时回退设置列表 */
    fun openBatteryOptimizationSettings() {
        val direct = runCatching {
            context.startActivity(reminderScheduler.requestIgnoreBatteryOptimizationsIntent())
        }
        if (direct.isFailure) {
            runCatching { context.startActivity(reminderScheduler.batteryOptimizationListIntent()) }
        }
    }

    if (showExactAlarmGuide) {
        ExactAlarmGuideDialog(
            scheduler = reminderScheduler,
            onDismiss = { showExactAlarmGuide = false }
        )
    }

    // 国产 ROM（OPPO ColorOS 等）后台保活引导：息屏/划掉后不提醒时按清单配置
    if (showKeepAliveDialog) {
        val keepAliveTips = listOf(
            "允许本应用「自启动 / 关联启动」",
            "耗电管理设为「允许后台活动」，关闭智能省电/应用速冻",
            "最近任务列表里把本应用下拉加锁，避免被一键清理",
            "通知权限保持允许，勿关闭通知渠道"
        )
        AlertDialog(
            onDismissRequest = { showKeepAliveDialog = false },
            title = { Text("后台提醒保活") },
            text = {
                Column {
                    Text(
                        text = "息屏或划掉应用后收不到提醒，多为国产手机冻结后台所致，请逐项设置：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    keepAliveTips.forEachIndexed { index, tip ->
                        Text(
                            text = "${index + 1}. $tip",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                // 优先直达厂商自启动页；组件缺失（ROM 版本差异）时回退应用详情页
                TextButton(onClick = {
                    val target = reminderScheduler.vendorAutoStartIntent()
                        ?: reminderScheduler.appDetailsSettingsIntent()
                    runCatching { context.startActivity(target) }
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { showKeepAliveDialog = false }) { Text("知道了") }
            }
        )
    }

    if (showTtsTestDialog) {
        TtsTestDialog(
            preferencesManager = preferencesManager,
            onDismiss = { showTtsTestDialog = false }
        )
    }

    // 日程缓冲时间四档：0/15/30/60 分钟
    if (showBufferDialog) {
        val options = listOf(0 to "关闭（不检测）", 15 to "15 分钟", 30 to "30 分钟（推荐）", 60 to "60 分钟")
        AlertDialog(
            onDismissRequest = { showBufferDialog = false },
            title = { Text("日程缓冲时间") },
            text = {
                Column {
                    Text(
                        text = "两个日程之间的最短间隔，不足时给出冲突提示（不阻止保存）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    options.forEach { (value, label) ->
                        Text(
                            text = if (value == bufferMinutes) "● $label" else "○ $label",
                            fontSize = 15.sp,
                            fontWeight = if (value == bufferMinutes) FontWeight.SemiBold
                            else FontWeight.Normal,
                            color = if (value == bufferMinutes) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    bufferMinutes = value
                                    preferencesManager.scheduleBufferMinutes = value
                                    showSavedHint = true
                                    showBufferDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBufferDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDataDialog) {
        DataManagementDialog(
            itemRepository = itemRepository,
            chatRepository = chatRepository,
            memoryRepository = memoryRepository,
            onDismiss = { showDataDialog = false }
        )
    }

    if (showMemoryDialog) {
        MemoryManagementDialog(
            memoryRepository = memoryRepository,
            onDismiss = { showMemoryDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "设置",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        if (showSavedHint) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "✓ 已保存",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // AI 模型配置
            SettingsSection(title = "AI 模型配置") {
                SettingsItem(
                    icon = Icons.Default.Psychology,
                    title = "AI 模型",
                    subtitle = "智谱 GLM 系列",
                    onClick = { }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "API Key",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            preferencesManager.apiKey = it  // 实时保存
                            showSavedHint = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴或输入你的 API Key", fontSize = 13.sp) },
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        // 默认圆点掩码，输入的字符立即隐藏
                        visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 一键从剪贴板粘贴
                                IconButton(
                                    onClick = {
                                        clipboardManager.getText()?.text
                                            ?.trim()?.takeIf { it.isNotEmpty() }
                                            ?.let { pasted ->
                                                apiKey = pasted
                                                preferencesManager.apiKey = pasted
                                                showSavedHint = true
                                                showApiKey = false
                                            }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "粘贴 API Key",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                // 明文/圆点切换
                                if (apiKey.isNotEmpty()) {
                                    IconButton(
                                        onClick = { showApiKey = !showApiKey },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showApiKey) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                            contentDescription = if (showApiKey) "隐藏 API Key" else "显示 API Key",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    // 清除已保存的 Key
                                    IconButton(
                                        onClick = {
                                            apiKey = ""
                                            preferencesManager.apiKey = ""
                                            showApiKey = false
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "清除 API Key",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Key 仅本地加密保存，不会上传任何服务器",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 模型选择 Chip：点击后写入对应智谱模型 ID
                        ModelChip(
                            text = "AirX 极速",
                            selected = selectedModel == "glm-4.5-airx",
                            onClick = {
                                selectedModel = "glm-4.5-airx"
                                preferencesManager.selectedModel = "glm-4.5-airx"
                                showSavedHint = true
                            }
                        )
                        ModelChip(
                            text = "GLM-4.6",
                            selected = selectedModel == "glm-4.6",
                            onClick = {
                                selectedModel = "glm-4.6"
                                preferencesManager.selectedModel = "glm-4.6"
                                showSavedHint = true
                            }
                        )
                        ModelChip(
                            text = "GLM-5.3",
                            selected = selectedModel == "glm-5.3",
                            onClick = {
                                selectedModel = "glm-5.3"
                                preferencesManager.selectedModel = "glm-5.3"
                                showSavedHint = true
                            }
                        )
                        ModelChip(
                            text = "4-Flash 免费",
                            selected = selectedModel == "glm-4-flash",
                            onClick = {
                                selectedModel = "glm-4-flash"
                                preferencesManager.selectedModel = "glm-4-flash"
                                showSavedHint = true
                            }
                        )
                    }

                    // 视觉模型：聊天页发送图片时自动使用（纯文本消息仍用上方文本模型）
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "视觉模型（发送图片时使用）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModelChip(
                            text = "4.6V-Flash",
                            selected = visionModel == "glm-4.6v-flash",
                            onClick = {
                                visionModel = "glm-4.6v-flash"
                                preferencesManager.visionModel = "glm-4.6v-flash"
                                showSavedHint = true
                            }
                        )
                        ModelChip(
                            text = "5.3-FlashX 极速",
                            selected = visionModel == "glm-5.3-flashx",
                            onClick = {
                                visionModel = "glm-5.3-flashx"
                                preferencesManager.visionModel = "glm-5.3-flashx"
                                showSavedHint = true
                            }
                        )
                        ModelChip(
                            text = "5.3-Flash",
                            selected = visionModel == "glm-5.3-flash",
                            onClick = {
                                visionModel = "glm-5.3-flash"
                                preferencesManager.visionModel = "glm-5.3-flash"
                                showSavedHint = true
                            }
                        )
                        ModelChip(
                            text = "4.6V",
                            selected = visionModel == "glm-4.6v",
                            onClick = {
                                visionModel = "glm-4.6v"
                                preferencesManager.visionModel = "glm-4.6v"
                                showSavedHint = true
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "4.6V-Flash 免费（默认）；5.3-FlashX 极速约 200 tokens/s；5.3-Flash 为最新原生多模态；4.6V 为收费旗舰",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "当前使用智谱 BigModel API（OpenAI 兼容接口）",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Divider(modifier = Modifier.padding(start = 56.dp))
                // 深度思考开关：默认关闭提升响应速度；GLM-4.5 以上文本模型及全系视觉模型生效
                SettingsItem(
                    icon = Icons.Default.Psychology,
                    title = "深度思考",
                    subtitle = if (thinkingEnabled) {
                        "已开启：复杂排期、评审与识图分析更严谨，回复会变慢"
                    } else {
                        "已关闭（默认）：日常对话和识图响应更快；4.5 以上文本模型与视觉模型生效，4-Flash 不支持"
                    },
                    trailing = {
                        Switch(
                            checked = thinkingEnabled,
                            onCheckedChange = { enable ->
                                thinkingEnabled = enable
                                preferencesManager.thinkingEnabled = enable
                                showSavedHint = true
                            }
                        )
                    },
                    onClick = {
                        thinkingEnabled = !thinkingEnabled
                        preferencesManager.thinkingEnabled = thinkingEnabled
                        showSavedHint = true
                    }
                )
            }

            // 提醒与权限
            SettingsSection(title = "提醒与权限") {
                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = "事项提醒",
                    subtitle = "有具体时间的事项到达时通知；全天事项在对话中提醒",
                    trailing = {
                        // 总开关：关→取消全部闹钟并撤销在用通知；开→补齐权限后全量重调度
                        Switch(
                            checked = reminderEnabled,
                            onCheckedChange = { enable ->
                                reminderEnabled = enable
                                preferencesManager.reminderEnabled = enable
                                showSavedHint = true
                                if (enable) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        !reminderScheduler.canPostNotifications()
                                    ) {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }
                                    if (!reminderScheduler.canScheduleExact()) {
                                        showExactAlarmGuide = true
                                    }
                                    scope.launch { reminderScheduler.rescheduleAll() }
                                } else {
                                    scope.launch { reminderScheduler.cancelAll() }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.PushPin,
                    title = "日程常驻通知",
                    subtitle = "通知栏常驻显示进行中或下一项日程；无安排时显示今日无安排",
                    trailing = {
                        // 关→撤销常驻通知与状态刷新闹钟；开→立即按当前日程发布
                        Switch(
                            checked = statusNotifEnabled,
                            onCheckedChange = { enable ->
                                statusNotifEnabled = enable
                                preferencesManager.statusNotificationEnabled = enable
                                showSavedHint = true
                                scope.launch {
                                    ScheduleStatusNotifications.refresh(context)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.Settings,
                    title = "日程缓冲时间",
                    subtitle = if (bufferMinutes == 0)
                        "不检测相邻日程缓冲"
                    else
                        "相邻日程间隔不足 $bufferMinutes 分钟时提示",
                    onClick = { showBufferDialog = true },
                    trailing = {
                        Text(
                            text = if (bufferMinutes == 0) "关闭" else "${bufferMinutes} 分钟",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.CheckCircle,
                    title = "AI 记录确认",
                    subtitle = "AI 识别出的事项，保存前先弹出确认卡片",
                    trailing = {
                        // AI 写入二次确认开关：关闭后识别事项自动入库
                        Switch(
                            checked = confirmBeforeSave,
                            onCheckedChange = {
                                confirmBeforeSave = it
                                preferencesManager.confirmBeforeSave = it
                                showSavedHint = true
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.Edit,
                    title = "AI 修改确认",
                    subtitle = "AI 修改或取消事项前先确认；删除操作永远需要确认",
                    trailing = {
                        Switch(
                            checked = confirmBeforeMutation,
                            onCheckedChange = {
                                confirmBeforeMutation = it
                                preferencesManager.confirmBeforeMutation = it
                                showSavedHint = true
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = "语音播报",
                    subtitle = "自动朗读 AI 回复；点击可测试并切换语音引擎",
                    onClick = { showTtsTestDialog = true },
                    trailing = {
                        Switch(
                            checked = ttsEnabled,
                            onCheckedChange = {
                                ttsEnabled = it
                                preferencesManager.ttsEnabled = it
                                showSavedHint = true
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "电池后台白名单",
                    subtitle = if (batteryWhitelisted) "已加入，提醒更可靠"
                    else "未配置，可能导致提醒延迟",
                    onClick = { openBatteryOptimizationSettings() }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.Lock,
                    title = "后台保活设置",
                    subtitle = "国产手机需允许自启动/后台活动，否则息屏或划掉后不提醒",
                    onClick = { showKeepAliveDialog = true }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = "系统权限管理",
                    subtitle = "通知、精确闹钟权限开关",
                    onClick = {
                        runCatching {
                            context.startActivity(reminderScheduler.notificationSettingsIntent())
                        }
                    }
                )
            }

            // 数据与隐私
            SettingsSection(title = "数据与隐私") {
                SettingsItem(
                    icon = Icons.Default.Psychology,
                    title = "记忆管理",
                    subtitle = "查看、编辑、删除 AI 记住的画像、人脉、纪念日和笔记",
                    onClick = { showMemoryDialog = true }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = "本地数据管理",
                    subtitle = "统计、导入/导出 JSON 备份、清除数据",
                    onClick = { showDataDialog = true }
                )
                Divider(modifier = Modifier.padding(start = 56.dp))
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = "隐私",
                    subtitle = "隐私政策与用户协议",
                    onClick = { showLegalHub = true }
                )
            }

            // 关于（连续点击 7 次为隐藏 Debug 页入口）
            SettingsSection(title = "关于") {
                // 连击计数与上次点击时间；间隔超过 1.5 秒计数清零
                var aboutTapCount by remember { mutableStateOf(0) }
                var aboutLastTapAt by remember { mutableStateOf(0L) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val now = System.currentTimeMillis()
                            if (now - aboutLastTapAt > 1500L) aboutTapCount = 0
                            aboutLastTapAt = now
                            aboutTapCount += 1
                            when {
                                aboutTapCount >= 7 -> {
                                    aboutTapCount = 0
                                    Toast.makeText(context, "已进入调试模式", Toast.LENGTH_SHORT).show()
                                    onOpenDebug()
                                }
                                aboutTapCount >= 3 ->
                                    Toast.makeText(context, "再点${7 - aboutTapCount}次进入调试", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "PA",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "PersonalAgent",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "版本 ${org.example.myapplication.BuildConfig.VERSION_NAME} · 本地AI记忆型私人助理",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // "隐私"二级页（调用点父级为 Box，覆盖整个设置页与底部导航）
    if (showLegalHub) {
        LegalHubScreen(
            onBack = { showLegalHub = false },
            onOpenDocument = { legalDocument = it },
            onRevoke = { showRevokeConfirm = true }
        )
    }

    // 撤回授权二次确认：确认后清空全部本地数据（含 API Key、协议标记）并退出应用
    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text("撤回授权", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "撤回后将删除本应用的全部本地数据，包括所有事项、聊天记录、长期记忆、" +
                        "审计日志、API Key 与全部设置，且无法恢复；应用将退出，再次启动需重新同意协议。\n\n" +
                        "确定撤回授权吗？",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRevokeConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            // 先撤销常驻通知与所有提醒闹钟
                            preferencesManager.statusNotificationEnabled = false
                            ScheduleStatusNotifications.refresh(context)
                            itemRepository.clear() // 内部取消全部提醒闹钟
                            chatRepository.clear()
                            memoryRepository.clear()
                            auditRepository.clear() // 最后清审计（item.clear 会追加一条日志）
                            preferencesManager.clear() // 含协议同意标记与 API Key
                        }
                        (context as? android.app.Activity)?.finishAffinity()
                    }
                }) { Text("撤回并退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) { Text("取消") }
            }
        )
    }

    // 全屏法律文档页：在隐私页之上再覆盖一层，返回时回到隐私页
    legalDocument?.let { doc ->
        LegalDocumentScreen(
            document = doc,
            onBack = { legalDocument = null }
        )
    }
}

/**
 * 设置分组容器
 * 顶部显示分组标题（主题色），下方卡片包裹该分组的所有设置项
 *
 * @param title   分组标题
 * @param content 分组内容
 */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            content()
        }
    }
}

/**
 * 通用设置项
 * 左侧圆形图标，中间标题+副标题，右侧为自定义尾部组件或默认箭头
 *
 * @param icon     左侧图标
 * @param title    标题
 * @param subtitle 副标题（为空则不显示）
 * @param trailing 右侧自定义内容（如开关），为空时默认显示箭头
 * @param onClick  点击回调
 */
@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * 模型选择 Chip（小型可点击标签）
 * 选中时填充主题色，未选中时使用 surfaceVariant 灰色
 *
 * @param text     显示文字
 * @param selected 是否选中
 * @param onClick  点击回调
 */
@Composable
fun ModelChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) Color.White
        else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** 本地数据统计快照 */
private data class DataStats(
    val itemCount: Int,
    val activeCount: Int,
    val memoryCount: Int,
    val chatCount: Int,
    val dbSizeBytes: Long
)

/**
 * 本地数据管理弹窗：统计展示、导出 JSON 备份、清除聊天/全部数据
 * 清除操作均需二次确认；清除全部只清数据库，API Key 与偏好设置保留
 */
@Composable
private fun DataManagementDialog(
    itemRepository: ItemRepository,
    chatRepository: ChatRepository,
    memoryRepository: MemoryRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<DataStats?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // 已解析、等待用户二次确认的导入（文件 Uri + 备份内容）
    var pendingImport by remember { mutableStateOf<Pair<Uri, BackupData>?>(null) }
    // 0 = 待确认清除聊天；1 = 待确认清除全部
    var confirmTarget by remember { mutableStateOf<Int?>(null) }

    // 选择备份文件：选完先解析，解析成功弹二次确认，失败给 Toast，不写任何数据
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val json = context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().decodeToString()
                        } ?: throw IllegalStateException("无法读取所选文件")
                        parseBackupJson(json)
                    }
                }
                result.onSuccess { pendingImport = uri to it }
                    .onFailure {
                        Toast.makeText(
                            context,
                            "备份解析失败：${it.message ?: "文件格式不正确"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }

    fun refresh() {
        scope.launch {
            stats = withContext(Dispatchers.IO) {
                DataStats(
                    itemCount = itemRepository.count(),
                    activeCount = itemRepository.activeCount(),
                    memoryCount = memoryRepository.count(),
                    chatCount = chatRepository.count(),
                    dbSizeBytes = context.getDatabasePath("personal_agent.db")?.length() ?: 0L
                )
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun exportBackup() {
        if (exporting) return
        exporting = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val root = JSONObject()
                        .put("app", "PersonalAgent")
                        .put("version", 1)
                        .put("exported_at", System.currentTimeMillis())

                    val itemsArr = JSONArray()
                    itemRepository.getAll().forEach { e ->
                        itemsArr.put(JSONObject().apply {
                            put("id", e.id)
                            put("type", e.type)
                            put("title", e.title)
                            put("description", e.description)
                            put("itemDate", e.itemDate)
                            put("itemTime", e.itemTime)
                            put("isAllDay", e.isAllDay)
                            put("location", e.location)
                            put("priority", e.priority)
                            put("recurrence", e.recurrence)
                            put("reminderEnabled", e.reminderEnabled)
                            put("remindMinutes", e.remindMinutes)
                            put("status", e.status)
                            put("source", e.source)
                            e.sourceImage?.let { put("sourceImage", Base64.encodeToString(it, Base64.NO_WRAP)) }
                            put("createdAt", e.createdAt)
                            put("updatedAt", e.updatedAt)
                            put("completedAt", e.completedAt)
                        })
                    }

                    val chatArr = JSONArray()
                    chatRepository.getAll().forEach { e ->
                        chatArr.put(JSONObject().apply {
                            put("id", e.id)
                            put("role", e.role)
                            put("text", e.text)
                            e.image?.let { put("image", Base64.encodeToString(it, Base64.NO_WRAP)) }
                            put("createdAt", e.createdAt)
                        })
                    }

                    val memoryArr = JSONArray()
                    memoryRepository.getAll().forEach { e ->
                        memoryArr.put(JSONObject().apply {
                            put("id", e.id)
                            put("category", e.category)
                            put("title", e.title)
                            put("content", e.content)
                            put("memoryDate", e.memoryDate)
                            put("createdAt", e.createdAt)
                            put("updatedAt", e.updatedAt)
                        })
                    }

                    root.put("items", itemsArr)
                    root.put("chat_messages", chatArr)
                    root.put("memories", memoryArr)

                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                    val file = File(dir, "personal_agent_backup_$stamp.json")
                    file.writeText(root.toString(2))
                    file
                }
            }
            exporting = false
            result.onSuccess { file ->
                // 通过系统分享面板发出（微信/邮件/文件管理等）
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "导出数据备份"))
            }.onFailure {
                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 确认导入：三表按原 id 合并覆盖，事项闹钟全量重排 */
    fun doImport(uri: Uri, data: BackupData) {
        if (importing) return
        importing = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    itemRepository.importItems(data.items)
                    chatRepository.importAll(data.chats)
                    memoryRepository.importAll(data.memories)
                }
            }
            importing = false
            pendingImport = null
            result.onSuccess {
                refresh()
                Toast.makeText(
                    context,
                    "导入完成：事项 ${data.items.size} 条、消息 ${data.chats.size} 条、记忆 ${data.memories.size} 条",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun doClear(target: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                chatRepository.clear()
                if (target == 1) {
                    itemRepository.clear()
                    memoryRepository.clear()
                }
            }
            confirmTarget = null
            refresh()
            Toast.makeText(
                context,
                if (target == 1) "已清除全部本地数据" else "已清除聊天记录",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地数据管理", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val s = stats
                if (s == null) {
                    Text("统计中…", fontSize = 13.sp)
                } else {
                    StatLine("事项", "${s.activeCount} 个进行中 · 共 ${s.itemCount} 个（含归档）")
                    StatLine("长期记忆", "${s.memoryCount} 条")
                    StatLine("聊天消息", "${s.chatCount} 条")
                    StatLine("数据库大小", formatFileSize(s.dbSizeBytes))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Divider()
                Text(
                    text = "导出包含事项、记忆与聊天记录，图片以 Base64 内嵌在 JSON 中；导入按编号合并，相同编号的现有数据会被覆盖，其余保留",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { exportBackup() },
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (exporting) "正在导出…" else "导出 JSON 备份")
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "application/json",
                                "text/plain",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (importing) "正在导入…" else "导入 JSON 备份")
                }
                TextButton(
                    onClick = { confirmTarget = 0 },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清除聊天记录", color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = { confirmTarget = 1 },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("清除全部数据（保留设置）", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )

    // 清除操作的二次确认
    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = {
                Text(if (target == 1) "清除全部数据？" else "清除聊天记录？")
            },
            text = {
                Text(
                    if (target == 1)
                        "事项、长期记忆与全部聊天记录将被永久删除，且无法恢复。\n\nAPI Key 与偏好设置会保留。"
                    else
                        "全部聊天消息将被永久删除。\n\n事项与长期记忆不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = { doClear(target) }) {
                    Text("确认清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("取消") }
            }
        )
    }

    // 导入前二次确认：展示将写入的数据条数与覆盖规则
    pendingImport?.let { (uri, data) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入备份？") },
            text = {
                Text(
                    "备份中包含：事项 ${data.items.size} 条、聊天消息 ${data.chats.size} 条、" +
                        "长期记忆 ${data.memories.size} 条。\n\n" +
                        "相同编号的现有数据会被备份内容覆盖，其他数据保留；导入后系统提醒会重新调度。"
                )
            },
            confirmButton = {
                TextButton(onClick = { doImport(uri, data) }) {
                    Text("确认导入", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(text = value, fontSize = 13.sp)
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.2f MB".format(bytes / (1024f * 1024f))
}

/** 备份文件解析结果（三表实体，保留备份原 id） */
private data class BackupData(
    val items: List<ItemEntity>,
    val chats: List<ChatMessageEntity>,
    val memories: List<MemoryEntity>
)

/**
 * 导出 JSON 的反向解析（与 DataManagementDialog.exportBackup 的字段一一对应）。
 * 非本应用备份或结构损坏时抛异常，由调用方提示用户；解析阶段不写任何数据。
 */
private fun parseBackupJson(json: String): BackupData {
    val root = JSONObject(json)
    if (root.optString("app") != "PersonalAgent") {
        throw IllegalArgumentException("不是 PersonalAgent 导出的备份文件")
    }
    fun JSONObject.strOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    fun JSONObject.longOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    fun JSONObject.decodeBase64(key: String): ByteArray? = strOrNull(key)?.let {
        runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
    }

    val now = System.currentTimeMillis()

    val items = mutableListOf<ItemEntity>()
    val itemArr = root.optJSONArray("items") ?: JSONArray()
    for (i in 0 until itemArr.length()) {
        val o = itemArr.getJSONObject(i)
        items += ItemEntity(
            id = o.optLong("id"),
            type = o.optString("type", "TODO"),
            title = o.optString("title").ifBlank { "未命名事项" },
            description = o.strOrNull("description"),
            itemDate = o.strOrNull("itemDate"),
            itemTime = o.strOrNull("itemTime"),
            isAllDay = o.optBoolean("isAllDay", false),
            location = o.strOrNull("location"),
            priority = o.optInt("priority", Priority.NORMAL),
            recurrence = o.optString("recurrence", Recurrence.NONE),
            reminderEnabled = o.optBoolean("reminderEnabled", false),
            remindMinutes = o.intOrNull("remindMinutes"),
            status = o.optString("status", Status.ACTIVE),
            source = o.optString("source", Source.MANUAL),
            sourceImage = o.decodeBase64("sourceImage"),
            createdAt = o.optLong("createdAt", now),
            updatedAt = o.optLong("updatedAt", now),
            completedAt = o.longOrNull("completedAt")
        )
    }

    val chats = mutableListOf<ChatMessageEntity>()
    val chatArr = root.optJSONArray("chat_messages") ?: JSONArray()
    for (i in 0 until chatArr.length()) {
        val o = chatArr.getJSONObject(i)
        chats += ChatMessageEntity(
            id = o.optLong("id"),
            role = o.optString("role", "user"),
            text = o.optString("text"),
            image = o.decodeBase64("image"),
            createdAt = o.optLong("createdAt", now)
        )
    }

    val memories = mutableListOf<MemoryEntity>()
    val memoryArr = root.optJSONArray("memories") ?: JSONArray()
    for (i in 0 until memoryArr.length()) {
        val o = memoryArr.getJSONObject(i)
        memories += MemoryEntity(
            id = o.optLong("id"),
            category = o.optString("category", MemoryCategory.NOTE),
            title = o.strOrNull("title"),
            content = o.optString("content"),
            memoryDate = o.strOrNull("memoryDate"),
            createdAt = o.optLong("createdAt", now),
            updatedAt = o.optLong("updatedAt", now)
        )
    }
    return BackupData(items, chats, memories)
}

/** 记忆分类中文标签 */
private fun memoryCategoryLabel(category: String): String = when (category) {
    MemoryCategory.PROFILE -> "画像"
    MemoryCategory.CONTACT -> "人脉"
    MemoryCategory.ANNIVERSARY -> "纪念日"
    MemoryCategory.NOTE -> "笔记"
    else -> "记忆"
}

/**
 * 记忆管理弹窗：列出全部长期记忆，支持单条编辑与删除（清空全部仍在本地数据管理中）。
 * 数据来自 Room Flow，编辑/删除后自动刷新。
 */
@Composable
private fun MemoryManagementDialog(
    memoryRepository: MemoryRepository,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val memories by memoryRepository.observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleting by remember { mutableStateOf<MemoryEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆管理（${memories.size}）", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (memories.isEmpty()) {
                    Text(
                        "还没有记住任何信息；在助理页说「记住……」即可添加。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                memories.forEach { m ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = memoryCategoryLabel(m.category) +
                                        (m.memoryDate?.let { " · $it" } ?: ""),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                m.title?.let {
                                    Text(
                                        text = it,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = m.content,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { editing = m }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "编辑",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { deleting = m }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    editing?.let { m ->
        MemoryEditDialog(
            memory = m,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    withContext(Dispatchers.IO) { memoryRepository.update(updated) }
                }
                editing = null
            }
        )
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条记忆？") },
            text = {
                Text(
                    (m.title?.let { "$it\n" } ?: "") + m.content +
                        (m.memoryDate?.let { "（$it）" } ?: "")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { memoryRepository.delete(m.id) }
                    }
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

/** 记忆编辑弹窗：分类、标题、内容、纪念日日期均可修改，内容不能为空 */
@Composable
private fun MemoryEditDialog(
    memory: MemoryEntity,
    onDismiss: () -> Unit,
    onSave: (MemoryEntity) -> Unit
) {
    var category by remember { mutableStateOf(memory.category) }
    var title by remember { mutableStateOf(memory.title ?: "") }
    var content by remember { mutableStateOf(memory.content) }
    var date by remember { mutableStateOf(memory.memoryDate ?: "") }

    val categoryOptions = listOf(
        "画像" to MemoryCategory.PROFILE,
        "人脉" to MemoryCategory.CONTACT,
        "纪念日" to MemoryCategory.ANNIVERSARY,
        "笔记" to MemoryCategory.NOTE
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑记忆", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categoryOptions.forEach { (label, value) ->
                        ModelChip(
                            text = label,
                            selected = category == value,
                            onClick = { category = value }
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题（可空）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日期（纪念日用，如 09-20，可空）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = {
                    onSave(
                        memory.copy(
                            category = category,
                            title = title.trim().ifBlank { null },
                            content = content.trim(),
                            memoryDate = date.trim().ifBlank { null }
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private const val TTS_TEST_PHRASE = "语音测试。如果你听到了这句话，说明语音引擎工作正常。"

/**
 * 语音引擎自检弹窗：
 * - 播放测试音，显示成功/失败
 * - 列出手机里实际安装的 TTS 引擎，可点击切换（持久化，聊天页随之使用）
 * - 显示媒体音量；引擎列表为空时给出安装指引
 */
@Composable
private fun TtsTestDialog(
    preferencesManager: PreferencesManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var enginePkg by remember { mutableStateOf(preferencesManager.ttsEngine) }
    // 切换引擎包名时重建 speaker；失败会自动回退系统默认引擎
    val speaker = remember(enginePkg) { SystemTtsSpeaker(context, enginePkg) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }

    var status by remember(enginePkg) { mutableStateOf("正在初始化语音引擎…") }
    var refreshTick by remember { mutableStateOf(0) }
    var autoTest by remember { mutableStateOf(false) }

    // 引擎初始化是异步的：短轮询刷新列表；切换引擎后自动播一次测试音
    LaunchedEffect(enginePkg) {
        if (autoTest) {
            status = "已切换引擎，正在播放测试音…"
            val started = speaker.speak(TTS_TEST_PHRASE) { ok ->
                status = if (ok) "✅ 播放完成，该引擎可用" else "❌ 该引擎朗读失败，请换一个引擎试试"
            }
            if (!started) status = "❌ 引擎不可用，请换一个引擎或安装语音数据"
            autoTest = false
        }
        repeat(20) {
            kotlinx.coroutines.delay(150)
            refreshTick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") refreshTick  // 触发重组以刷新引擎列表

    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    val mediaVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

    val engines = speaker.availableEngines()
    val scanning = refreshTick < 20
    // 能看到引擎但默认引擎初始化失败：引导用户直接点选某个引擎
    val needPickEngine = engines.isNotEmpty() && !speaker.isReady && !scanning

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语音引擎自检") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = status,
                    fontSize = 13.sp,
                    color = when {
                        status.startsWith("✅") -> Color(0xFF2E7D32)
                        status.startsWith("❌") -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "媒体音量：$mediaVolume / $maxVolume" +
                        if (mediaVolume == 0) "（当前为静音，请按音量键调大「媒体」音量）" else "",
                    fontSize = 12.sp,
                    color = if (mediaVolume == 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (needPickEngine) {
                    Text(
                        text = "⚠️ 系统默认引擎无法启动，但已检测到可用引擎，请直接点击下方引擎切换：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("已安装的引擎（点击切换并测试）：", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                if (engines.isEmpty()) {
                    Text(
                        text = if (scanning)
                            "正在读取引擎列表…"
                        else
                            "未检测到任何语音引擎。\n若你确认已安装讯飞等引擎，可能是被系统停用了：\n" +
                                "1. 点下方「系统设置」，把首选引擎选为讯飞并试听；\n" +
                                "2. 或在系统设置 → 应用管理中确认讯飞语音引擎未被「停用」；\n" +
                                "3. 完成后重新打开此弹窗。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    engines.forEach { engine ->
                        val selected = (enginePkg ?: speaker.activeEngine) == engine.packageName ||
                            (enginePkg == null && engine.isDefault)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    preferencesManager.ttsEngine =
                                        if (engine.isDefault) null else engine.packageName
                                    enginePkg = preferencesManager.ttsEngine
                                    autoTest = true
                                    status = "正在切换到 ${engine.label}…"
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (selected) "● " else "○ ",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = engine.label +
                                        if (engine.isDefault) "（系统默认）" else "",
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = engine.packageName,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val started = speaker.speak(TTS_TEST_PHRASE) { ok ->
                    status = if (ok) "✅ 播放完成，该引擎可用" else "❌ 该引擎朗读失败，请换一个引擎试试"
                }
                if (!started) status = "❌ 引擎不可用或仍在初始化，请稍后再试"
                else status = "正在播放测试音…"
            }) { Text("播放测试音") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { SystemTtsSpeaker.openSystemTtsSettings(context) }) {
                    Text("系统设置")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}
