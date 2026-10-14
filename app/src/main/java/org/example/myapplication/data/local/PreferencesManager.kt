package org.example.myapplication.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地配置管理器
 * 基于 SharedPreferences 封装应用的全部持久化配置：
 * API Key、当前选中的 AI 模型、日程提醒开关
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "personal_agent_prefs"  // SharedPreferences 文件名
        private const val KEY_API_KEY = "api_key"              // API Key 的存储键
        private const val KEY_SELECTED_MODEL = "selected_model" // 选中文本模型的存储键
        private const val KEY_VISION_MODEL = "vision_model"    // 选中视觉模型的存储键（发图片时使用）
        private const val KEY_REMINDER_ENABLED = "reminder_enabled" // 提醒开关的存储键
        private const val KEY_CONFIRM_BEFORE_SAVE = "confirm_before_save" // AI 写入事项前是否二次确认
        private const val KEY_TTS_ENABLED = "tts_enabled" // 语音播报开关
        private const val KEY_TTS_ENGINE = "tts_engine"   // 指定 TTS 引擎包名（自检面板选择）
        private const val KEY_CONFIRM_BEFORE_MUTATION = "confirm_before_mutation" // AI 修改/取消事项前确认
        private const val KEY_LAST_READ_CHAT_ID = "last_read_chat_id" // 聊天已读水位（最后已读的 AI 消息 id）
        private const val KEY_CHAT_NOTIF_REQUESTED = "chat_notif_requested" // 是否已因聊天补请过通知权限
        private const val KEY_SCHEDULE_BUFFER_MINUTES = "schedule_buffer_minutes" // 相邻日程缓冲分钟数
        private const val KEY_THINKING_ENABLED = "thinking_enabled" // 是否开启模型深度思考（默认关，提速）
        private const val KEY_STATUS_NOTIFICATION_ENABLED = "status_notification_enabled" // 日程常驻通知开关
        private const val KEY_AGREEMENT_ACCEPTED = "agreement_accepted" // 是否已同意用户协议与隐私政策

        /** 新装用户默认文本模型：glm-4.5-airx 极速档 */
        const val DEFAULT_TEXT_MODEL = "glm-4.5-airx"
    }

    /**
     * 聊天已读水位：id 不大于该值的 assistant 消息视为已读。
     * -1 表示尚未初始化（首次进入时把当前最新 AI 消息整体视为已读，避免老消息亮红点）
     */
    var lastReadChatId: Long
        get() = prefs.getLong(KEY_LAST_READ_CHAT_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_READ_CHAT_ID, value).apply()

    /** 是否已因发送聊天消息补请过 POST_NOTIFICATIONS（只主动请一次，避免反复打扰） */
    var chatNotifRequested: Boolean
        get() = prefs.getBoolean(KEY_CHAT_NOTIF_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_CHAT_NOTIF_REQUESTED, value).apply()

    // API Key：读写时都会经过 cleanApiKey 清洗，默认空字符串
    var apiKey: String
        get() = cleanApiKey(prefs.getString(KEY_API_KEY, "") ?: "")
        set(value) = prefs.edit().putString(KEY_API_KEY, cleanApiKey(value)).apply()

    /**
     * 清理 API Key：去除所有不可见字符、控制字符和非 ASCII 字符
     * 避免从其他 App 复制 Key 时带入不可见字符导致鉴权失败
     */
    private fun cleanApiKey(key: String): String {
        return key.filter { it.code in 32..126 }.trim()
    }

    // 选中的文本模型 ID。默认 glm-4.5-airx 极速档（低延迟、支持工具调用）；
    // 注意：默认值仅对新装生效，已安装用户沿用其已保存的选择，不做强制迁移
    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED_MODEL, DEFAULT_TEXT_MODEL) ?: DEFAULT_TEXT_MODEL
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value).apply()

    // 选中的视觉模型 ID（发送图片时使用），默认免费的 glm-4.6v-flash
    var visionModel: String
        get() = prefs.getString(KEY_VISION_MODEL, "glm-4.6v-flash") ?: "glm-4.6v-flash"
        set(value) = prefs.edit().putString(KEY_VISION_MODEL, value).apply()

    // AI 识别出事项后是否需要用户二次确认（确认卡片），默认开启
    var confirmBeforeSave: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_BEFORE_SAVE, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_BEFORE_SAVE, value).apply()

    // 日程提醒总开关，默认开启
    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    // AI 回复语音播报开关（系统 TTS），默认关闭
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    // 指定 TTS 引擎包名；null/空 表示使用系统默认引擎
    var ttsEngine: String?
        get() = prefs.getString(KEY_TTS_ENGINE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE, value).apply()

    // AI 修改/取消事项前是否二次确认（删除永远强制确认，不受此开关影响），默认开启
    var confirmBeforeMutation: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_BEFORE_MUTATION, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_BEFORE_MUTATION, value).apply()

    /**
     * 相邻日程缓冲时间（分钟）：两事项间隔小于该值时冲突检测给出软警告。
     * 可选 0/15/30/60，默认 30；0 表示不检查缓冲。
     */
    var scheduleBufferMinutes: Int
        get() = prefs.getInt(KEY_SCHEDULE_BUFFER_MINUTES, 30)
        set(value) = prefs.edit().putInt(KEY_SCHEDULE_BUFFER_MINUTES, value).apply()

    /**
     * 是否开启模型深度思考。默认关闭以提升响应速度；
     * 仅对支持 thinking 参数的文本模型（GLM-4.5 及以上）生效，不支持的模型自动忽略。
     */
    var thinkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINKING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_THINKING_ENABLED, value).apply()

    /**
     * 日程常驻通知开关：通知栏长期保留一条静默常驻通知，复刻日程页 Hero
     * （进行中 / 下一项 / 今日无安排）。默认开启，关闭时撤销通知与刷新闹钟。
     */
    var statusNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_STATUS_NOTIFICATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_STATUS_NOTIFICATION_ENABLED, value).apply()

    /**
     * 是否已同意《用户协议》与《隐私政策》。
     * 默认 false：首次安装及老用户升级到含协议弹窗的版本时都会重新弹窗取得同意；
     * 用户撤回授权时随 [clear] 一并删除，下次启动重新弹窗。
     */
    var agreementAccepted: Boolean
        get() = prefs.getBoolean(KEY_AGREEMENT_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_AGREEMENT_ACCEPTED, value).apply()

    // 清除所有本地配置（注销/重置时使用）
    fun clear() {
        prefs.edit().clear().apply()
    }

    // 检查是否已配置 API Key（聊天页据此显示在线状态）
    fun isApiKeyConfigured(): Boolean = apiKey.isNotBlank()
}
