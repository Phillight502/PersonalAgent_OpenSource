package org.example.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.example.myapplication.data.agent.AssistantAgent
import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.AppDatabase
import org.example.myapplication.data.remote.AiService
import org.example.myapplication.data.repository.AuditRepository
import org.example.myapplication.data.repository.ChatRepository
import org.example.myapplication.data.repository.ItemRepository
import org.example.myapplication.data.repository.MemoryRepository
import org.example.myapplication.reminder.AiReplyNotifications
import org.example.myapplication.reminder.ReminderNotifications
import org.example.myapplication.reminder.ReminderScheduler
import org.example.myapplication.reminder.ScheduleStatusNotifications
import org.example.myapplication.ui.screens.ChatScreen
import org.example.myapplication.ui.screens.ChatViewModel
import org.example.myapplication.ui.screens.DebugScreen
import org.example.myapplication.ui.screens.LegalDocumentScreen
import org.example.myapplication.ui.screens.LegalDocument
import org.example.myapplication.ui.screens.PrivacyAgreementGate
import org.example.myapplication.ui.screens.ScheduleScreen
import org.example.myapplication.ui.screens.SettingsScreen
import org.example.myapplication.ui.theme.PersonalAgentTheme

/**
 * 底部导航项的密封类定义
 * 每个导航项包含标题和对应的图标
 */
sealed class BottomNavItem(
    val title: String,
    val icon: ImageVector
) {
    data object Chat : BottomNavItem("助理", Icons.AutoMirrored.Filled.Chat)       // AI 对话页
    data object Schedule : BottomNavItem("日程", Icons.Default.Event)            // 日程管理页
    data object Settings : BottomNavItem("设置", Icons.Default.Settings)         // 应用设置页
    // Debug 页已隐藏，入口为设置页"关于"连击 7 次，不再占底部 Tab
}

/**
 * 应用唯一入口 Activity
 * 负责初始化全局依赖（本地存储、AI 服务、提醒调度）并承载 Compose 导航
 */
class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager  // 本地配置管理（API Key、模型等）
    private lateinit var aiService: AiService                    // AI 模型网络服务
    private lateinit var reminderScheduler: ReminderScheduler    // 事项提醒闹钟调度
    private lateinit var itemRepository: ItemRepository          // 事项仓库
    private lateinit var chatRepository: ChatRepository          // 聊天记录仓库
    private lateinit var memoryRepository: MemoryRepository      // 长期记忆仓库
    private lateinit var auditRepository: AuditRepository        // 事项操作审计仓库
    private lateinit var assistantAgent: AssistantAgent          // Function Calling 编排器

    /**
     * 聊天页 ViewModel（Activity 作用域）：跨底部 Tab 存活，
     * 切走聊天页时在途 AI 请求不取消、回复照常落库。
     * Factory 闭包内的 lateinit 依赖在首次访问 VM（setContent 期间）时已全部就绪。
     */
    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(
            appContext = applicationContext,
            preferencesManager = preferencesManager,
            chatRepository = chatRepository,
            assistantAgent = assistantAgent
        )
    }

    // 当前选中的底部 Tab 提到 Activity 持有，通知点击与 onNewIntent 都要能切 Tab
    private var selectedTab by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化依赖（手动依赖注入，后续通过参数传给各页面）
        preferencesManager = PreferencesManager(this)
        aiService = AiService()
        // Room 数据库 + 三个仓库（单例，App 生命周期内共用）
        val db = AppDatabase.getInstance(this)
        // 调度器先于仓库创建：仓库写操作会挂钩闹钟
        reminderScheduler = ReminderScheduler(this, preferencesManager, db.itemDao())
        itemRepository = ItemRepository(db.itemDao(), reminderScheduler, db.auditDao())
        chatRepository = ChatRepository(db.chatDao())
        memoryRepository = MemoryRepository(db.memoryDao())
        auditRepository = AuditRepository(db.auditDao())
        assistantAgent = AssistantAgent(
            aiService, itemRepository, chatRepository, memoryRepository, preferencesManager
        )

        ReminderNotifications.ensureChannel(this)  // 通知渠道尽早创建
        AiReplyNotifications.ensureChannel(this)  // AI 回复静默渠道
        ScheduleStatusNotifications.ensureChannel(this)  // 日程常驻静默渠道

        // 事项数据任何变化（手动/AI 增删改）都刷新日程常驻通知；首次发射也承担启动发布
        lifecycleScope.launch {
            itemRepository.observeActive(null).collect {
                ScheduleStatusNotifications.refresh(this@MainActivity)
            }
        }
        selectedTab = intent?.readTabExtra() ?: 0

        enableEdgeToEdge()  // 开启边到边布局，内容延伸到状态栏/导航栏后方
        setContent {
            PersonalAgentTheme {
                MainScreen(
                    preferencesManager = preferencesManager,
                    aiService = aiService,
                    itemRepository = itemRepository,
                    chatRepository = chatRepository,
                    memoryRepository = memoryRepository,
                    auditRepository = auditRepository,
                    assistantAgent = assistantAgent,
                    reminderScheduler = reminderScheduler,
                    chatViewModel = chatViewModel,
                    selectedIndex = selectedTab,
                    onSelectTab = { selectedTab = it }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop 下通知点击复用本实例：切换到通知指定的 Tab
        intent.readTabExtra()?.let { selectedTab = it }
    }

    override fun onResume() {
        super.onResume()
        // 回前台全量同步：覆盖用户在系统设置撤销精确闹钟权限、厂商清理、时间微调等失步场景
        if (this::reminderScheduler.isInitialized) {
            lifecycleScope.launch { reminderScheduler.rescheduleAll() }
            // 同步常驻通知的即时状态（跨零点/系统时间变更/进程外数据变化）
            lifecycleScope.launch { ScheduleStatusNotifications.refresh(this@MainActivity) }
        }
    }

    /** 通知跳转 extra：仅接受合法 Tab 下标，否则返回 null 不切换 */
    private fun Intent.readTabExtra(): Int? =
        if (hasExtra(ReminderNotifications.EXTRA_TAB)) {
            getIntExtra(ReminderNotifications.EXTRA_TAB, -1).takeIf { it in 0..2 }
        } else {
            null
        }
}

/**
 * 主屏幕：底部导航 + 页面切换
 * 通过 selectedIndex 控制当前显示哪个 Tab 页面
 */
@Composable
fun MainScreen(
    preferencesManager: PreferencesManager,
    aiService: AiService,
    itemRepository: ItemRepository,
    chatRepository: ChatRepository,
    memoryRepository: MemoryRepository,
    auditRepository: AuditRepository,
    assistantAgent: AssistantAgent,
    reminderScheduler: ReminderScheduler,
    chatViewModel: ChatViewModel,
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit
) {
    val items = listOf(
        BottomNavItem.Chat,
        BottomNavItem.Schedule,
        BottomNavItem.Settings
    )

    // 助理 Tab 未读红点状态（未读 AI 回复数 / 待确认 / 失败）
    val chatUnread by chatViewModel.unreadCount.collectAsState()
    val showChatBadge by chatViewModel.showBadge.collectAsState()

    // 隐藏 Debug 页：设置"关于"连击 7 次后整页覆盖打开，返回键关闭
    var showDebug by remember { mutableStateOf(false) }
    BackHandler(enabled = showDebug) { showDebug = false }

    // 协议同意状态：未同意时全屏 gate 拦截整个应用；老用户升级（无标记）同样重新弹窗
    var agreementAccepted by remember {
        mutableStateOf(preferencesManager.agreementAccepted)
    }
    // 未同意时点协议链接查看文档（与 gate 互斥展示，返回后回到 gate）
    var agreementDoc by remember { mutableStateOf<LegalDocument?>(null) }
    val appContext = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { onSelectTab(index) },
                        icon = {
                            val navIcon = @Composable {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                            // 助理 Tab：未读 AI 回复显示数字红点，待确认/失败显示纯红点
                            if (index == 0 && showChatBadge) {
                                BadgedBox(
                                    badge = {
                                        if (chatUnread > 0) {
                                            Badge { Text("$chatUnread") }
                                        } else {
                                            Badge()
                                        }
                                    }
                                ) { navIcon() }
                            } else {
                                navIcon()
                            }
                        },
                        label = {
                            Text(
                                text = item.title,
                                fontSize = 11.sp,
                                fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        // 根据选中下标渲染对应页面
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedIndex) {
                0 -> ChatScreen(
                    chatViewModel = chatViewModel,
                    preferencesManager = preferencesManager,
                    chatRepository = chatRepository,
                    itemRepository = itemRepository,
                    reminderScheduler = reminderScheduler
                )
                1 -> ScheduleScreen(
                    itemRepository = itemRepository,
                    reminderScheduler = reminderScheduler,
                    preferencesManager = preferencesManager
                )
                2 -> SettingsScreen(
                    preferencesManager = preferencesManager,
                    itemRepository = itemRepository,
                    chatRepository = chatRepository,
                    memoryRepository = memoryRepository,
                    auditRepository = auditRepository,
                    reminderScheduler = reminderScheduler,
                    onOpenDebug = { showDebug = true }
                )
            }
        }
    }

    if (showDebug) {
        DebugScreen(
            preferencesManager = preferencesManager,
            aiService = aiService,
            itemRepository = itemRepository,
            reminderScheduler = reminderScheduler,
            assistantAgent = assistantAgent,
            auditRepository = auditRepository,
            onBack = { showDebug = false }
        )
    }

    // 协议 gate：必须在 Debug 层之后，保证未同意时盖住一切入口
    if (!agreementAccepted) {
        val doc = agreementDoc
        if (doc != null) {
            LegalDocumentScreen(
                document = doc,
                onBack = { agreementDoc = null }
            )
        } else {
            PrivacyAgreementGate(
                onAgree = {
                    preferencesManager.agreementAccepted = true
                    agreementAccepted = true
                },
                onExitApp = {
                    // 关闭当前任务栈并退出进程
                    (appContext as? Activity)?.finishAffinity()
                },
                onOpenDocument = { agreementDoc = it }
            )
        }
    }
    }
}
