package org.example.myapplication.data.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 接口返回结果的数据模型（简单单轮调用使用，Debug 页用）
 *
 * @param answer       AI 回复的文本内容
 * @param totalTokens  本次请求消耗的总 token 数
 * @param inputTokens  输入（提示词+用户消息）token 数
 * @param outputTokens 输出（AI 回复）token 数
 * @param isSuccess    请求是否成功
 * @param errorMessage 失败时的错误信息
 */
data class AiResponse(
    val answer: String,
    val totalTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val isSuccess: Boolean,
    val errorMessage: String = ""
)

/** 模型返回的一次工具调用（Function Calling） */
data class ToolCall(
    val id: String,            // tool_call_id，回传结果时必须原样带回
    val name: String,          // 函数名，如 create_item
    val argumentsJson: String  // 函数参数 JSON 字符串
)

/**
 * 通用 Chat Completions 原始响应
 * [rawAssistantMessage] 为 choices[0].message 原始对象，
 * Function Calling 第二轮请求时需原样回传（包含 tool_calls）
 */
data class AiRawResponse(
    val isSuccess: Boolean,
    val errorMessage: String = "",
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val rawAssistantMessage: JSONObject? = null,
    val totalTokens: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)

/**
 * AI 模型网络服务
 * 统一使用智谱 BigModel 的 Chat Completions 接口（OpenAI 兼容格式）
 * 文档：https://docs.bigmodel.cn/cn/guide/capabilities/function-calling
 */
class AiService {

    // OkHttp 客户端：统一设置超时时间，不自动重试（避免重复扣费/重复回复）
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)  // 免费视觉池高峰期排队较久，给首 token 留足余量
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val baseUrl = "https://open.bigmodel.cn/api/paas/v4"  // 智谱开放平台接口地址
    private val defaultModel = "glm-4.6"                          // 默认使用的模型 ID

    /**
     * 单轮便捷调用：system + 单条用户消息（可带图）
     * 供 Debug 页等不需要上下文/工具的场景使用
     */
    suspend fun sendMessage(
        apiKey: String,
        message: String,
        model: String = defaultModel,
        systemPrompt: String = "",
        imageBase64: String? = null,
        enableThinking: Boolean? = null
    ): AiResponse {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        if (imageBase64.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "user").put("content", message))
        } else {
            // 图文消息：多模态 content 数组（text + image_url base64 data URL）
            val content = JSONArray()
            if (message.isNotBlank()) {
                content.put(JSONObject().put("type", "text").put("text", message))
            }
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
            )
            messages.put(JSONObject().put("role", "user").put("content", content))
        }
        val raw = chatCompletion(apiKey, model, messages, enableThinking = enableThinking)
        return AiResponse(
            answer = raw.content ?: "",
            totalTokens = raw.totalTokens,
            inputTokens = raw.inputTokens,
            outputTokens = raw.outputTokens,
            isSuccess = raw.isSuccess,
            errorMessage = raw.errorMessage
        )
    }

    /**
     * 通用 Chat Completions 请求（Function Calling 编排使用）
     *
     * @param messages 已组装好的完整 messages JSON 数组（system/history/user/tool 等）
     * @param tools    可选的工具定义数组；非空时追加 tools 与 tool_choice=auto
     */
    suspend fun chatCompletion(
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray? = null,
        /**
         * 是否开启深度思考：
         * - null：不带 thinking 参数（沿用服务端默认）；
         * - true/false：仅在 [model] 支持 thinking 参数时注入 enabled/disabled，
         *   不支持的模型（如 glm-4-flash、视觉模型）自动省略，避免服务端报参数错误。
         */
        enableThinking: Boolean? = null
    ): AiRawResponse = withContext(Dispatchers.IO) {
        try {
            // 清理 API Key：去除所有不可见字符、控制字符和非 ASCII 字符
            // 防止复制 Key 时带入空格/换行导致鉴权失败
            val cleanApiKey = apiKey
                .filter { it.code in 32..126 }
                .trim()

            if (cleanApiKey.isBlank()) {
                return@withContext AiRawResponse(
                    isSuccess = false,
                    errorMessage = "API Key 为空，请先在设置中配置"
                )
            }

            val requestJson = JSONObject()
                .put("model", model)
                .put("messages", messages)
            // 深度思考开关：仅 GLM-4.5 及以上文本模型支持；不支持的模型省略该参数
            if (enableThinking != null && supportsThinking(model)) {
                requestJson.put(
                    "thinking",
                    JSONObject().put("type", if (enableThinking) "enabled" else "disabled")
                )
            }
            if (tools != null && tools.length() > 0) {
                requestJson.put("tools", tools).put("tool_choice", "auto")
            }
            val requestBody = requestJson.toString()

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Authorization", "Bearer $cleanApiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            Log.d("AiService", "POST chat/completions model=$model tools=${tools?.length() ?: 0}")

            // 同步执行请求（use 保证响应体自动关闭）
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                Log.d("AiService", "Response code: ${response.code}")

                // HTTP 状态码非 2xx：尝试从响应体解析错误信息
                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errorJson = JSONObject(responseBody).optJSONObject("error")
                        errorJson?.optString("message")
                            ?: JSONObject(responseBody).optString("error", response.message)
                    } catch (e: Exception) {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    return@withContext AiRawResponse(isSuccess = false, errorMessage = errorMsg)
                }

                val jsonResponse = JSONObject(responseBody)
                val messageObj = jsonResponse.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")

                // 200 但缺少消息体（服务端异常/空 choices）：判失败，交由上层重试
                if (messageObj == null) {
                    return@withContext AiRawResponse(
                        isSuccess = false,
                        errorMessage = "服务响应格式异常，请重试"
                    )
                }

                // content 可能为 null（模型只返回 tool_calls 时）
                val content = if (messageObj == null || messageObj.isNull("content")) {
                    null
                } else {
                    messageObj.optString("content", "")
                }

                // 解析 tool_calls：choices[0].message.tool_calls[].{id,function.name,function.arguments}
                val toolCalls = mutableListOf<ToolCall>()
                messageObj?.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val call = arr.optJSONObject(i) ?: continue
                        val fn = call.optJSONObject("function") ?: continue
                        toolCalls.add(
                            ToolCall(
                                id = call.optString("id", "call_$i"),
                                name = fn.optString("name", ""),
                                argumentsJson = fn.optString("arguments", "{}")
                            )
                        )
                    }
                }
                Log.d(
                    "AiService",
                    "content=${content?.take(80)}, toolCalls=${toolCalls.map { it.name }}"
                )

                // 解析 token 用量（智谱字段：prompt_tokens / completion_tokens / total_tokens）
                val usage = jsonResponse.optJSONObject("usage")
                AiRawResponse(
                    isSuccess = true,
                    content = content,
                    toolCalls = toolCalls,
                    rawAssistantMessage = messageObj,
                    totalTokens = usage?.optInt("total_tokens", 0) ?: 0,
                    inputTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                    outputTokens = usage?.optInt("completion_tokens", 0) ?: 0
                )
            }
        } catch (e: Exception) {
            // 网络异常（无网络、超时、DNS 失败等）统一封装为失败结果
            AiRawResponse(isSuccess = false, errorMessage = "网络错误: ${e.message ?: "未知错误"}")
        }
    }

    /**
     * 流式 Chat Completions（SSE），参数与 [chatCompletion] 一致。
     *
     * - [onContentDelta] 在 OkHttp 回调线程上按到达顺序收到正文增量（reasoning_content 思考链
     *   按产品决策不透出，直接丢弃）；
     * - tool_calls 分片按 index 缓冲拼接，流结束后统一返回，因此工具调用轮对 UI 表现为静默；
     * - 出字前失败（连接异常 / HTTP 429、500、502、503、504 / 业务码 1302、1305 限流过载）
     *   按 1s、2s（带随机抖动）自动退避重试，最多 [STREAM_MAX_ATTEMPTS] 次，免费模型高峰期
     *   基本可自行恢复；一旦已开始接收正文/工具分片，则不再重试，沿用半截处理逻辑；
     * - 用户取消（协程取消 → call.cancel）：抛出 [CancellationException]，已收增量由上层
     *   自行决定仅展示或丢弃，本方法不产出结果；
     * - 读到半截网络中断（非取消）：若已收到非空正文且未出现工具调用，按成功返回半截正文
     *   （上层照常落库）；否则返回失败，由上层走错误气泡重试。
     */
    suspend fun chatCompletionStream(
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray? = null,
        enableThinking: Boolean? = null,
        onContentDelta: (String) -> Unit
    ): AiRawResponse {
        val cleanApiKey = apiKey.filter { it.code in 32..126 }.trim()
        if (cleanApiKey.isBlank()) {
            return AiRawResponse(isSuccess = false, errorMessage = "API Key 为空，请先在设置中配置")
        }

        val requestJson = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            // 智谱兼容 OpenAI：流式下默认 usage 可能缺失，显式要求最后一片带 token 用量
            .put("stream_options", JSONObject().put("include_usage", true))
        if (enableThinking != null && supportsThinking(model)) {
            requestJson.put(
                "thinking",
                JSONObject().put("type", if (enableThinking) "enabled" else "disabled")
            )
        }
        if (tools != null && tools.length() > 0) {
            requestJson.put("tools", tools).put("tool_choice", "auto")
        }
        val requestBody = requestJson.toString()

        Log.d("AiService", "POST chat/completions [stream] model=$model tools=${tools?.length() ?: 0}")

        var lastMessage = "网络错误，请稍后重试"
        for (attempt in 0 until STREAM_MAX_ATTEMPTS) {
            when (val outcome = executeStreamOnce(cleanApiKey, requestBody, onContentDelta)) {
                is StreamAttempt.Finished -> return outcome.response
                is StreamAttempt.Retryable -> {
                    lastMessage = outcome.message
                    if (attempt < STREAM_MAX_ATTEMPTS - 1) {
                        val waitMs = streamBackoffMillis(attempt)
                        Log.w(
                            "AiService",
                            "stream transient failure (attempt ${attempt + 1}/$STREAM_MAX_ATTEMPTS): " +
                                "${outcome.message}, retry in ${waitMs}ms"
                        )
                        delay(waitMs)
                    }
                }
            }
        }
        return AiRawResponse(
            isSuccess = false,
            errorMessage = "$lastMessage（已自动重试 ${STREAM_MAX_ATTEMPTS - 1} 次仍失败，请稍后手动重试）"
        )
    }

    /** 单次 SSE 请求结果：终态响应，或出字前可退避重试的瞬时失败 */
    private sealed class StreamAttempt {
        data class Finished(val response: AiRawResponse) : StreamAttempt()
        data class Retryable(val message: String) : StreamAttempt()
    }

    /**
     * 发起一次 SSE 请求。连接失败或 HTTP 层被拒（尚未出字）时返回 [StreamAttempt.Retryable]
     * 或终态失败；响应头 200 之后的一切结果（成功、半截中断）都是 [StreamAttempt.Finished]，
     * 不再重试，避免重复输出/重复扣费。
     */
    private suspend fun executeStreamOnce(
        apiKey: String,
        requestBody: String,
        onContentDelta: (String) -> Unit
    ): StreamAttempt = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .build()

        val call = client.newCall(request)
        // 协程取消（用户点"停止生成"）时立即断开底层连接，中断阻塞中的读行
        cont.invokeOnCancellation { runCatching { call.cancel() } }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isActive) return
                // 连接阶段失败，尚未出字，可退避重试
                cont.resume(StreamAttempt.Retryable("网络错误: ${e.message ?: "未知错误"}"))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                // 即使解析中途失败也要保证响应体关闭
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        var errorMsg = "HTTP ${resp.code}: ${resp.message}"
                        var bizCode: String? = null
                        try {
                            val errorJson = JSONObject(body).optJSONObject("error")
                            if (errorJson != null) {
                                errorJson.optString("message").takeIf { it.isNotBlank() }?.let { errorMsg = it }
                                bizCode = errorJson.opt("code")?.toString()
                            } else {
                                JSONObject(body).optString("error", "").takeIf { it.isNotBlank() }
                                    ?.let { errorMsg = it }
                            }
                        } catch (_: Exception) {
                            // 保留 HTTP 兜底文案
                        }
                        if (!cont.isActive) return
                        // 限流（1302/429）、服务端过载（1305/5xx）出字前可重试；400/401/403 等参数鉴权
                        // 问题重试无意义，直接终态失败
                        val retryable = resp.code in RETRYABLE_HTTP_CODES || bizCode in RETRYABLE_BIZ_CODES
                        cont.resume(
                            if (retryable) StreamAttempt.Retryable(errorMsg)
                            else StreamAttempt.Finished(AiRawResponse(isSuccess = false, errorMessage = errorMsg))
                        )
                        return
                    }

                    val contentBuilder = StringBuilder()
                    // index → 该 tool_call 的累积槽（id/name 首片给出，arguments 多片拼接）
                    val toolSlots = sortedMapOf<Int, ToolCallSlot>()
                    var usageJson: JSONObject? = null

                    try {
                        val reader = resp.body?.charStream()?.buffered()
                            ?: throw IOException("响应体为空")
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty() || data == "[DONE]") {
                                if (data == "[DONE]") break
                                continue
                            }
                            val chunk = JSONObject(data)
                            chunk.optJSONObject("usage")?.let { usageJson = it }
                            val choice0 = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                            val delta = choice0.optJSONObject("delta") ?: continue

                            // 正文增量（思考链 reasoning_content 在此被刻意忽略）
                            val piece = if (delta.isNull("content")) null else delta.optString("content")
                            if (!piece.isNullOrEmpty()) {
                                contentBuilder.append(piece)
                                onContentDelta(piece)
                            }

                            // tool_calls 增量：按 index 归位拼接
                            delta.optJSONArray("tool_calls")?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    val tc = arr.optJSONObject(i) ?: continue
                                    val slot = toolSlots.getOrPut(tc.optInt("index", 0)) { ToolCallSlot() }
                                    tc.optString("id").takeIf { it.isNotBlank() }?.let { slot.id = it }
                                    tc.optJSONObject("function")?.let { fn ->
                                        fn.optString("name").takeIf { it.isNotBlank() }?.let { slot.name = it }
                                        fn.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                                            slot.args.append(it)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 用户主动取消导致的读中断：交还给协程取消语义（半截仅 UI 展示，不入库）
                        if (call.isCanceled()) {
                            if (cont.isActive) {
                                cont.resumeWithException(CancellationException("用户停止生成"))
                            }
                            return
                        }
                        // 非取消的网络中断：有半截正文且无工具调用 → 半截保留当成功（终态，不重试）
                        val partial = contentBuilder.toString()
                        if (partial.isNotBlank() && toolSlots.isEmpty()) {
                            Log.w("AiService", "stream broken with partial content: ${e.message}")
                            if (cont.isActive) {
                                cont.resume(StreamAttempt.Finished(buildStreamResponse(partial, emptyList(), null)))
                            }
                        } else if (cont.isActive) {
                            cont.resume(
                                StreamAttempt.Finished(
                                    AiRawResponse(
                                        isSuccess = false,
                                        errorMessage = "网络错误: ${e.message ?: "未知错误"}"
                                    )
                                )
                            )
                        }
                        return
                    }

                    val toolCalls = toolSlots.values.mapNotNull { slot ->
                        if (slot.name.isBlank()) return@mapNotNull null
                        ToolCall(
                            id = slot.id.ifBlank { "call_${slot.hashCode()}" },
                            name = slot.name,
                            argumentsJson = slot.args.toString().ifBlank { "{}" }
                        )
                    }
                    val fullContent = contentBuilder.toString()
                    val rawMessage = if (toolCalls.isNotEmpty()) {
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", JSONObject.NULL)
                            .put(
                                "tool_calls",
                                JSONArray().apply {
                                    toolCalls.forEach { tc ->
                                        put(
                                            JSONObject()
                                                .put("id", tc.id)
                                                .put("type", "function")
                                                .put(
                                                    "function",
                                                    JSONObject()
                                                        .put("name", tc.name)
                                                        .put("arguments", tc.argumentsJson)
                                                )
                                        )
                                    }
                                }
                            )
                    } else null

                    if (cont.isActive) {
                        cont.resume(
                            StreamAttempt.Finished(
                                buildStreamResponse(fullContent, toolCalls, rawMessage, usageJson)
                            )
                        )
                    }
                }
            }
        })
    }

    /** 出字前可重试的退避间隔：第 1 次等 1s、第 2 次等 2s，附 0-500ms 随机抖动 */
    private fun streamBackoffMillis(attempt: Int): Long {
        val base = when (attempt) {
            0 -> 1_000L
            else -> 2_000L
        }
        return base + Random.nextLong(0, 500)
    }

    /** 组装流式结果（与非流式 [AiRawResponse] 结构保持一致，供 runLoop 无感使用） */
    private fun buildStreamResponse(
        content: String,
        toolCalls: List<ToolCall>,
        rawAssistantMessage: JSONObject?,
        usageJson: JSONObject? = null
    ): AiRawResponse = AiRawResponse(
        isSuccess = true,
        content = content,
        toolCalls = toolCalls,
        rawAssistantMessage = rawAssistantMessage,
        totalTokens = usageJson?.optInt("total_tokens", 0) ?: 0,
        inputTokens = usageJson?.optInt("prompt_tokens", 0) ?: 0,
        outputTokens = usageJson?.optInt("completion_tokens", 0) ?: 0
    )

    /** 单个 tool_call 分片的累积槽 */
    private class ToolCallSlot {
        var id: String = ""
        var name: String = ""
        val args: StringBuilder = StringBuilder()
    }

    companion object {
        /** SSE 出字前瞬时失败的最大尝试次数（含首次），即最多自动重试 2 次 */
        private const val STREAM_MAX_ATTEMPTS = 3

        /** 出字前遇到这些 HTTP 状态码可退避重试：限流与服务端瞬时故障 */
        private val RETRYABLE_HTTP_CODES = setOf(429, 500, 502, 503, 504)

        /** 智谱业务错误码：1302 触达速率限制、1305 服务暂时过载 */
        private val RETRYABLE_BIZ_CODES = setOf("1302", "1305")

        // 可选文本模型常量
        const val MODEL_GLM_5_3 = "glm-5.3"      // 最新旗舰，推理能力最强
        const val MODEL_GLM_4_6 = "glm-4.6"      // 成熟旗舰，智能体任务性价比高
        const val MODEL_GLM_4_5_AIRX = "glm-4.5-airx" // 极速档，低延迟高响应，支持工具调用，按量计费
        const val MODEL_GLM_4_FLASH = "glm-4-flash" // 免费文本模型，适合开发调试

        // 视觉模型常量（聊天页发送图片时必须使用视觉模型，纯文本模型无法识图）
        const val MODEL_GLM_4_6V_FLASH = "glm-4.6v-flash" // 免费视觉模型，128K，默认
        const val MODEL_GLM_4_6V = "glm-4.6v"             // 视觉推理旗舰，按量计费
        const val MODEL_GLM_5_3_FLASH = "glm-5.3-flash"   // 最新原生多模态，能看图/视频/文件
        const val MODEL_GLM_5_3_FLASHX = "glm-5.3-flashx" // 5.3-Flash 极速版，约 200 tokens/s

        /**
         * 是否支持 thinking（深度思考）参数：GLM-4.5 及以上文本模型，以及全系视觉模型
         * （官方文档确认 4.6V/4.6V-Flash 支持思考开关，5.3-Flash 系列同代支持）。
         * 服务端 thinking 默认 enabled，视觉请求不显式关闭会空转思考链导致长时间干等，
         * 因此视觉模型必须在白名单内，跟随设置页"深度思考"开关（默认关）显式传 disabled。
         * glm-4-flash 不支持，继续省略该参数。用显式白名单而非前缀判断。
         */
        fun supportsThinking(model: String): Boolean = model in THINKING_CAPABLE_MODELS

        private val THINKING_CAPABLE_MODELS = setOf(
            MODEL_GLM_5_3,
            "glm-5.2", "glm-5.1", "glm-5", "glm-5-turbo",
            "glm-4.7", "glm-4.7-flashx",
            MODEL_GLM_4_6,
            "glm-4.5-air", MODEL_GLM_4_5_AIRX, "glm-4.5",
            // 视觉模型
            MODEL_GLM_4_6V, MODEL_GLM_4_6V_FLASH,
            MODEL_GLM_5_3_FLASH, MODEL_GLM_5_3_FLASHX
        )
    }
}
