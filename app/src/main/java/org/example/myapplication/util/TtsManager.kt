package org.example.myapplication.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import java.util.Locale

/**
 * 语音播报抽象层：当前只有系统 TTS 实现，
 * 以后接入云端 TTS 时新增一个实现类即可，UI 层无需改动
 */
interface Speaker {

    /** 引擎是否已初始化完成（不再异步加载中，也未初始化失败） */
    val isReady: Boolean

    /**
     * 朗读文本（自动打断上一段）
     *
     * @param text   播报文本（内部会清洗）
     * @param onDone 朗读自然结束或引擎报错时在主线程回调；参数为是否成功。
     *               主动调用 [stop] 打断时不回调
     * @return true 已开始/已排队朗读；false 引擎确定不可用（调用方可提示用户）
     */
    fun speak(text: String, onDone: (success: Boolean) -> Unit = {}): Boolean

    /** 停止当前朗读（不触发 onDone） */
    fun stop()

    /** 释放引擎，页面销毁时调用 */
    fun shutdown()
}

/**
 * 基于 Android 系统 [TextToSpeech] 的实现
 *
 * 兼容性说明（OPPO/ColorOS、部分国产 ROM）：
 * - 引擎初始化成功但 setLanguage 返回 LANG_MISSING_DATA/LANG_NOT_SUPPORTED 时，
 *   国产引擎往往仍能用默认（中文）音色朗读，因此这里宽松处理：引擎在就算可用，
 *   真正不支持时 speak 会同步返回 ERROR，再由 UI 提示
 * - 初始化完成前的朗读请求会排队，初始化结束后自动播放
 */
class SystemTtsSpeaker(
    context: Context,
    private val preferredEngine: String? = null  // 用户在自检面板指定的引擎包名；null 用系统默认
) : Speaker {

    /** 已安装引擎（引擎初始化完成后可读） */
    data class EngineInfo(val label: String, val packageName: String, val isDefault: Boolean)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    @Volatile private var initialized = false   // init 回调已成功
    @Volatile private var initFailed = false    // init 回调失败（无引擎/被禁用）
    @Volatile private var fellBack = false      // 指定引擎失败后是否已回退默认引擎

    private var activeId: String? = null        // 当前朗读的 utteranceId
    private var doneCallback: ((Boolean) -> Unit)? = null

    // 初始化完成前到达的朗读请求，暂存后在回调里播放
    private var pendingText: String? = null
    private var pendingCallback: ((Boolean) -> Unit)? = null

    override val isReady: Boolean
        get() = initialized

    init {
        createEngine(preferredEngine)
    }

    /** 创建 TTS 引擎；指定包名初始化失败时自动回退系统默认引擎 */
    private fun createEngine(enginePackage: String?) {
        try {
            tts = if (enginePackage.isNullOrBlank()) {
                TextToSpeech(appContext) { status -> onInit(status) }
            } else {
                TextToSpeech(appContext, { status -> onInit(status) }, enginePackage)
            }
            Log.i(TAG, "正在初始化 TTS 引擎：${enginePackage ?: "系统默认"}")
        } catch (e: Exception) {
            Log.e(TAG, "创建 TTS 引擎异常", e)
            fallbackToDefault(enginePackage)
        }
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.e(
                TAG,
                "TTS 初始化失败 status=$status, 默认引擎=${engine?.defaultEngine}, " +
                    "可见引擎数=${engine?.engines?.size ?: 0}"
            )
            engine?.engines?.forEach {
                Log.e(TAG, "  已安装但未绑定成功的引擎：${it.label} -> ${it.name}")
            }
            // 指定引擎初始化失败：换系统默认引擎再试一次
            if (!preferredEngine.isNullOrBlank() && !fellBack) {
                fallbackToDefault(preferredEngine)
                return
            }
            initFailed = true
            mainHandler.post { failPending() }
            return
        }
        attachListener(engine)
        // 依次尝试 简中(zh_CN) → 中文(zh) → 系统默认语言；返回码只做记录不做硬判定
        var result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            result = engine.setLanguage(Locale.CHINESE)
        }
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            engine.setLanguage(Locale.getDefault())
        }
        initialized = true
        Log.i(
            TAG,
            "TTS 就绪：默认引擎=${engine.defaultEngine}, 已装引擎数=${engine.engines?.size ?: 0}, " +
                "中文 setLanguage 返回=$result"
        )
        mainHandler.post { playPending() }
    }

    private fun fallbackToDefault(requested: String?) {
        if (requested.isNullOrBlank()) {
            initFailed = true
            mainHandler.post { failPending() }
            return
        }
        fellBack = true
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
        Log.w(TAG, "指定引擎不可用，回退系统默认引擎")
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    /** 当前实际生效的引擎包名 */
    val activeEngine: String?
        get() = tts?.defaultEngine

    /** 系统中已安装的全部 TTS 引擎（初始化完成前为空） */
    fun availableEngines(): List<EngineInfo> {
        val engine = tts ?: return emptyList()
        val defaultPkg = engine.defaultEngine
        return engine.engines?.map {
            EngineInfo(label = it.label ?: it.name, packageName = it.name, isDefault = it.name == defaultPkg)
        } ?: emptyList()
    }

    override fun speak(text: String, onDone: (Boolean) -> Unit): Boolean {
        val engine = tts
        if (initFailed || engine == null) return false
        // 引擎还在初始化：排队等待，避免初始化慢时误报"不可用"
        if (!initialized) {
            pendingText = text
            pendingCallback = onDone
            return true
        }
        return doSpeak(engine, text, onDone)
    }

    private fun doSpeak(
        engine: TextToSpeech,
        rawText: String,
        onDone: (Boolean) -> Unit
    ): Boolean {
        val cleaned = cleanForTts(rawText)
        if (cleaned.isBlank()) {
            onDone(true)
            return true
        }
        doneCallback = onDone
        val id = "pa_${System.nanoTime()}"
        activeId = id
        val result = engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "speak 同步返回 ERROR，引擎=${engine.defaultEngine}")
            activeId = null
            doneCallback = null
            onDone(false)
            return false
        }
        return true
    }

    override fun stop() {
        activeId = null
        doneCallback = null
        pendingText = null
        pendingCallback = null
        tts?.stop()
    }

    override fun shutdown() {
        activeId = null
        doneCallback = null
        pendingText = null
        pendingCallback = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
        initFailed = true
    }

    private fun attachListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == activeId) fireDone(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == activeId) fireDone(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "朗读失败 utterance=$utteranceId errorCode=$errorCode")
                if (utteranceId == activeId) fireDone(false)
            }

            // 被 QUEUE_FLUSH/stop 打断：不算结束，不回调
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == activeId) {
                    activeId = null
                    doneCallback = null
                }
            }
        })
    }

    private fun playPending() {
        val text = pendingText
        val cb = pendingCallback
        pendingText = null
        pendingCallback = null
        if (text == null) return
        val engine = tts ?: run {
            cb?.let { mainHandler.post { it(false) } }
            return
        }
        doSpeak(engine, text, cb ?: {})
    }

    private fun failPending() {
        val cb = pendingCallback
        pendingText = null
        pendingCallback = null
        cb?.invoke(false)
    }

    private fun fireDone(success: Boolean) {
        activeId = null
        val cb = doneCallback
        doneCallback = null
        cb?.let { mainHandler.post { it(success) } }
    }

    companion object {
        private const val TAG = "SystemTts"

        /** 出错时统一播报的简短总结（不朗读整段技术错误文案） */
        const val ERROR_ANNOUNCEMENT = "网络或服务出错了，请检查一下网络连接后重试。"

        /**
         * 跳转系统「文本转语音输出」设置页；
         * 各 ROM 入口不统一，失败时退到无障碍设置
         */
        fun openSystemTtsSettings(context: Context) {
            val app = context.applicationContext
            val direct = Intent("com.android.settings.TTS_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(direct)
            } catch (e: Exception) {
                try {
                    app.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e2: Exception) {
                    Toast.makeText(app, "请在系统设置中搜索「文本转语音」", Toast.LENGTH_LONG).show()
                }
            }
        }

        /**
         * 清洗 AI 文本用于朗读：
         * 去掉链接、Markdown 符号、emoji（含变体选择符/连接符）与多余空白
         */
        fun cleanForTts(raw: String): String = raw
            .replace(Regex("https?://\\S+"), "链接")
            // Markdown 常见符号：# 标题、* 加粗、` 代码、> 引用、[]() 链接括号
            .replace(Regex("[#*`>_\\[\\](){}|]"), "")
            // 中点等装饰性标点
            .replace("·", "")
            // emoji 主体：代理对（绝大多数 emoji 在辅助平面）
            .replace(Regex("[\\uD800-\\uDBFF\\uDC00-\\uDFFF]"), "")
            // 变体选择符 U+FE0F、零宽连接符 U+200D 等
            .replace(Regex("[\\uFE00-\\uFE0F\\u200D\\u200B]"), "")
            // BMP 杂项符号区（天气/箭头/几何等）
            .replace(Regex("[\\u2190-\\u21FF\\u2300-\\u23FF\\u25A0-\\u25FF\\u2600-\\u27BF\\u2B00-\\u2BFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
