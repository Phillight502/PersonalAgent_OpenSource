package org.example.myapplication.ui.screens

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.myapplication.data.agent.AssistantAgent
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.ChatRole
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.repository.ChatRepository
import org.example.myapplication.data.repository.ItemRepository
import org.example.myapplication.reminder.ExactAlarmGuideDialog
import org.example.myapplication.reminder.ReminderScheduler
import org.example.myapplication.util.SystemTtsSpeaker
import org.example.myapplication.ui.theme.AiBubbleDark
import org.example.myapplication.ui.theme.AiBubbleLight
import org.example.myapplication.ui.theme.UserBubbleDark
import org.example.myapplication.ui.theme.UserBubbleLight
import org.example.myapplication.util.ImageUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import java.io.File

/**
 * 聊天消息数据模型
 *
 * @param dbId        数据库行 ID；欢迎语/加载态/错误气泡等临时消息为 null
 * @param text        消息文本（纯图片消息时为空字符串）
 * @param isUser      是否为用户发送（false 表示 AI 消息）
 * @param timestamp   显示的时间标签
 * @param isLoading   是否为"正在思考..."占位消息
 * @param isStreaming 是否为正在逐字生成的流式回复（末尾带闪烁光标，不入库）
 * @param isStopped   是否为用户停止后仅展示的半截回复（不入库、不进上下文）
 * @param isError     是否为错误提示消息（红色气泡样式）
 * @param imageBase64 可选，用户发送的 JPEG 图片 Base64（不含 data: 前缀），仅用户消息可能携带
 * @param bucket      无 dbId 的临时气泡的稳定 key（避免逐字时因文本 hash 变化被整项重建）
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: String = "",
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val isStopped: Boolean = false,
    val isError: Boolean = false,
    val imageBase64: String? = null,
    val dbId: Long? = null,
    val bucket: String? = null
)

/**
 * AI 助理聊天页面
 * 包含顶部状态栏、消息列表、底部输入框三部分。
 * 会话状态与网络请求由 Activity 作用域的 [ChatViewModel] 持有，切换底部 Tab 不丢失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    preferencesManager: PreferencesManager,
    chatRepository: ChatRepository,
    itemRepository: ItemRepository,
    reminderScheduler: ReminderScheduler
) {
    val coroutineScope = rememberCoroutineScope()  // 图片压缩/删除消息等 UI 本地协程
    val listState = rememberLazyListState()        // 消息列表的滚动状态

    // 会话状态全部来自 ViewModel：切走聊天页时请求继续、回复照常入库、状态不丢
    // 从 Room 观察聊天历史；加载态/错误态为临时气泡不入库
    val history by chatViewModel.history.collectAsState()
    val isLoading by chatViewModel.isLoading.collectAsState()       // 是否正在等待 AI 回复
    val streamingText by chatViewModel.streamingText.collectAsState() // 流式回复当前累积文本
    val stoppedPartial by chatViewModel.stoppedPartial.collectAsState() // 停止后仅展示的半截回复
    val ephemeralError by chatViewModel.ephemeralError.collectAsState() // 最近一次失败现场（可点击重试）
    // AI 识别出、等待用户确认保存的事项（不入库，决策前临时展示为卡片）
    val pendingActions by chatViewModel.pendingActions.collectAsState()
    // 确认卡片中每个操作的勾选状态（与 pendingActions 一一对应，默认全选，可逐条批准）
    val selectedActions by chatViewModel.selectedActions.collectAsState()
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }  // 长按后待确认删除的消息 ID
    // 长按弹出操作菜单的消息（复制/重新发送/删除）；临时气泡不会出现
    var actionMenuMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA) }

    // 持久消息映射为 UI 模型；空历史时在顶部虚拟显示欢迎语（不入库）
    val persistedMessages = remember(history) {
        history.map { e ->
            ChatMessage(
                text = e.text,
                isUser = e.role == ChatRole.USER,
                timestamp = timeFmt.format(java.util.Date(e.createdAt)),
                imageBase64 = e.image?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                dbId = e.id
            )
        }
    }
    val messages: List<ChatMessage> = buildList {
        if (persistedMessages.isEmpty() && !isLoading && stoppedPartial == null) {
            add(
                ChatMessage(
                    text = "你好！我是你的私人AI助理。我可以帮你记录日程、提醒安排、回答你的日程相关问题。\n\n请先在「设置」页面配置你的 API Key，然后就可以开始对话了。",
                    isUser = false,
                    bucket = "welcome"
                )
            )
        }
        addAll(persistedMessages)
        stoppedPartial?.let {
            // 用户主动停止后的半截回复：仅展示，不入库、不进 AI 上下文
            add(
                ChatMessage(
                    text = it,
                    isUser = false,
                    isStopped = true,
                    timestamp = "已停止（未保存）",
                    bucket = "stopped_partial"
                )
            )
        }
        if (isLoading) {
            if (streamingText.isEmpty()) {
                add(
                    ChatMessage(
                        text = "正在思考...",
                        isUser = false,
                        isLoading = true,
                        bucket = "loading"
                    )
                )
            } else {
                add(
                    ChatMessage(
                        text = streamingText,
                        isUser = false,
                        isStreaming = true,
                        bucket = "streaming"
                    )
                )
            }
        }
        ephemeralError?.let {
            add(
                ChatMessage(
                    text = it.message,
                    isUser = false,
                    isError = true,
                    timestamp = "现在",
                    bucket = "error"
                )
            )
        }
    }

    var inputText by remember { mutableStateOf("") }  // 输入框文本

    // ===== 图片发送相关状态 =====
    val context = LocalContext.current
    var pendingImageBase64 by remember { mutableStateOf<String?>(null) } // 待发送图片（已压缩的 Base64）
    var showImagePicker by remember { mutableStateOf(false) }            // 是否显示"拍照/相册"选择弹窗
    var cameraImageFile by remember { mutableStateOf<File?>(null) }      // 拍照输出的临时文件

    // ===== 语音播报 =====
    // 当前正在朗读的已入库 AI 消息 ID（错误短句为临时播报，不高亮任何气泡）
    var speakingDbId by remember { mutableStateOf<Long?>(null) }
    // 引擎包名偏好可能在设置页自检面板被切换；回到聊天页 ON_RESUME 时重新读取并重建引擎
    var ttsEnginePref by remember { mutableStateOf(preferencesManager.ttsEngine) }
    val speaker = remember(ttsEnginePref) { SystemTtsSpeaker(context, ttsEnginePref) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, speaker) {
        // 切走聊天 Tab / App 进后台时停止朗读并通知 VM 进入不可见状态（之后结果走系统通知）；
        // 回到页面时刷新引擎选择、标记已读并撤销通知
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ttsEnginePref = preferencesManager.ttsEngine
                    chatViewModel.onChatVisible()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    speaker.stop()
                    speakingDbId = null
                    chatViewModel.onChatHidden()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // App 内切到其他 Tab 时 Activity 不经过 ON_PAUSE，页面直接被移出组合，
            // 必须在此显式标记不可见，否则在途回复会被误判为可见而不发通知、不亮红点
            chatViewModel.onChatHidden()
            speaker.shutdown()  // 离开页面或引擎切换时释放旧 TTS 引擎
        }
    }

    // 是否弹出"语音引擎不可用"引导弹窗
    var showTtsDialog by remember { mutableStateOf(false) }

    /** 自动播报：仅在设置开关打开时调用；错误只念固定短句 */
    fun announce(text: String, dbId: Long?) {
        if (!preferencesManager.ttsEnabled) return
        val started = speaker.speak(text) { ok ->
            speakingDbId = null
            if (!ok) showTtsDialog = true
        }
        if (started) {
            if (dbId != null) speakingDbId = dbId
        } else {
            showTtsDialog = true
        }
    }

    /** 手动点喇叭：同一条在念则停止，否则从头播放（不受开关限制） */
    fun toggleSpeak(message: ChatMessage) {
        if (message.dbId != null && speakingDbId == message.dbId) {
            speaker.stop()
            speakingDbId = null
        } else {
            val started = speaker.speak(message.text) { ok ->
                speakingDbId = null
                if (!ok) showTtsDialog = true
            }
            if (started) {
                speakingDbId = message.dbId
            } else {
                showTtsDialog = true
            }
        }
    }

    /** 相册图片：IO 线程读取压缩，完成后设为待发送图片（需先于 galleryLauncher 声明） */
    fun onGalleryImageSelected(uri: Uri) {
        coroutineScope.launch {
            val base64 = withContext(Dispatchers.IO) {
                ImageUtils.uriToJpegBase64(context, uri)
            }
            if (base64 != null) {
                pendingImageBase64 = base64
            } else {
                Toast.makeText(context, "图片处理失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 拍照图片：同样在 IO 线程处理临时文件（需先于 cameraLauncher 声明） */
    fun onCameraImageTaken(file: File) {
        coroutineScope.launch {
            val base64 = withContext(Dispatchers.IO) {
                ImageUtils.fileToJpegBase64(context, file)
            }
            if (base64 != null) {
                pendingImageBase64 = base64
            } else {
                Toast.makeText(context, "照片处理失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 相册选择结果回调：系统 Photo Picker，无需存储权限
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onGalleryImageSelected(uri)
    }

    // 拍照结果回调
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = cameraImageFile
        if (success && file != null) {
            onCameraImageTaken(file)
        }
        cameraImageFile = null
    }

    /** 创建缓存文件 + FileProvider Uri，调起系统相机（需先于权限 Launcher 声明） */
    fun launchCamera() {
        try {
            val file = ImageUtils.createCameraImageFile(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            cameraImageFile = file
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "无法启动相机：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 相机运行时权限回调：授权后才真正调起相机
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 事项提醒权限（AI 落库后补请） =====
    // 通知权限运行时请求；拒绝也不影响对话，设置页可再次引导
    val reminderNotificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    var showExactAlarmGuide by remember { mutableStateOf(false) }

    /**
     * 每轮 AI 交互成功落库后调用：只要库中存在需要提醒的事项，
     * 就补齐通知权限与精确闹钟引导。重复调用安全（已授权时全部 no-op）。
     */
    suspend fun ensureReminderPermissionsAfterRound() {
        if (!itemRepository.hasItemsNeedingReminder()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !reminderScheduler.canPostNotifications()
        ) {
            reminderNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!reminderScheduler.canScheduleExact()) {
            showExactAlarmGuide = true
        }
    }

    // 每当消息数量变化或确认卡片出现时，自动滚动到最新一条
    LaunchedEffect(messages.size, pendingActions != null) {
        val count = messages.size + (if (pendingActions != null) 1 else 0)
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }
    // 流式逐字期间持续贴底（直接定位，避免每个分片都做动画导致抖动）
    LaunchedEffect(streamingText) {
        if (streamingText.isNotEmpty()) {
            val count = messages.size + (if (pendingActions != null) 1 else 0)
            if (count > 0) listState.scrollToItem(count - 1)
        }
    }

    // 收集 ViewModel 一次性事件：回复落库后补请提醒权限 + 语音播报。
    // 切走聊天页时本组合销毁、收集停止，后台到达的回复不播报（回来看记录即可）
    LaunchedEffect(chatViewModel) {
        chatViewModel.events.collect { event ->
            when (event) {
                is ChatEvent.Reply -> {
                    // AI 可能刚创建/改期了带提醒的事项：落库后补请通知/精确闹钟权限
                    ensureReminderPermissionsAfterRound()
                    // 自动朗读完整回复（开关关闭时 announce 内部直接返回）
                    announce(event.text, event.dbId)
                }
                // 错误只播报固定短句，不念整段技术文案
                ChatEvent.RequestFailed ->
                    announce(SystemTtsSpeaker.ERROR_ANNOUNCEMENT, null)
                is ChatEvent.ShowToast ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 实际发送一段内容（输入框发送与长按"重新发送"共用）：
     * 用户消息入库与 AI 请求均在 ViewModel 中完成，权限请求等副作用在此统一处理
     */
    fun sendContent(text: String, imageBase64: String?) {
        // 加载中或有待确认事项时禁止发送；文字和图片至少有一个
        if (isLoading || pendingActions != null) return
        if (text.isBlank() && imageBase64 == null) return

        // 首次发消息补请通知权限（AI 回复静默通知需要；用户拒绝也不影响对话与红点）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !reminderScheduler.canPostNotifications() &&
            !preferencesManager.chatNotifRequested
        ) {
            preferencesManager.chatNotifRequested = true
            reminderNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        chatViewModel.sendMessage(text, imageBase64)
    }

    /** 输入框发送：取出文本/待发图片并清空预览，再走统一发送 */
    fun sendMessage() {
        val userMessage = inputText.trim()
        val imageToSend = pendingImageBase64
        if (userMessage.isBlank() && imageToSend == null) return
        inputText = ""
        pendingImageBase64 = null  // 清空待发送预览
        sendContent(userMessage, imageToSend)
    }

    // 页面骨架：顶栏（助理信息）+ 内容（消息列表）+ 底栏（输入区）
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "AI",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "私人助理",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            // 根据 API Key 配置情况显示在线/未配置状态
                            Text(
                                text = if (preferencesManager.isApiKeyConfigured()) "在线 · 已连接AI" else "未配置API Key",
                                fontSize = 11.sp,
                                color = if (preferencesManager.isApiKeyConfigured())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // 底部输入区：圆角输入框 + 圆形发送按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 待发送图片缩略图：选图后、发送前显示，右上角 × 可移除
                pendingImageBase64?.let { b64 ->
                    val pendingBitmap = remember(b64) { decodeBase64Bitmap(b64) }
                    if (pendingBitmap != null) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .align(Alignment.Start)
                        ) {
                            Image(
                                bitmap = pendingBitmap.asImageBitmap(),
                                contentDescription = "待发送图片预览",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            // 移除按钮：覆盖在缩略图右上角
                            IconButton(
                                onClick = { pendingImageBase64 = null },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "移除图片",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 图片按钮：弹出"拍照/相册"选择弹窗，加载中/待确认时禁用
                    IconButton(
                        onClick = { showImagePicker = true },
                        enabled = !isLoading && pendingActions == null,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "发送图片",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        placeholder = {
                            Text(
                                if (pendingImageBase64 != null) "配句话...（可不填）" else "输入消息...",
                                fontSize = 14.sp
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = !isLoading && pendingActions == null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isLoading) {
                        // 加载中：圆形按钮变为"停止"，可中止在途请求（已落库的工具操作不回滚）
                        IconButton(
                            onClick = { chatViewModel.cancelRequest() },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "停止生成",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        // 发送按钮：待确认时禁用；有文字或有图片时可用
                        val canSend = pendingActions == null &&
                            (inputText.isNotBlank() || pendingImageBase64 != null)
                        IconButton(
                            onClick = { sendMessage() },
                            enabled = canSend,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "发送",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "智谱 GLM 驱动 · 数据本地存储",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    ) { innerPadding ->
        // 消息列表：懒加载渲染所有消息气泡
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val lastPersistedId = history.lastOrNull()?.id
            items(messages, key = { it.dbId?.toString() ?: (it.bucket ?: it.text.hashCode().toString()) }) { message ->
                // 请求失败时：错误气泡可点重试；最后一条未得到回复的用户消息也可点重试
                val retryClick = when {
                    message.isError -> { { chatViewModel.retryLastRequest() } }
                    ephemeralError != null &&
                        message.isUser && message.dbId != null &&
                        message.dbId == lastPersistedId -> { { chatViewModel.retryLastRequest() } }
                    else -> null
                }
                // 长按已入库消息可删除（控制 AI 上下文与 token）；临时气泡不可删
                // AI 文字回复显示喇叭按钮，可手动重听/停止（不受播报开关限制）
                val speakerClick = if (!message.isUser && message.dbId != null &&
                    !message.isLoading && !message.isError
                ) {
                    { toggleSpeak(message) }
                } else null
                ChatBubble(
                    message = message,
                    onLongPress = { actionMenuMessage = it },
                    onClick = retryClick,
                    onSpeakerClick = speakerClick,
                    isSpeaking = message.dbId != null && message.dbId == speakingDbId
                )
            }
            // AI 操作确认卡片（新建/修改/取消/删除，决策前不入库）
            pendingActions?.let { pending ->
                item(key = "pending_confirm_card") {
                    PendingActionsCard(
                        actions = pending,
                        selected = selectedActions,
                        onToggle = chatViewModel::toggleAction,
                        onApprove = { chatViewModel.resolvePendingItems(selectedActions) },
                        onReject = { chatViewModel.resolvePendingItems(List(pending.size) { false }) }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }  // 底部留白
        }
    }

    // 图片来源选择弹窗：拍照 / 从相册选择 / 取消
    if (showImagePicker) {
        AlertDialog(
            onDismissRequest = { showImagePicker = false },
            title = { Text("发送图片", fontWeight = FontWeight.Bold) },
            text = { Text("选择图片来源") },
            confirmButton = {
                Column {
                    TextButton(
                        onClick = {
                            showImagePicker = false
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("拍照")
                        }
                    }
                    TextButton(
                        onClick = {
                            showImagePicker = false
                            galleryLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("从相册选择")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImagePicker = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 长按消息操作菜单：用户消息=复制/重新发送/删除，AI 消息=复制/删除
    val clipboardManager = LocalClipboardManager.current
    val menuMessage = actionMenuMessage
    if (menuMessage != null) {
        // 加载中或有待确认事项时不允许重发（与发送按钮禁用条件一致）
        val resendEnabled = menuMessage.isUser && !isLoading && pendingActions == null
        AlertDialog(
            onDismissRequest = { actionMenuMessage = null },
            title = {
                Text(
                    if (menuMessage.isUser) "我的消息" else "AI 回复",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (menuMessage.text.isNotBlank()) {
                        MessageMenuRow(
                            icon = Icons.Default.ContentCopy,
                            label = "复制文本",
                            onClick = {
                                clipboardManager.setText(AnnotatedString(menuMessage.text))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                actionMenuMessage = null
                            }
                        )
                    }
                    if (menuMessage.isUser) {
                        MessageMenuRow(
                            icon = Icons.Default.Send,
                            label = if (menuMessage.imageBase64 != null &&
                                menuMessage.text.isBlank()
                            ) "重新发送图片" else "重新发送",
                            enabled = resendEnabled,
                            onClick = {
                                sendContent(menuMessage.text.trim(), menuMessage.imageBase64)
                                actionMenuMessage = null
                            }
                        )
                        if (!resendEnabled) {
                            Text(
                                text = "AI 正在回复或有待确认事项，暂不能重新发送",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                    MessageMenuRow(
                        icon = Icons.Default.Delete,
                        label = "删除",
                        danger = true,
                        onClick = {
                            // dbId 必非空：菜单只会从已入库气泡的长按触发
                            menuMessage.dbId?.let { pendingDeleteId = it }
                            actionMenuMessage = null
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { actionMenuMessage = null }) { Text("取消") }
            }
        )
    }

    // 删除消息确认弹窗（长按触发）；删除后该消息不再回传给 AI
    val deleteId = pendingDeleteId
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除这条消息？") },
            text = { Text("删除后不会再作为 AI 的对话上下文，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch { chatRepository.delete(deleteId) }
                    // 删掉的正是等待回复的最后一条消息时，错误现场一并清除
                    if (deleteId == history.lastOrNull()?.id) chatViewModel.dismissError()
                    // 删掉的消息正在朗读则立即停止
                    if (deleteId == speakingDbId) {
                        speaker.stop()
                        speakingDbId = null
                    }
                    pendingDeleteId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            }
        )
    }

    // 语音引擎不可用：引导跳转系统「文本转语音输出」设置
    if (showTtsDialog) {
        AlertDialog(
            onDismissRequest = { showTtsDialog = false },
            title = { Text("语音引擎不可用") },
            text = {
                Text(
                    "没有检测到可用的中文语音引擎。\n\n" +
                        "请在系统「文本转语音」设置中：\n" +
                        "1. 首选引擎选择手机自带引擎（如 ColorOS/小布语音引擎）；\n" +
                        "2. 下载「中文（中国）」语音数据并试听；\n" +
                        "3. 完成后回到本页再点喇叭重试。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTtsDialog = false
                    SystemTtsSpeaker.openSystemTtsSettings(context)
                }) { Text("打开系统设置") }
            },
            dismissButton = {
                TextButton(onClick = { showTtsDialog = false }) { Text("取消") }
            }
        )
    }

    // 精确闹钟未授权引导（AI 落库带提醒事项后按需弹出）
    if (showExactAlarmGuide) {
        ExactAlarmGuideDialog(
            scheduler = reminderScheduler,
            onDismiss = { showExactAlarmGuide = false }
        )
    }
}

/**
 * 单条聊天气泡
 * 根据消息类型（用户/AI/加载中/错误）决定颜色、左右对齐、头像和内容样式
 *
 * @param onLongPress    长按气泡回调，回传消息本身（弹复制/重发/删除菜单）；临时消息不触发
 * @param onClick        点击气泡回调；错误气泡用于重新发送，未回复的用户消息也可点击重试
 * @param onSpeakerClick AI 回复上的喇叭按钮回调；null 表示不显示
 * @param isSpeaking     该气泡是否正在朗读（决定喇叭/停止图标）
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: ChatMessage,
    onLongPress: (ChatMessage) -> Unit = {},
    onClick: (() -> Unit)? = null,
    onSpeakerClick: (() -> Unit)? = null,
    isSpeaking: Boolean = false
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    // 气泡背景色：错误用 errorContainer，其余按 用户/AI × 明/暗 主题区分
    val bubbleColor = when {
        message.isLoading -> if (isDark) AiBubbleDark else AiBubbleLight
        message.isError -> MaterialTheme.colorScheme.errorContainer
        message.isUser -> if (isDark) UserBubbleDark else UserBubbleLight
        else -> if (isDark) AiBubbleDark else AiBubbleLight
    }
    // 气泡文字颜色
    val textColor = when {
        message.isError -> MaterialTheme.colorScheme.onErrorContainer
        message.isUser -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 用户消息靠右，AI 消息靠左
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        // AI 头像（仅 AI 消息显示，位于气泡左侧）
        if (!message.isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("AI", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = bubbleColor),
                // 圆角不对称处理：靠近头像一侧的下角收窄，形成"气泡尾巴"的视觉效果
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.isUser) 16.dp else 4.dp,
                    bottomEnd = if (message.isUser) 4.dp else 16.dp
                ),
                // 已入库消息：长按弹操作菜单，点击用于重试未回复的消息；
                // 临时错误气泡（无 dbId）：点击重新发送
                modifier = when {
                    message.dbId != null -> Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = { onLongPress(message) }
                    )
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            ) {
                if (message.isLoading) {
                    // 加载态：转圈 + 提示文字横向排列
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = message.text,
                            color = textColor,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    // 图片消息（仅用户消息可能携带）
                    val bitmap = message.imageBase64?.let {
                        remember(it) { decodeBase64Bitmap(it) }
                    }
                    var showFullscreen by remember { mutableStateOf(false) }

                    if (bitmap != null) {
                        // 按图片真实宽高比展示，限制极端比例，固定宽度 200dp
                        val ratio = (bitmap.width.toFloat() / bitmap.height)
                            .coerceIn(0.62f, 1.6f)
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "聊天图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(200.dp)
                                .aspectRatio(ratio)
                                .combinedClickable(
                                    onClick = { showFullscreen = true },
                                    onLongClick = if (message.dbId != null) {
                                        { onLongPress(message) }
                                    } else null
                                )
                        )
                    }

                    // 图片下方附带的文字（纯图片消息时为空，不渲染）
                    if (message.text.isNotBlank()) {
                        // 流式回复：正文末尾内嵌一个闪烁光标，提示仍在生成
                        val cursorTransition = rememberInfiniteTransition(label = "streaming-cursor")
                        val cursorAlpha by cursorTransition.animateFloat(
                            initialValue = 0.15f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "cursorAlpha"
                        )
                        val displayText = if (message.isStreaming) {
                            buildAnnotatedString {
                                append(message.text)
                                appendInlineContent("cursor", "▍")
                            }
                        } else {
                            androidx.compose.ui.text.AnnotatedString(message.text)
                        }
                        val inlineContent = if (message.isStreaming) {
                            mapOf(
                                "cursor" to InlineTextContent(
                                    Placeholder(8.sp, 14.sp, PlaceholderVerticalAlign.TextBottom)
                                ) {
                                    Text(
                                        text = "▍",
                                        color = textColor.copy(alpha = cursorAlpha),
                                        fontSize = 14.sp
                                    )
                                }
                            )
                        } else {
                            emptyMap()
                        }
                        Text(
                            text = displayText,
                            inlineContent = inlineContent,
                            color = textColor,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .padding(
                                    start = 14.dp, end = 14.dp,
                                    top = 10.dp,
                                    bottom = if (message.isError && onClick != null) 4.dp else 10.dp
                                )
                        )
                    }

                    // 网络错误气泡：点击整卡重新发送上一条消息
                    if (message.isError && onClick != null) {
                        Text(
                            text = "🔄 点击此处重新发送",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(
                                start = 14.dp, end = 14.dp, bottom = 10.dp
                            )
                        )
                    }

                    // AI 回复的语音播报按钮：播放中变为停止图标
                    if (onSpeakerClick != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 8.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            IconButton(
                                onClick = onSpeakerClick,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.Default.Stop
                                    else Icons.Default.VolumeUp,
                                    contentDescription = if (isSpeaking) "停止朗读" else "朗读回复",
                                    tint = if (isSpeaking) MaterialTheme.colorScheme.primary
                                    else textColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }

                    // 全屏查看：黑色遮罩 + 完整图片，点击任意处关闭
                    if (showFullscreen && bitmap != null) {
                        Dialog(
                            onDismissRequest = { showFullscreen = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.95f))
                                    .clickable { showFullscreen = false },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "图片大图",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
            // 气泡下方的时间标签（加载态不显示）
            if (message.timestamp.isNotEmpty() && !message.isLoading) {
                Text(
                    text = message.timestamp,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
                )
            }
        }

        // 用户头像（仅用户消息显示，位于气泡右侧）
        if (message.isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("我", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 长按消息菜单中的一行：图标 + 文案，整行可点；[danger] 用错误色（删除）
 */
@Composable
private fun MessageMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, fontSize = 15.sp, color = tint)
    }
}

/**
 * AI 操作确认卡片（临时，不入库）
 * 支持新建 / 修改（展示改前→改后）/ 取消 / 删除（红色警示，永久不可恢复）
 * 多条操作时每条前带勾选框，可只批准其中一部分；未勾选项按"放弃"处理。
 */
@Composable
private fun PendingActionsCard(
    actions: List<AssistantAgent.DeferredAction>,
    selected: List<Boolean>,
    onToggle: (Int, Boolean) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val hasDelete = actions.any { it is AssistantAgent.DeferredAction.Delete }
    val allCreate = actions.all { it is AssistantAgent.DeferredAction.Create }
    val approvedCount = selected.count { it }
    // 多个操作时才显示逐条勾选；单个操作整卡点击按钮即可
    val selectable = actions.size > 1
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasDelete)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasDelete) Icons.Default.Delete else Icons.Default.Edit,
                    contentDescription = null,
                    tint = if (hasDelete) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        actions.size == 1 && actions[0] is AssistantAgent.DeferredAction.Update ->
                            "AI 想修改这个事项，是否执行？"
                        actions.size == 1 && actions[0] is AssistantAgent.DeferredAction.Cancel ->
                            "AI 想取消这个事项，是否执行？"
                        actions.size == 1 && actions[0] is AssistantAgent.DeferredAction.Delete ->
                            "AI 想永久删除这个事项"
                        allCreate -> "AI 识别到 ${actions.size} 个事项，是否保存？"
                        else -> "AI 请求执行 ${actions.size} 个操作，请确认："
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (hasDelete) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            actions.forEachIndexed { index, action ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    if (selectable) {
                        Checkbox(
                            checked = selected.getOrElse(index) { false },
                            onCheckedChange = { onToggle(index, it) },
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        when (action) {
                            is AssistantAgent.DeferredAction.Create ->
                                CreateActionSummary(action.item)
                            is AssistantAgent.DeferredAction.Update ->
                                UpdateActionSummary(action.before, action.after)
                            is AssistantAgent.DeferredAction.Cancel ->
                                SimpleActionSummary("取消", action.item, danger = false)
                            is AssistantAgent.DeferredAction.Delete ->
                                SimpleActionSummary("删除", action.item, danger = true)
                        }
                        // 冲突/缓冲/归一化异常警告：红色小字逐条展示，用户可据此取消勾选
                        val warnings = when (action) {
                            is AssistantAgent.DeferredAction.Create -> action.warnings
                            is AssistantAgent.DeferredAction.Update -> action.warnings
                            else -> emptyList()
                        }
                        warnings.forEach { w ->
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "⚠ $w",
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (hasDelete) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠️ 删除后事项永久消失，无法恢复（也不会留在归档中）。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onReject) {
                    Text("全部放弃", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onApprove,
                    enabled = approvedCount > 0,
                    colors = if (hasDelete) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ) else ButtonDefaults.buttonColors()
                ) {
                    Text(
                        when {
                            approvedCount == 0 -> "请选择"
                            actions.size == 1 && actions[0] is AssistantAgent.DeferredAction.Delete ->
                                "确认删除"
                            selectable && approvedCount < actions.size ->
                                "执行已选（$approvedCount/${actions.size}）"
                            allCreate -> "保存"
                            else -> "确认执行"
                        }
                    )
                }
            }
            if (!hasDelete) {
                Text(
                    text = "执行后可在「日程」页查看",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 新建事项摘要 */
@Composable
private fun CreateActionSummary(item: ItemEntity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionTag(pendingTypeLabel(item.type), danger = false)
        Spacer(modifier = Modifier.width(6.dp))
        Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    val meta = buildList {
        if (item.isAllDay) {
            item.itemDate?.let { add("$it 全天") }
        } else if (!item.itemTime.isNullOrBlank()) {
            val timeRange = item.itemTime +
                (item.endTime?.takeIf { it.isNotBlank() }?.let { "–$it" } ?: "")
            add(listOfNotNull(item.itemDate, timeRange).joinToString(" "))
        } else {
            item.itemDate?.let { add(it) }
        }
        item.location?.let { add(it) }
        item.attendees?.takeIf { it.isNotBlank() }?.let { add("与$it") }
        add(if (item.scope == org.example.myapplication.data.local.db.Scope.WORK) "工作" else "私人")
        if (item.reminderEnabled) add("已设提醒")
    }
    if (meta.isNotEmpty()) {
        Text(
            text = meta.joinToString(" · "),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    item.description?.takeIf { it.isNotBlank() }?.let {
        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 修改事项摘要：标题 + 逐字段「旧 → 新」 */
@Composable
private fun UpdateActionSummary(before: ItemEntity, after: ItemEntity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionTag("修改", danger = false)
        Spacer(modifier = Modifier.width(6.dp))
        Text(after.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    updateDiffs(before, after).forEach { (label, diff) ->
        Text(
            text = "$label：$diff",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 取消/删除事项摘要 */
@Composable
private fun SimpleActionSummary(tag: String, item: ItemEntity, danger: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionTag(tag, danger = danger)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            item.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
    }
    val meta = listOfNotNull(item.itemDate, item.itemTime.takeIf { !item.isAllDay })
    if (meta.isNotEmpty()) {
        Text(
            text = meta.joinToString(" ") + if (item.isAllDay) "（全天）" else "",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionTag(text: String, danger: Boolean) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 计算修改前后有变化的字段，返回「字段名」到「旧值 → 新值」的列表 */
private fun updateDiffs(before: ItemEntity, after: ItemEntity): List<Pair<String, String>> {
    val diffs = mutableListOf<Pair<String, String>>()
    fun changed(label: String, old: String?, new: String?, render: (String?) -> String = { it ?: "未设置" }) {
        if (old != new) diffs.add(label to "${render(old)} → ${render(new)}")
    }
    if (before.type != after.type)
        diffs.add("类型" to "${pendingTypeLabel(before.type)} → ${pendingTypeLabel(after.type)}")
    changed("标题", before.title, after.title) { it ?: "" }
    changed("备注", before.description, after.description) { it?.takeIf { s -> s.isNotBlank() } ?: "无" }
    changed("日期", before.itemDate, after.itemDate) { it ?: "无日期" }
    changed("时间", before.itemTime, after.itemTime) { it ?: "无时间" }
    changed("结束时间", before.endTime, after.endTime) { it ?: "无" }
    if (before.isAllDay != after.isAllDay)
        diffs.add("全天" to "${if (before.isAllDay) "是" else "否"} → ${if (after.isAllDay) "是" else "否"}")
    changed("地点", before.location, after.location) { it ?: "无地点" }
    changed("参与人物", before.attendees, after.attendees) { it ?: "无" }
    if (before.scope != after.scope)
        diffs.add("范围" to "${scopeText(before.scope)} → ${scopeText(after.scope)}")
    if (before.priority != after.priority)
        diffs.add("优先级" to "${priorityText(before.priority)} → ${priorityText(after.priority)}")
    if (before.recurrence != after.recurrence)
        diffs.add("重复" to "${recurrenceText(before.recurrence)} → ${recurrenceText(after.recurrence)}")
    if (before.reminderEnabled != after.reminderEnabled || before.remindMinutes != after.remindMinutes)
        diffs.add("提醒" to "${reminderText(before)} → ${reminderText(after)}")
    return diffs
}

private fun priorityText(p: Int): String = when (p) {
    0 -> "低"
    2 -> "高"
    else -> "普通"
}

private fun scopeText(scope: String): String =
    if (scope == org.example.myapplication.data.local.db.Scope.WORK) "工作" else "私人"

private fun recurrenceText(r: String): String = when (r) {
    "DAILY" -> "每天"
    "WEEKLY" -> "每周"
    "MONTHLY" -> "每月"
    "YEARLY" -> "每年"
    else -> "不重复"
}

private fun reminderText(item: ItemEntity): String =
    if (!item.reminderEnabled || item.remindMinutes == null) "不提醒"
    else when (item.remindMinutes) {
        0 -> "准时提醒"
        1440 -> "提前 1 天提醒"
        else -> "提前 ${item.remindMinutes} 分钟提醒"
    }

private fun pendingTypeLabel(type: String): String = when (type) {
    "SCHEDULE" -> "日程"
    "TODO" -> "待办"
    "PLAN" -> "计划"
    "WISH" -> "心愿"
    else -> "事项"
}

/**
 * 将 JPEG Base64 字符串解码为 Bitmap；解码失败返回 null
 * （缩略图用 remember 缓存，避免列表重组时重复解码）
 */
private fun decodeBase64Bitmap(base64: String) =
    runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
