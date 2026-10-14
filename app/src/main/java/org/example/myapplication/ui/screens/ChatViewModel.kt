package org.example.myapplication.ui.screens

import android.content.Context
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.myapplication.data.agent.AssistantAgent
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.ChatRole
import org.example.myapplication.data.repository.ChatRepository
import org.example.myapplication.reminder.AiReplyNotifications

/**
 * 一次失败请求的现场（临时状态，不入库）
 * 保留原消息文字/图片，供点击错误气泡时原样重发
 */
data class FailedRequest(
    val message: String,
    val userText: String,
    val imageBase64: String?
)

/**
 * 聊天页一次性 UI 事件（不持久、不补发）：
 * 仅聊天页处于组合中时收集得到——页面切走期间发生的回复不播报，符合预期。
 */
sealed interface ChatEvent {
    /** AI 文字回复已落库：UI 据此补请提醒权限并自动播报 */
    data class Reply(val text: String, val dbId: Long) : ChatEvent

    /** 请求失败：UI 播报固定错误短句 */
    data object RequestFailed : ChatEvent

    /** 需要 UI 弹 Toast（VM 不持有界面上下文） */
    data class ShowToast(val message: String) : ChatEvent
}

/**
 * 聊天页 ViewModel（Activity 作用域，跨底部 Tab 存活）。
 *
 * 承载原先散落在 ChatScreen 组合里的全部会话状态与在途请求：
 * - 请求跑在 viewModelScope，切 Tab / App 进后台都不会取消，回复照常落库；
 * - 页面不可见时，回复/待确认/失败转成系统静默通知；
 * - 未读 AI 回复数以 Room Flow + 已读水位驱动导航栏红点，进程重启不丢。
 */
class ChatViewModel(
    private val appContext: Context,
    private val preferencesManager: PreferencesManager,
    private val chatRepository: ChatRepository,
    private val assistantAgent: AssistantAgent
) : ViewModel() {

    /** 持久聊天历史（界面渲染），Room Flow 驱动 */
    val history = chatRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    /**
     * 流式回复的当前累积文本（仅 UI 展示用，请求结束后才一次性入库）。
     * 为空字符串时气泡显示"正在思考..."转圈；非空时逐字渲染。
     */
    private val _streamingText = MutableStateFlow("")
    val streamingText = _streamingText.asStateFlow()

    /**
     * 用户主动停止后保留的半截回复：**只显示，不入库、不进 AI 上下文**。
     * 下一次发送/重试/确认续跑时清除。
     */
    private val _stoppedPartial = MutableStateFlow<String?>(null)
    val stoppedPartial = _stoppedPartial.asStateFlow()

    private var requestJob: Job? = null

    private val _ephemeralError = MutableStateFlow<FailedRequest?>(null)
    val ephemeralError = _ephemeralError.asStateFlow()

    /** AI 识别出、等待用户确认保存的事项（不入库，决策前临时展示为卡片） */
    private val _pendingActions = MutableStateFlow<List<AssistantAgent.DeferredAction>?>(null)
    val pendingActions = _pendingActions.asStateFlow()

    /** 确认卡片中每个操作的勾选状态（与 pendingActions 一一对应，默认全选） */
    private val _selectedActions = MutableStateFlow<List<Boolean>>(emptyList())
    val selectedActions = _selectedActions.asStateFlow()

    /** 聊天页当前是否对用户可见（ON_RESUME/ON_PAUSE 维护），决定通知与已读策略 */
    private val _chatVisible = MutableStateFlow(false)

    /**
     * 已读水位：id 不大于该值的 assistant 消息算已读。
     * null 表示尚未从 prefs 恢复——初始化完成前不查询未读数，
     * 避免升级安装首次冷启动时按水位 0 查出全量历史导致红点一闪。
     */
    private val _readWatermark = MutableStateFlow<Long?>(null)

    /** 未读 AI 回复数（水位变化时自动切换查询） */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val unreadCount = _readWatermark
        .filterNotNull()
        .flatMapLatest { chatRepository.observeAssistantCountAfter(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 导航栏红点：仅在聊天页不可见时可能亮——
     * 有未读回复显示数字；不可见期间出现待确认卡片/失败显示纯红点，回到聊天页即消。
     */
    val showBadge = combine(
        unreadCount, _pendingActions, _ephemeralError, _chatVisible
    ) { count, pending, error, visible ->
        !visible && (count > 0 || pending != null || error != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _events = MutableSharedFlow<ChatEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    init {
        // 首次启动（含升级安装）：把当前最新 AI 消息整体视为已读，老消息不产生红点
        viewModelScope.launch {
            val saved = preferencesManager.lastReadChatId
            val watermark = if (saved < 0L) {
                (chatRepository.latestAssistantId() ?: 0L).also {
                    preferencesManager.lastReadChatId = it
                }
            } else {
                saved
            }
            _readWatermark.value = watermark
        }
    }

    // ===== 页面可见性（ChatScreen ON_RESUME / ON_PAUSE 调用） =====

    /** 进入聊天页：推进已读水位到最新、撤销通知与角标 */
    fun onChatVisible() {
        _chatVisible.value = true
        viewModelScope.launch {
            val latest = chatRepository.latestAssistantId() ?: 0L
            preferencesManager.lastReadChatId = latest
            _readWatermark.value = latest
            AiReplyNotifications.cancel(appContext)
        }
    }

    /** 离开聊天页（切 Tab / App 进后台）：之后到达的结果走通知 */
    fun onChatHidden() {
        _chatVisible.value = false
    }

    // ===== 请求编排 =====

    /** 统一发起在途请求：防重入、持有 Job 供手动中止、结束（含取消）时复位加载态 */
    private fun launchRequest(block: suspend () -> Unit) {
        if (_isLoading.value) return
        _isLoading.value = true
        _stoppedPartial.value = null
        _streamingText.value = ""
        requestJob = viewModelScope.launch {
            try {
                block()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 用户主动停止：已收到的半截文字保留为临时气泡（不入库、不进上下文）
                _stoppedPartial.value = _streamingText.value.takeIf { it.isNotBlank() }
                throw ce
            } finally {
                _isLoading.value = false
                _streamingText.value = ""
                requestJob = null
            }
        }
    }

    /** 流式回调：追加正文增量 / 每轮请求开始时清空残留（回调在网络线程，StateFlow 线程安全） */
    private val appendStreamDelta: (String) -> Unit = { piece ->
        _streamingText.value += piece
    }
    private val resetStreamText: () -> Unit = {
        _streamingText.value = ""
    }

    /** 用户手动停止生成（已落库的工具操作不回滚） */
    fun cancelRequest() {
        requestJob?.cancel()
    }

    /**
     * 发送消息：用户消息入库 -> 请求 AI -> AI 回复入库。
     * 输入框清空等纯 UI 处理由调用方完成。
     */
    fun sendMessage(userText: String, imageBase64: String?) {
        if (_isLoading.value || _pendingActions.value != null) return
        if (userText.isBlank() && imageBase64 == null) return

        _ephemeralError.value = null
        launchRequest {
            // 用户消息先入库（纯图片时 text 为空），图片以压缩字节持久化
            val imageBytes = imageBase64?.toJpegBytes()
            chatRepository.add(ChatRole.USER, userText, imageBytes)

            requestAssistant(userText, imageBase64)
        }
    }

    /**
     * 点击错误气泡重试：用户消息已在库中（不会重复入库），
     * 直接用保存的现场重新请求；若原消息已被删除则提示重新输入
     */
    fun retryLastRequest() {
        val failed = _ephemeralError.value ?: return
        if (_isLoading.value || _pendingActions.value != null) return

        launchRequest {
            // 校验最后一条历史仍是该用户消息（可能已被长按删除）
            val last = chatRepository.recentForContext(1).firstOrNull()
            if (last == null || last.role != ChatRole.USER) {
                _ephemeralError.value = null
                _events.tryEmit(ChatEvent.ShowToast("原消息已删除，请重新发送"))
                return@launchRequest
            }

            _ephemeralError.value = null
            requestAssistant(failed.userText, failed.imageBase64)
        }
    }

    /**
     * 用户在确认卡片上做出决策后续跑：[approvals] 与操作一一对应，
     * 只执行勾选的操作，未勾选项按"放弃"回告模型；全部不选等同于整批放弃。
     */
    fun resolvePendingItems(approvals: List<Boolean>) {
        launchRequest {
            _pendingActions.value = null
            val outcome = assistantAgent.resolve(
                approvals,
                onDelta = appendStreamDelta,
                onReset = resetStreamText
            )
            // 续跑失败时保留原消息现场（最后一条用户消息），供点击错误气泡整轮重发
            val last = chatRepository.recentForContext(1).firstOrNull()
            handleOutcome(
                outcome = outcome,
                failedUserText = last?.text ?: "",
                failedImageBase64 = last?.image?.toBase64()
            )
        }
    }

    /** 确认卡片勾选/取消某一条 */
    fun toggleAction(index: Int, checked: Boolean) {
        val current = _selectedActions.value
        if (index in current.indices) {
            _selectedActions.value = current.toMutableList().also { it[index] = checked }
        }
    }

    /** 删除消息时，若删掉的正是等待回复的最后一条，错误现场一并清除 */
    fun dismissError() {
        _ephemeralError.value = null
    }

    /**
     * 请求助理并处理结果（发送与重试共用）
     * 失败时把原消息文字/图片保存在 [FailedRequest] 中，供点击错误气泡重发
     */
    private suspend fun requestAssistant(userText: String, imageBase64: String?) {
        val outcome = assistantAgent.send(
            apiKey = preferencesManager.apiKey,
            textModel = preferencesManager.selectedModel,
            visionModel = preferencesManager.visionModel,
            userText = userText,
            imageBase64 = imageBase64,
            imageBytes = imageBase64?.toJpegBytes(),
            confirmCreate = preferencesManager.confirmBeforeSave,
            confirmMutation = preferencesManager.confirmBeforeMutation,
            onDelta = appendStreamDelta,
            onReset = resetStreamText
        )
        handleOutcome(outcome, failedUserText = userText, failedImageBase64 = imageBase64)
    }

    /** 统一处理一轮（含确认后续跑）的三种结果 */
    private suspend fun handleOutcome(
        outcome: AssistantAgent.Outcome,
        failedUserText: String,
        failedImageBase64: String?
    ) {
        // 结果已确定：流式临时文本退场，交给持久消息/卡片/错误气泡
        _streamingText.value = ""
        when (outcome) {
            is AssistantAgent.Outcome.Reply -> {
                val replyId = chatRepository.add(ChatRole.ASSISTANT, outcome.text)
                if (_chatVisible.value) {
                    // 页面可见：直接计入已读，不发通知
                    preferencesManager.lastReadChatId = replyId
                    _readWatermark.value = replyId
                } else {
                    // 页面不可见：静默通知 + 启动器角标；未读红点由 Room Flow 自动驱动
                    AiReplyNotifications.showReply(appContext, outcome.text)
                }
                _events.tryEmit(ChatEvent.Reply(outcome.text, replyId))
            }

            is AssistantAgent.Outcome.ConfirmActions -> {
                _pendingActions.value = outcome.actions
                _selectedActions.value = outcome.actions.map { true }
                if (!_chatVisible.value) {
                    AiReplyNotifications.showConfirmRequest(appContext, outcome.actions.size)
                }
            }

            is AssistantAgent.Outcome.Failure -> {
                _ephemeralError.value = FailedRequest(
                    message = "❌ ${outcome.message}\n\n请检查：\n1. API Key 是否正确配置\n" +
                        "2. 网络连接是否正常\n3. API Key 是否有足够的额度",
                    userText = failedUserText,
                    imageBase64 = failedImageBase64
                )
                if (!_chatVisible.value) {
                    AiReplyNotifications.showFailure(appContext)
                }
                _events.tryEmit(ChatEvent.RequestFailed)
            }
        }
    }

    private suspend fun String.toJpegBytes(): ByteArray = withContext(Dispatchers.IO) {
        Base64.decode(this@toJpegBytes, Base64.NO_WRAP)
    }

    private fun ByteArray.toBase64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    /** 手动依赖注入工厂（项目现状为纯手写 DI，无 Hilt） */
    class Factory(
        private val appContext: Context,
        private val preferencesManager: PreferencesManager,
        private val chatRepository: ChatRepository,
        private val assistantAgent: AssistantAgent
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return ChatViewModel(
                appContext, preferencesManager, chatRepository, assistantAgent
            ) as T
        }
    }
}
