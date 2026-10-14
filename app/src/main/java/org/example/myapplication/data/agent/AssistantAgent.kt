package org.example.myapplication.data.agent

import org.example.myapplication.data.local.PreferencesManager
import org.example.myapplication.data.local.db.AuditActor
import org.example.myapplication.data.local.db.ChatMessageEntity
import org.example.myapplication.data.local.db.ChatRole
import org.example.myapplication.data.local.db.ItemEntity
import org.example.myapplication.data.local.db.ItemType
import org.example.myapplication.data.local.db.MemoryCategory
import org.example.myapplication.data.local.db.MemoryEntity
import org.example.myapplication.data.local.db.Priority
import org.example.myapplication.data.local.db.Recurrence
import org.example.myapplication.data.local.db.Scope
import org.example.myapplication.data.local.db.Source
import org.example.myapplication.data.local.db.Status
import org.example.myapplication.data.logic.ScheduleLogic
import org.example.myapplication.data.remote.AiRawResponse
import org.example.myapplication.data.remote.AiService
import org.example.myapplication.data.repository.ChatRepository
import org.example.myapplication.data.repository.ItemRepository
import org.example.myapplication.data.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 私人助理编排器
 *
 * 职责：
 * 1. 组装系统提示词（角色 + 今天日期 + 未来 7 天事项 + 全部长期记忆）
 * 2. 把最近 20 条聊天历史拼进上下文（历史图片只发 [图片] 占位，仅最新图片真正发送）
 * 3. 携带 Function Calling 工具发起请求，本地执行工具后回传结果，工具调用最多 8 轮
 * 4. 创建/修改/取消/删除在需要确认时中断流程，由 UI 弹确认卡片，用户决策后续跑；
 *    删除无视开关，永远强制确认
 */
class AssistantAgent(
    private val aiService: AiService,
    private val itemRepository: ItemRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val preferencesManager: PreferencesManager
) {

    /** 一次用户消息的处理结果 */
    sealed class Outcome {
        /** 模型直接给出了最终文字回复 */
        data class Reply(val text: String) : Outcome()
        /** 有操作等待用户在确认卡片上决策（新建/修改/取消/删除，可能一次多个） */
        data class ConfirmActions(val actions: List<DeferredAction>) : Outcome()
        /** 请求失败，错误文案直接展示 */
        data class Failure(val message: String) : Outcome()
    }

    /** 等待用户确认的工具动作 */
    sealed class DeferredAction {
        abstract val toolCallId: String
        abstract val title: String

        /** 新建事项；[warnings] 为归一化/时间异常等需模型向用户追问的提示 */
        data class Create(
            override val toolCallId: String,
            val item: ItemEntity,
            val warnings: List<String> = emptyList()
        ) : DeferredAction() {
            override val title: String get() = item.title
        }

        /** 修改事项（[before] 原值 / [after] 改后值，仅变化字段不同） */
        data class Update(
            override val toolCallId: String,
            val before: ItemEntity,
            val after: ItemEntity,
            val warnings: List<String> = emptyList()
        ) : DeferredAction() {
            override val title: String get() = after.title
        }

        /** 标记取消 */
        data class Cancel(
            override val toolCallId: String,
            val item: ItemEntity
        ) : DeferredAction() {
            override val title: String get() = item.title
        }

        /** 永久删除 */
        data class Delete(
            override val toolCallId: String,
            val item: ItemEntity
        ) : DeferredAction() {
            override val title: String get() = item.title
        }
    }

    /** 二次确认中断时保存的现场 */
    private data class PendingRound(
        val apiKey: String,
        val model: String,
        val messages: JSONArray,
        val pendingActions: List<DeferredAction>
    )

    private var pending: PendingRound? = null

    /**
     * 上一轮"同名多候选"的快照（仅内存，不入库）。
     * 工具结果里的 id 只活在当次请求中，用户下一轮回复"第一个"时上下文已重建，
     * 因此把最近一次候选暂存并注入下一轮系统提示词，保证按序号能精确命中。
     */
    private data class CandidateSnapshot(
        val id: Long,
        val type: String,
        val title: String,
        val date: String?,
        val time: String?,
        val allDay: Boolean
    )
    private var lastCandidates: List<CandidateSnapshot>? = null

    /** 是否有等待用户确认的操作（UI 据此禁用输入框） */
    fun hasPending(): Boolean = pending != null

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    // 意图校验闸门用粗筛正则：用户消息疑似"建事项/设提醒"，回复疑似"答应"口吻。
    // 仅用于触发模型自检，不代替模型做决策，纯问答误触时模型可直接返回文字。
    private val creationIntentRegex = Regex(
        "提醒|通知|闹钟|叫醒|叫我|别忘了|别忘记|日程|安排|待办|备忘|创建|建立|建一|建个|" +
            "记一下|记一条|记下|帮我记|开会|会议|截止|明天|后天|大后天|周[一二三四五六日天]"
    )
    private val promiseToneRegex = Regex(
        "好的|没问题|放心|已经|已帮|帮你|记下|安排|到时候|准时|提醒你|通知你|搞定|这就"
    )
    private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val weekdayNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /**
     * 事项统一格式化（系统提示词与 query_items 结果共用），按当前时间追加相对时刻标注：
     * - 日程已过开始时间仍未完成 → ⏰进行中（未完成）
     * - 其他类型（待办/计划等）已过时间 → 已逾期
     * - 30 分钟内开始 → 30分钟内开始
     * 仅对有具体时间的非全天事项计算；时间格式非法时不标注
     */
    private fun itemLine(item: ItemEntity, now: Long, withId: Boolean): String = buildString {
        if (withId) append("(id=${item.id}) ")
        append("[${typeLabel(item.type)}] ")
        append(item.itemDate ?: "无日期")
        if (!item.isAllDay && !item.itemTime.isNullOrBlank()) {
            append(" ${item.itemTime}")
            item.endTime?.takeIf { it.isNotBlank() }?.let { append("–$it") }
        }
        if (item.isAllDay) append("（全天）")
        append(" ${item.title}")
        item.location?.takeIf { it.isNotBlank() }?.let { append(" @$it") }
        relativeTag(item, now)?.let { append(" $it") }
    }

    private fun relativeTag(item: ItemEntity, now: Long): String? {
        val date = item.itemDate?.takeIf { it.isNotBlank() } ?: return null
        val time = item.itemTime?.takeIf { it.isNotBlank() } ?: return null
        if (item.isAllDay) return null
        val start = try {
            dateTimeFmt.parse("$date $time")?.time
        } catch (e: Exception) {
            null
        } ?: return null
        val diff = start - now
        return when {
            diff > 0 && diff <= MILLIS_30_MINUTES -> "（30分钟内开始）"
            diff <= 0 -> when (item.type) {
                ItemType.SCHEDULE -> "⏰进行中（未完成）"
                else -> "（已逾期）"
            }
            else -> null
        }
    }

    /** 事项是否具备系统提醒资格：有日期 + 有具体时间 + 非全天 */
    private fun reminderEligible(date: String?, time: String?, isAllDay: Boolean): Boolean =
        !isAllDay && !date.isNullOrBlank() && !time.isNullOrBlank()

    /** 创建成功后给模型的补充信息：时间区间 + 提醒设置，供其向用户准确转述 */
    private fun itemSettingsSuffix(item: ItemEntity): String = buildString {
        if (item.isAllDay) {
            item.itemDate?.let { append("（$it 全天）") }
        } else if (!item.itemDate.isNullOrBlank() && !item.itemTime.isNullOrBlank()) {
            append("（${item.itemDate} ${item.itemTime}")
            item.endTime?.takeIf { it.isNotBlank() }?.let { append("–$it") }
            append("）")
        }
        if (item.reminderEnabled) {
            val m = item.remindMinutes
            append(
                when {
                    m == null || m == 0 -> "，将准时提醒"
                    m >= 60 && m % 60 == 0 -> "，提前${m / 60}小时提醒"
                    else -> "，提前$m 分钟提醒"
                }
            )
        }
    }

    /**
     * 处理一条用户消息（调用前用户消息应已通过 ChatRepository 入库）
     *
     * @param userText        用户实际输入的文字（可能为空，纯图片消息）
     * @param imageBase64     本次图片的 JPEG Base64（仅最新一张真正发送）
     * @param imageBytes      同一图片的字节数组，用于图片识别事项的来源留档
     * @param confirmCreate   create_item 是否需要用户二次确认
     * @param confirmMutation update_item/cancel_item 是否需要用户二次确认；删除永远确认
     */
    suspend fun send(
        apiKey: String,
        textModel: String,
        visionModel: String,
        userText: String,
        imageBase64: String?,
        imageBytes: ByteArray?,
        confirmCreate: Boolean,
        confirmMutation: Boolean,
        /** 最终文字回复的 SSE 正文增量回调（工具调用轮静默）；回调在网络线程，勿直接碰 UI 状态 */
        onDelta: (String) -> Unit = {},
        /** 每轮请求开始前回调：UI 清空上一轮残留的流式文字 */
        onReset: () -> Unit = {}
    ): Outcome {
        pending = null
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", buildSystemPrompt()))

        // 最近 20 条历史（含刚入库的当前用户消息，位于最后一条）
        val history: List<ChatMessageEntity> = chatRepository.recentForContext(20)
        history.forEachIndexed { index, msg ->
            val isLatest = index == history.lastIndex
            when (msg.role) {
                ChatRole.ASSISTANT ->
                    messages.put(JSONObject().put("role", "assistant").put("content", msg.text))
                else -> {
                    val hasImage = msg.image != null
                    if (hasImage && isLatest) {
                        // 仅最新一张图片真正发送；纯图片消息注入简短引导语
                        val text = userText.ifBlank { DEFAULT_IMAGE_PROMPT }
                        messages.put(buildUserMessage(text, imageBase64))
                    } else if (hasImage) {
                        // 历史图片只发占位文字，避免视觉 token 随聊天长度累积
                        messages.put(
                            JSONObject().put("role", "user")
                                .put("content", msg.text.ifBlank { "[图片]" })
                        )
                    } else {
                        messages.put(JSONObject().put("role", "user").put("content", msg.text))
                    }
                }
            }
        }

        val model = if (imageBase64 != null) visionModel else textModel
        return runLoop(
            apiKey, model, messages,
            deferCreateInFirstRound = confirmCreate,
            deferMutationInFirstRound = confirmMutation,
            currentImageBytes = imageBytes,
            userText = userText,
            onDelta = onDelta,
            onReset = onReset
        )
    }

    /**
     * 用户在确认卡片上做出决策后续跑
     * @param approvals 与待确认操作一一对应：true=执行该项，false=放弃该项（可逐条勾选）
     */
    suspend fun resolve(
        approvals: List<Boolean>,
        onDelta: (String) -> Unit = {},
        onReset: () -> Unit = {}
    ): Outcome {
        val round = pending ?: return Outcome.Failure("没有待确认的操作")
        pending = null

        // 被用户拒绝的操作：除了把"已取消"回告模型，还要在本轮续跑中硬拦截重复调用，
        // 防止模型无视拒绝结果重新发起 create/update/cancel/delete 后又谎称已执行
        val rejectedCreates = mutableListOf<ItemEntity>()
        val rejectedMutationIds = mutableSetOf<Long>()
        // 一次批准多条新建时不做全天评审诱导：避免模型接着多轮 review/update，把轮次耗光
        val approvedCreateCount = round.pendingActions.count {
            it is DeferredAction.Create && approvals.getOrElse(round.pendingActions.indexOf(it)) { false }
        }
        val batchCreate = approvedCreateCount > 1
        round.pendingActions.forEachIndexed { index, action ->
            val approved = approvals.getOrElse(index) { false }
            val resultText = if (approved) {
                performAction(action, enableDensityHint = !batchCreate)
            } else {
                when (action) {
                    is DeferredAction.Create -> rejectedCreates += action.item
                    is DeferredAction.Update -> rejectedMutationIds += action.after.id
                    is DeferredAction.Cancel -> rejectedMutationIds += action.item.id
                    is DeferredAction.Delete -> rejectedMutationIds += action.item.id
                }
                rejectedText(action)
            }
            round.messages.put(toolResultMessage(action.toolCallId, resultText))
        }
        return runLoop(
            round.apiKey, round.model, round.messages,
            // 模型可能把一句话里的多事项拆成多轮逐个发起：首轮只弹了一张卡，
            // 续跑中再冒出的 create_item 必须重新收集进确认卡，不能静默直接入库
            deferCreateInFirstRound = true,
            // 用户已在交互中：修改/取消直接执行；删除若再次出现仍强制确认
            deferMutationInFirstRound = false,
            currentImageBytes = null,
            // 续跑中模型若重复发起已批准事项（库里已有），直接幂等返回，不再弹卡
            dedupeBeforeDefer = true,
            rejectedCreates = rejectedCreates,
            rejectedMutationIds = rejectedMutationIds,
            onDelta = onDelta,
            onReset = onReset
        )
    }

    /**
     * 被拒事项是否与本次新建参数同一件（类型/标题/日期/时间/全天一致即视为重复提交）。
     * 模型被拒后续跑时若重新发起同一 create_item，据此硬拦截，防止误建后谎称成功。
     */
    private fun isRejectedCreate(item: ItemEntity, rejected: List<ItemEntity>): Boolean =
        rejected.any {
            it.type == item.type &&
                it.title.trim() == item.title.trim() &&
                it.itemDate == item.itemDate &&
                it.itemTime == item.itemTime &&
                it.isAllDay == item.isAllDay
        }

    /** 拦截已被用户拒绝的操作后，回给模型的硬约束文本 */
    private fun rejectedRekeyText(action: String): String =
        "系统拦截：用户刚刚已在确认卡片上明确拒绝了这次${action}，操作没有执行，数据没有任何变化。" +
            "严禁再次调用同一工具或换种说法重试；请直接用自然语言告知用户这次${action}已取消、没有生效，" +
            "绝不能说已经完成/已经创建/已经修改。"

    /**
     * 真正落地一个已确认的动作，返回给模型的工具结果文本。
     * @param enableDensityHint 批量确认续跑时关闭：多条新建逐条诱导评审会平白多耗工具轮次，
     *        还可能引发二次确认卡，用户没有评审意图时不应触发。
     */
    private suspend fun performAction(
        action: DeferredAction,
        enableDensityHint: Boolean = true
    ): String = when (action) {
        is DeferredAction.Create -> {
            // 幂等查重：失败重试/超时重发时同一事项不重复入库
            val duplicate = itemRepository.findDuplicate(action.item)
            val head = if (duplicate != null) {
                "该事项已存在（id=${duplicate.id}，标题：${action.item.title}），未重复创建。"
            } else {
                val id = itemRepository.add(action.item, AuditActor.AI)
                "已成功创建事项（id=$id）：${action.item.title}" + itemSettingsSuffix(action.item)
            }
            // 新建落库当天若已有 2 场以上时段日程，提示模型可做一次全天评审（每轮最多一次）
            val densityHint = if (enableDensityHint && duplicate == null) {
                dayDensityHint(action.item)
            } else null
            listOf(head)
                .plus(action.warnings.map { "注意：$it" })
                .plus(listOfNotNull(densityHint))
                .joinToString("\n")
        }
        is DeferredAction.Update -> {
            itemRepository.update(action.after, AuditActor.AI)
            (listOf("已修改事项（id=${action.after.id}）：${action.after.title}") +
                action.warnings.map { "注意：$it" }).joinToString("\n")
        }
        is DeferredAction.Cancel -> {
            itemRepository.cancel(action.item.id, AuditActor.AI)
            "已取消事项（id=${action.item.id}）：${action.item.title}"
        }
        is DeferredAction.Delete -> {
            itemRepository.delete(action.item.id, AuditActor.AI)
            "已永久删除事项（id=${action.item.id}）：${action.item.title}"
        }
    }

    private fun rejectedText(action: DeferredAction): String = when (action) {
        is DeferredAction.Create -> "用户在确认卡片上点击了取消/放弃，该事项【没有创建、没有入库】。" +
            "严禁再次调用 create_item，也不要说「已创建/已记下/已安排」；直接告诉用户这次创建已取消。"
        is DeferredAction.Update -> "用户在确认卡片上没有确认这次修改，事项【保持原样、未做任何改动】。" +
            "严禁再次调用 update_item，直接告诉用户修改未生效。"
        is DeferredAction.Cancel -> "用户在确认卡片上没有确认取消，事项【仍然有效】。" +
            "严禁再次调用 cancel_item，直接告诉用户事项保持有效。"
        is DeferredAction.Delete -> "用户在确认卡片上没有确认删除，事项【已保留、未删除】。" +
            "严禁再次调用 delete_item，直接告诉用户事项没有删除。"
    }

    /**
     * 工具调用循环：请求 → 执行工具 → 回传结果 → 再请求，直到模型给出文字回复。
     * 工具调用最多 [MAX_ROUNDS] 轮；模型偶发空内容的原样重试独立计数，不占用工具轮次。
     */
    private suspend fun runLoop(
        apiKey: String,
        model: String,
        messages: JSONArray,
        deferCreateInFirstRound: Boolean = false,
        deferMutationInFirstRound: Boolean = false,
        currentImageBytes: ByteArray?,
        /** 本轮用户原始输入：用于"口头答应却没调工具"的意图校验闸门；resolve 续跑不传 */
        userText: String = "",
        /** 用户在确认卡片上拒绝的新建事项：本轮续跑中重复发起时硬拦截，不执行 */
        rejectedCreates: List<ItemEntity> = emptyList(),
        /** 用户拒绝的修改/取消/删除目标 id：本轮续跑中再次操作同一 id 时硬拦截 */
        rejectedMutationIds: Set<Long> = emptySet(),
        /** 确认后续跑：create_item 本应弹卡，但若库里已有完全相同事项则幂等返回不重复弹卡 */
        dedupeBeforeDefer: Boolean = false,
        onDelta: (String) -> Unit = {},
        /** 每轮请求开始前回调：UI 清空上一轮残留的流式文字 */
        onReset: () -> Unit = {}
    ): Outcome {
        var deferCreate = deferCreateInFirstRound
        var deferMutation = deferMutationInFirstRound
        // 评审工具返回后，模型据此发起的改期属"AI 建议、用户审核"，即使在确认后续跑轮次也要再次确认
        var reviewDrivenDefer = false
        var toolRounds = 0
        var emptyRetries = 0
        // 意图校验闸门是否已用过（最多一次，防死循环）
        var intentGuardUsed = false
        // 首轮可能携带图片、使用视觉模型；模型一旦发起工具调用，图片信息就已结构化进
        // tool_calls 参数，此后所有轮次（含用户批准后的续跑）都是纯文字工具往返，
        // 立即剥离 base64 图片并切换到文本模型：图片全程只上传一次，总结轮走极速文本通道。
        var currentModel = model
        while (toolRounds < MAX_ROUNDS) {
            // 每轮开始前清掉上一轮工具调用时可能透出的零星正文，保证气泡只呈现最终回复
            onReset()
            val resp: AiRawResponse = aiService.chatCompletionStream(
                apiKey, currentModel, messages, AgentTools.JSON,
                enableThinking = preferencesManager.thinkingEnabled,
                onContentDelta = onDelta
            )
            if (!resp.isSuccess) return Outcome.Failure(resp.errorMessage)

            // 无工具调用：本轮就是最终答复
            if (resp.toolCalls.isEmpty()) {
                val text = resp.content?.takeIf { it.isNotBlank() }
                if (text != null) {
                    // 意图校验闸门：首轮没调任何工具、用户消息含建事项/提醒意图、
                    // 回复又是"答应"口吻时，强制模型自检并续跑一次，防止只口头答应。
                    // 中间文本不入库，只作为上下文带给模型；最终以续跑结果为准。
                    if (!intentGuardUsed && toolRounds == 0 &&
                        needsToolIntentGuard(userText, text)
                    ) {
                        intentGuardUsed = true
                        messages.put(
                            JSONObject().put("role", "assistant").put("content", text)
                        )
                        messages.put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", buildIntentGuardInstruction())
                        )
                        continue
                    }
                    return Outcome.Reply(text)
                }
                // 模型偶发空内容（内容安全拦截/服务端抖动）：同请求原样重试一次（不占工具轮次）；
                // 仍为空则走失败气泡（可点击重试），不向用户展示"（无回复）"
                if (emptyRetries < 1) {
                    emptyRetries++
                    continue
                }
                return Outcome.Failure("AI 没有返回内容，请点击重试")
            }
            emptyRetries = 0

            // 视觉模型首轮识图使命完成：图片信息已在 tool_calls 参数里，后续轮次不再需要
            // 像素内容。剥离 base64 图片（替换为文字占位）并切换到文本模型，避免每一轮
            // 工具往返都重复上传图片、在免费视觉池重复排队（批准后长时间转圈的根因）。
            if (currentModel != preferencesManager.selectedModel) {
                stripVisionImages(messages)
                currentModel = preferencesManager.selectedModel
            }

            // 原样回传 assistant 消息（其中含 tool_calls，服务端要求逐字带回）
            resp.rawAssistantMessage?.let { messages.put(it) }

            val deferred = mutableListOf<DeferredAction>()
            for (call in resp.toolCalls) {
                val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: JSONObject()
                when (call.name) {
                    "create_item" -> {
                        val parsed = parseItem(args, currentImageBytes)
                        val warnings = parsed.warnings + collectConflictWarnings(parsed.item)
                        if (deferCreate) {
                            // 续跑场景：模型重复发起刚批准入库的同一事项时，幂等返回不再弹卡
                            val existing = if (dedupeBeforeDefer) {
                                itemRepository.findDuplicate(parsed.item)
                            } else null
                            if (existing != null) {
                                messages.put(
                                    toolResultMessage(
                                        call.id,
                                        "该事项已存在（id=${existing.id}，标题：${existing.title}），" +
                                            "未重复创建；不要再调用 create_item，继续处理其余事项即可。"
                                    )
                                )
                            } else {
                                deferred.add(DeferredAction.Create(call.id, parsed.item, warnings))
                            }
                        } else if (isRejectedCreate(parsed.item, rejectedCreates)) {
                            // 用户刚拒绝过同一事项：拦截重复创建，不让其误建后又回复"已创建"
                            messages.put(toolResultMessage(call.id, rejectedRekeyText("创建")))
                        } else {
                            messages.put(
                                toolResultMessage(
                                    call.id,
                                    performAction(DeferredAction.Create(call.id, parsed.item, warnings))
                                )
                            )
                        }
                    }
                    "update_item" -> {
                        val result = buildUpdate(call.id, args)
                        val rejectedAgain = (result as? MutateResult.Defer)?.action.let {
                            it is DeferredAction.Update && it.after.id in rejectedMutationIds
                        }
                        if (rejectedAgain) {
                            messages.put(toolResultMessage(call.id, rejectedRekeyText("修改")))
                        } else if (result is MutateResult.Defer && (deferMutation || reviewDrivenDefer)) {
                            deferred.add(result.action)
                        } else {
                            messages.put(toolResultMessage(call.id, mutateResultText(result)))
                        }
                    }
                    "cancel_item", "delete_item" -> {
                        val result = buildStatusChange(call.name, call.id, args)
                        val rejectedAgain = (result as? MutateResult.Defer)?.action.let {
                            when (it) {
                                is DeferredAction.Cancel -> it.item.id in rejectedMutationIds
                                is DeferredAction.Delete -> it.item.id in rejectedMutationIds
                                else -> false
                            }
                        }
                        if (rejectedAgain) {
                            messages.put(
                                toolResultMessage(
                                    call.id,
                                    rejectedRekeyText(if (call.name == "delete_item") "删除" else "取消")
                                )
                            )
                        } else {
                            // 删除永远强制确认；取消仅首轮按开关确认
                            val needDefer = (result is MutateResult.Defer) &&
                                (call.name == "delete_item" || deferMutation)
                            if (needDefer) {
                                deferred.add((result as MutateResult.Defer).action)
                            } else {
                                messages.put(toolResultMessage(call.id, mutateResultText(result)))
                            }
                        }
                    }
                    else -> {
                        // 查询/完成/记忆类工具立即执行
                        val result = dispatch(call.name, args)
                        // 一旦本轮跑过全天评审，随后模型发起的改期一律先弹卡让用户审核
                        if (call.name == "review_day_plan") reviewDrivenDefer = true
                        messages.put(toolResultMessage(call.id, result))
                    }
                }
            }

            // 需要二次确认：保存现场并中断（此时图片已剥离、currentModel 已是文本模型，
            // 用户批准后的续跑不再走视觉通道）
            if (deferred.isNotEmpty()) {
                pending = PendingRound(apiKey, currentModel, messages, deferred)
                return Outcome.ConfirmActions(deferred)
            }
            // 确认后续跑（dedupeBeforeDefer=true）时保持每轮新建都拦截确认：
            // 模型可能把多事项拆成多轮逐个发起，任何一轮漏网都会静默入库
            if (!dedupeBeforeDefer) deferCreate = false
            deferMutation = false    // 后续轮次不再中断修改/取消（删除仍永远确认）
            toolRounds++
        }
        return Outcome.Failure("AI 工具调用轮次过多，请简化请求后重试")
    }

    /** 变更类工具（修改/取消/删除）的处理结果 */
    private sealed class MutateResult {
        /** 直接有文本结果（成功/未找到/多匹配候选/错误） */
        data class Text(val text: String) : MutateResult()
        /** 需要用户确认后才能执行 */
        data class Defer(val action: DeferredAction) : MutateResult()
    }

    private suspend fun mutateResultText(result: MutateResult): String = when (result) {
        is MutateResult.Text -> result.text
        // 未中断确认却走到这里（如后续轮次）：直接落地执行
        is MutateResult.Defer -> performAction(result.action)
    }

    /** 构造 update_item 的结果：定位 → 部分更新 */
    private suspend fun buildUpdate(toolCallId: String, args: JSONObject): MutateResult {
        val id = args.optLong("id", 0L).takeIf { it > 0 }
        val keyword = args.optString("title_keyword").ifBlank { null }
        when (val target = resolveTarget(id, keyword)) {
            is Target.One -> {
                val outcome = applyUpdate(target.item, args)
                if (sameContent(target.item, outcome.item)) {
                    return MutateResult.Text("没有需要修改的字段，事项未变化：${target.item.title}")
                }
                val warnings = outcome.warnings +
                    collectConflictWarnings(outcome.item, excludeId = outcome.item.id)
                return MutateResult.Defer(
                    DeferredAction.Update(toolCallId, target.item, outcome.item, warnings)
                )
            }
            else -> return MutateResult.Text(targetErrorText(target, "修改"))
        }
    }

    /**
     * 收集事项在其日期当天的冲突/缓冲/全天提示，供确认卡片展示与模型二轮告知。
     * 不占时段（无日期/无时间/全天/非日程）时不检查冲突。
     */
    private suspend fun collectConflictWarnings(
        item: ItemEntity,
        excludeId: Long = item.id
    ): List<String> {
        val date = item.itemDate?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (item.type != ItemType.SCHEDULE || item.isAllDay || item.itemTime.isNullOrBlank()) {
            return emptyList()
        }
        val dayItems = itemRepository.activeOnDate(date)
        val result = ScheduleLogic.checkConflicts(
            item, dayItems, preferencesManager.scheduleBufferMinutes, excludeId
        )
        val messages = result.conflicts.map { ScheduleLogic.conflictMessage(it, date) }.toMutableList()
        result.allDayItems.take(3).forEach {
            messages.add("当天还有全天事项「${it.title}」，留意时间是否受影响")
        }
        return messages
    }

    /**
     * 新建日程落库后给模型的"全天评审"触发提示：
     * 仅当天已有 2 场及以上占时段日程时返回，是否评审交给模型按对话语境判断（每轮最多一次）。
     */
    private suspend fun dayDensityHint(item: ItemEntity): String? {
        val date = item.itemDate?.takeIf { it.isNotBlank() } ?: return null
        if (item.type != ItemType.SCHEDULE || item.isAllDay || item.itemTime.isNullOrBlank()) {
            return null
        }
        val count = itemRepository.activeOnDate(date).count {
            it.type == ItemType.SCHEDULE && !it.isAllDay &&
                !it.itemTime.isNullOrBlank() && ScheduleLogic.occursOnDate(it, date)
        }
        if (count < 2) return null
        return "系统提示：$date 当天已有 $count 场占具体时段的日程。如果你判断当天可能排得偏密、" +
            "或用户关心安排是否合理，可调一次 review_day_plan(date=\"$date\") 做全天评估" +
            "（每轮对话最多一次）；用户没有相关意图时不要主动调。"
    }

    /** 构造 cancel_item / delete_item 的结果 */
    private suspend fun buildStatusChange(name: String, toolCallId: String, args: JSONObject): MutateResult {
        val id = args.optLong("id", 0L).takeIf { it > 0 }
        val keyword = args.optString("title").ifBlank { null }
        val verb = if (name == "delete_item") "删除" else "取消"
        return when (val target = resolveTarget(id, keyword)) {
            is Target.One -> {
                val action = if (name == "delete_item") {
                    DeferredAction.Delete(toolCallId, target.item)
                } else {
                    DeferredAction.Cancel(toolCallId, target.item)
                }
                MutateResult.Defer(action)
            }
            else -> MutateResult.Text(targetErrorText(target, verb))
        }
    }

    /** 按 id 优先、标题关键词其次定位唯一的有效事项 */
    private sealed class Target {
        data class One(val item: ItemEntity) : Target()
        data class Many(val candidates: List<ItemEntity>) : Target()
        object NotFound : Target()
        object Archived : Target()
        object MissingParam : Target()
    }

    private suspend fun resolveTarget(id: Long?, keyword: String?): Target {
        if (id != null) {
            val item = itemRepository.getById(id) ?: run {
                lastCandidates = null
                return Target.NotFound
            }
            // 用 id 精确定位（包括用户按序号选择）：候选使命完成，清掉暂存
            lastCandidates = null
            return if (item.status == Status.ACTIVE) Target.One(item) else Target.Archived
        }
        if (keyword.isNullOrBlank()) return Target.MissingParam
        val matches = itemRepository.getAll()
            .filter { it.status == Status.ACTIVE && it.title.contains(keyword) }
            .sortedWith(compareBy({ it.itemDate ?: "9999-99-99" }, { it.itemTime ?: "99:99" }))
        return when {
            matches.isEmpty() -> {
                lastCandidates = null
                Target.NotFound
            }
            matches.size == 1 -> {
                lastCandidates = null
                Target.One(matches.first())
            }
            else -> {
                // 暂存候选，下一轮用户回复"第一个/周会那个"时模型仍能拿到 id 精确命中
                lastCandidates = matches.map {
                    CandidateSnapshot(it.id, it.type, it.title, it.itemDate, it.itemTime, it.isAllDay)
                }
                Target.Many(matches)
            }
        }
    }

    private fun targetErrorText(target: Target, verb: String): String = when (target) {
        is Target.Many -> buildString {
            append("找到 ${target.candidates.size} 个匹配事项，请列出候选让用户选择要${verb}哪一个（用户回复序号后，用对应 id 再次调用）：")
            target.candidates.forEachIndexed { i, it ->
                append("\n${i + 1}. (id=${it.id}) [${typeLabel(it.type)}] ${it.title}")
                it.itemDate?.let { d -> append(" $d") }
                it.itemTime?.takeIf { t -> !it.isAllDay }?.let { t -> append(" $t") }
                if (it.isAllDay) append("（全天）")
            }
        }
        Target.NotFound -> "没有找到对应的有效事项。"
        Target.Archived -> "该事项已完成或已取消（归档只读），不能${verb}；可提示用户在日程页归档中恢复。"
        Target.MissingParam -> "缺少事项 id 或标题关键词，无法定位要${verb}的事项。"
        is Target.One -> ""
    }

    /**
     * 比较事项的可变内容是否一致（ItemEntity.equals 只比 id，不能用于判断有无字段变化；
     * sourceImage/时间戳不参与）
     */
    private fun sameContent(a: ItemEntity, b: ItemEntity): Boolean =
        a.type == b.type && a.title == b.title && a.description == b.description &&
            a.itemDate == b.itemDate && a.itemTime == b.itemTime && a.endTime == b.endTime &&
            a.isAllDay == b.isAllDay && a.location == b.location &&
            a.attendees == b.attendees && a.scope == b.scope &&
            a.priority == b.priority && a.recurrence == b.recurrence &&
            a.reminderEnabled == b.reminderEnabled && a.remindMinutes == b.remindMinutes

    /** update_item 部分更新结果：改后实体 + 归一化异常警告 */
    private data class UpdateOutcome(val item: ItemEntity, val warnings: List<String>)

    /** 把 update_item 参数按"部分更新"应用到原事项；未出现的字段保持不变；日期时间归一化 */
    private fun applyUpdate(before: ItemEntity, args: JSONObject): UpdateOutcome {
        var a = before
        val warnings = mutableListOf<String>()
        if (args.has("type") && !args.isNull("type")) {
            a = a.copy(
                type = when (args.optString("type").uppercase()) {
                    ItemType.TODO -> ItemType.TODO
                    ItemType.PLAN -> ItemType.PLAN
                    ItemType.WISH -> ItemType.WISH
                    else -> ItemType.SCHEDULE
                }
            )
        }
        if (args.has("title") && !args.isNull("title")) {
            args.optString("title").trim().takeIf { it.isNotBlank() }?.let { a = a.copy(title = it) }
        }
        // description/location：出现且为 null → 清空；出现且有值 → 覆盖
        listOf("description", "location").forEach { key ->
            if (args.has(key)) {
                val value = if (args.isNull(key)) null else args.optString(key).ifBlank { null }
                a = if (key == "description") a.copy(description = value) else a.copy(location = value)
            }
        }
        // date/time：显式 null 或空串 → 清空；有值则归一化，无法识别置空并警告
        if (args.has("date")) {
            val raw = if (args.isNull("date")) null else args.optString("date").ifBlank { null }
            if (raw == null) {
                a = a.copy(itemDate = null)
            } else {
                val normalized = DateTimeNormalizer.normalizeDate(raw)
                a = a.copy(itemDate = normalized)
                if (normalized == null) {
                    warnings.add("新日期「$raw」无法识别，已清空日期；请向用户确认后再设置。")
                }
            }
        }
        if (args.has("time")) {
            val raw = if (args.isNull("time")) null else args.optString("time").ifBlank { null }
            if (raw == null) {
                a = a.copy(itemTime = null)
            } else {
                val normalized = DateTimeNormalizer.normalizeTime(raw)
                a = a.copy(itemTime = normalized)
                if (normalized == null) {
                    warnings.add("新时间「$raw」无法识别，已清空时间；请向用户确认后再设置。")
                }
            }
        }
        // end_time：显式 null/空串 → 清空；有值归一化，无法识别置空并警告
        if (args.has("end_time")) {
            val raw = if (args.isNull("end_time")) null else args.optString("end_time").ifBlank { null }
            if (raw == null) {
                a = a.copy(endTime = null)
            } else {
                val normalized = DateTimeNormalizer.normalizeTime(raw)
                a = a.copy(endTime = normalized)
                if (normalized == null) {
                    warnings.add("新结束时间「$raw」无法识别，已清空；请向用户确认后再设置。")
                }
            }
        }
        if (args.has("is_all_day") && !args.isNull("is_all_day")) {
            a = a.copy(isAllDay = args.optBoolean("is_all_day"))
        }
        // attendees：与 description/location 同语义，null 清空
        if (args.has("attendees")) {
            val value = if (args.isNull("attendees")) null
            else args.optString("attendees").trim().ifBlank { null }
            a = a.copy(attendees = value)
        }
        if (args.has("scope") && !args.isNull("scope")) {
            a = a.copy(
                scope = when (args.optString("scope").uppercase()) {
                    Scope.WORK -> Scope.WORK
                    else -> Scope.PERSONAL
                }
            )
        }
        if (args.has("priority") && !args.isNull("priority")) {
            a = a.copy(
                priority = when (args.optString("priority").uppercase()) {
                    "HIGH" -> Priority.HIGH
                    "LOW" -> Priority.LOW
                    else -> Priority.NORMAL
                }
            )
        }
        if (args.has("recurrence") && !args.isNull("recurrence")) {
            a = a.copy(
                recurrence = when (args.optString("recurrence").uppercase()) {
                    Recurrence.DAILY -> Recurrence.DAILY
                    Recurrence.WEEKLY -> Recurrence.WEEKLY
                    Recurrence.MONTHLY -> Recurrence.MONTHLY
                    Recurrence.YEARLY -> Recurrence.YEARLY
                    else -> Recurrence.NONE
                }
            )
        }
        // remind_minutes：出现且为 null → 关闭提醒；出现且有值 → 更新并启用；不出现 → 不变
        if (args.has("remind_minutes")) {
            if (args.isNull("remind_minutes")) {
                a = a.copy(reminderEnabled = false, remindMinutes = null)
            } else {
                val minutes = args.optInt("remind_minutes", Int.MIN_VALUE)
                if (minutes != Int.MIN_VALUE) {
                    a = a.copy(reminderEnabled = true, remindMinutes = minutes)
                }
            }
        }
        // 兜底 1：结束时间仅属于日程；类型被改为待办/计划/心愿时清空，
        // 防止卡片把待办截止时间误展示成时间区间
        if (a.type != ItemType.SCHEDULE) {
            a = a.copy(endTime = null)
        }
        // 兜底 2：部分更新后若不再具备提醒资格（日期/时间被清空或变为全天），强制关闭提醒；
        // 结束时间依附于具体开始时间，一并清空，防止残留跨天脏数据
        if (!reminderEligible(a.itemDate, a.itemTime, a.isAllDay)) {
            a = a.copy(reminderEnabled = false, remindMinutes = null, endTime = null)
        }
        return UpdateOutcome(a, warnings)
    }

    /** 执行除创建/修改/取消/删除之外的工具，返回给模型的工具结果文本 */
    private suspend fun dispatch(name: String, args: JSONObject): String = when (name) {
        "query_items" -> {
            val range = args.optString("range", "week")
            val type = args.optString("type").ifBlank { null }
            val now = System.currentTimeMillis()
            val items = when (range) {
                // all 包含无日期事项（旧实现按日期区间过滤会漏掉"先不急"的待办）
                "all" -> itemRepository.activeAll()
                "today" -> itemRepository.upcoming(1)
                "tomorrow" -> {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    val day = dateFmt.format(cal.time)
                    itemRepository.upcoming(2).filter { it.itemDate == day }
                }
                else -> itemRepository.upcoming(7)
            }
            val filtered = items.filter { type == null || it.type == type }
            if (filtered.isEmpty()) "该时间范围内没有事项。"
            else filtered.joinToString("\n") { "- " + itemLine(it, now, withId = true) }
        }
        "find_free_slots" -> {
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            val today = dateFmt.format(cal.time)
            val rawDate = args.optString("date").ifBlank { today }
            // buildString 为 inline，裸 return 直接从 dispatch 返回错误提示文本
            val date = DateTimeNormalizer.normalizeDate(rawDate) ?: return buildString {
                append("日期「$rawDate」无法识别，请用自然语言跟用户确认具体日期后再查。")
            }
            val duration = args.optInt("duration_minutes", ScheduleLogic.DEFAULT_DURATION_MINUTES)
                .coerceIn(15, 24 * 60)
            val earliest = DateTimeNormalizer.normalizeTime(
                args.optString("earliest").ifBlank { ScheduleLogic.DEFAULT_EARLIEST }
            ) ?: ScheduleLogic.DEFAULT_EARLIEST
            val latest = DateTimeNormalizer.normalizeTime(
                args.optString("latest").ifBlank { ScheduleLogic.DEFAULT_LATEST }
            ) ?: ScheduleLogic.DEFAULT_LATEST

            val dayItems = itemRepository.activeOnDate(date)
            val slots = ScheduleLogic.findFreeSlots(
                date = date,
                durationMinutes = duration,
                earliest = earliest,
                latest = latest,
                dayItems = dayItems,
                bufferMinutes = preferencesManager.scheduleBufferMinutes,
                nowMillis = now
            )
            if (slots.isEmpty()) {
                // 当天排不开：给出次日最早可用空档，方便模型直接建议
                val parsedDay = dateFmt.parse(date)
                val nextDate = parsedDay?.let {
                    Calendar.getInstance().apply {
                        time = it
                        add(Calendar.DAY_OF_YEAR, 1)
                    }.let { dateFmt.format(it.time) }
                }
                val tomorrowSlots = nextDate?.let { nd ->
                    ScheduleLogic.findFreeSlots(
                        date = nd,
                        durationMinutes = duration,
                        earliest = earliest,
                        latest = latest,
                        dayItems = itemRepository.activeOnDate(nd),
                        bufferMinutes = preferencesManager.scheduleBufferMinutes,
                        nowMillis = now
                    )
                }.orEmpty()
                buildString {
                    append("$date 在 $earliest–$latest 之间没有长达 $duration 分钟的空闲时段。")
                    if (tomorrowSlots.isNotEmpty()) {
                        append(" $nextDate 最早的空档是 ${tomorrowSlots.first().start}–${tomorrowSlots.first().end}，可建议用户改到明天。")
                    }
                }
            } else {
                buildString {
                    append("$date 的空闲时段如下（已避开已有日程及相邻缓冲，按时间先后）：")
                    slots.forEachIndexed { i, s -> append("\n${i + 1}. ${s.start}–${s.end}") }
                    append("\n请结合用户语境推荐其中一个或把候选用口语列给用户选择；用户确定后用 create_item 或 update_item 落库，时间填该空档的开始时刻、有结束时间需求时填结束时刻。")
                }
            }
        }
        "review_day_plan" -> {
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            val today = dateFmt.format(cal.time)
            val rawDate = args.optString("date").ifBlank { today }
            // 同 find_free_slots：裸 return 直接从 dispatch 返回错误提示文本
            val date = DateTimeNormalizer.normalizeDate(rawDate) ?: return buildString {
                append("日期「$rawDate」无法识别，请用自然语言跟用户确认具体日期后再评审。")
            }
            // 过载建议要算次日空档：reviewDay 是纯逻辑非挂起函数，这里先把次日事项预取好
            val nextCal = Calendar.getInstance().apply {
                time = dateFmt.parse(date) ?: return buildString {
                    append("日期「$date」内部解析失败。")
                }
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val nextDate = dateFmt.format(nextCal.time)
            val nextDayItems = itemRepository.activeOnDate(nextDate)
            val review = ScheduleLogic.reviewDay(
                date = date,
                dayItems = itemRepository.activeOnDate(date),
                bufferMinutes = preferencesManager.scheduleBufferMinutes,
                nowMillis = now,
                nextDayItemsProvider = { nextDayItems }
            )
            buildString {
                append("$date 全天评审结果（系统内部信息，供你决策，禁止原样转述给用户）：")
                if (review.timeline.isEmpty()) {
                    append("\n当天没有占具体时段的日程，安排很空。")
                } else {
                    val hours = "%.1f".format(Locale.CHINA, review.totalScheduledMinutes / 60.0)
                    append("\n时间轴（共 ${review.blockingCount} 场，总时长约 $hours 小时）：")
                    review.timeline.forEach { t ->
                        append("\n- ${t.start}–${t.end} ${t.title}")
                        t.location?.takeIf { it.isNotBlank() }?.let { append(" @$it") }
                    }
                }
                if (review.allDayItems.isNotEmpty()) {
                    append("\n全天事项：")
                    append(review.allDayItems.joinToString("、") { "「${it.title}」" })
                }
                if (review.issues.isEmpty()) {
                    append("\n未发现排期问题：用口语告诉用户当天安排合理即可，不要为了凑建议而改动事项。")
                } else {
                    append("\n问题与建议：")
                    review.issues.forEachIndexed { i, issue ->
                        append("\n${i + 1}. ")
                        append(if (issue.warn) "【建议处理】" else "【知情提示】")
                        append(issue.text)
                        issue.suggestion?.let { s ->
                            append("\n   可执行改期：对 id=${s.itemId} 调用 update_item（")
                            if (s.toDate != date) append("date=\"${s.toDate}\"，")
                            append("time=\"${s.toStart}\"，end_time=\"${s.toEnd}\"），")
                            append("把「${s.itemTitle}」移到 ${s.toDate} ${s.toStart}–${s.toEnd}。")
                        }
                    }
                    append("\n处理要求：对每条带“可执行改期”的问题立即调用 update_item 提交（可在同一轮并发多个），")
                    append("系统会弹确认卡片让用户审核，用户没确认前任何事项都保持原样；")
                    append("只有文字没有改期建议的问题（午休、跨地点、过早过晚、间隔太紧），用口语转告并给出你的建议，")
                    append("必要时自己调 find_free_slots 找空档，但严禁移动没有建议依据的事项。")
                }
            }
        }
        "complete_item" -> {
            val id = args.optLong("id", 0L).takeIf { it > 0 }
            val keyword = args.optString("title").ifBlank { null }
            when (val target = resolveTarget(id, keyword)) {
                is Target.One -> {
                    itemRepository.complete(target.item.id, AuditActor.AI)
                    "已完成：${target.item.title}"
                }
                else -> targetErrorText(target, "完成")
            }
        }
        "save_memory" -> {
            val category = normalizeCategory(args.optString("category"))
            val title = args.optString("title").ifBlank { null }
            val content = args.optString("content", "").trim()
            val date = args.optString("date").ifBlank { null }
            if (content.isBlank()) {
                "记忆内容为空，未保存"
            } else {
                memoryRepository.save(category, title, content, date)
                "已记住：${title ?: content.take(20)}"
            }
        }
        "query_memory" -> {
            val category = args.optString("category").ifBlank { null }
            val all = memoryRepository.getAll()
            val list = all.filter { category == null || it.category == category }
            if (list.isEmpty()) "没有相关记忆。"
            else list.joinToString("\n") { m ->
                val head = m.title?.let { "$it：" } ?: ""
                "- (id=${m.id}) [${memoryLabel(m.category)}] $head${m.content}" +
                    (m.memoryDate?.let { d -> "（$d）" } ?: "")
            }
        }
        "update_memory" -> {
            val id = args.optLong("id", 0L).takeIf { it > 0 }
            val keyword = args.optString("title_keyword").ifBlank { null }
            when (val target = resolveMemory(id, keyword, null)) {
                is MemoryTarget.One -> {
                    var m = target.memory
                    if (args.has("category") && !args.isNull("category")) {
                        m = m.copy(category = normalizeCategory(args.optString("category")))
                    }
                    if (args.has("title") && !args.isNull("title")) {
                        args.optString("title").trim().ifBlank { null }?.let {
                            m = m.copy(title = it)
                        }
                    }
                    if (args.has("content") && !args.isNull("content")) {
                        args.optString("content").trim().ifBlank { null }?.let {
                            m = m.copy(content = it)
                        }
                    }
                    // date：显式 null 或空串 → 清空；有值 → 覆盖（不做强制格式校验，模型按 schema 传）
                    if (args.has("date")) {
                        val d = if (args.isNull("date")) null else args.optString("date").ifBlank { null }
                        m = m.copy(memoryDate = d)
                    }
                    if (m.content.isBlank()) {
                        "记忆内容不能为空，未修改"
                    } else {
                        memoryRepository.update(m)
                        "已更新记忆：${memoryDisplayName(m)}"
                    }
                }
                is MemoryTarget.Many -> memoryCandidatesText(target.candidates, "修改")
                MemoryTarget.NotFound -> "没有找到对应的记忆，可先调用 query_memory 查询后再修改。"
                MemoryTarget.MissingParam -> "缺少记忆 id 或标题关键词，无法定位要修改的记忆。"
            }
        }
        "delete_memory" -> {
            val id = args.optLong("id", 0L).takeIf { it > 0 }
            val keyword = args.optString("title").ifBlank { null }
            val category = args.optString("category").ifBlank { null }
                ?.let { normalizeCategory(it) }
            when (val target = resolveMemory(id, keyword, category)) {
                is MemoryTarget.One -> {
                    memoryRepository.delete(target.memory.id)
                    "已删除记忆：${memoryDisplayName(target.memory)}"
                }
                is MemoryTarget.Many -> memoryCandidatesText(target.candidates, "删除")
                MemoryTarget.NotFound -> "没有找到对应的记忆，可先调用 query_memory 查询后再删除。"
                MemoryTarget.MissingParam -> "缺少记忆 id 或标题关键词，无法定位要删除的记忆。"
            }
        }
        else -> "未知工具：$name"
    }

    /** 记忆定位结果（与事项的 [Target] 同构，但记忆无归档概念） */
    private sealed class MemoryTarget {
        data class One(val memory: MemoryEntity) : MemoryTarget()
        data class Many(val candidates: List<MemoryEntity>) : MemoryTarget()
        object NotFound : MemoryTarget()
        object MissingParam : MemoryTarget()
    }

    /** 按 id 优先、标题/内容关键词其次定位记忆；可按分类收窄 */
    private suspend fun resolveMemory(
        id: Long?,
        keyword: String?,
        category: String?
    ): MemoryTarget {
        if (id != null) {
            return when (val m = memoryRepository.getById(id)) {
                null -> MemoryTarget.NotFound
                else -> MemoryTarget.One(m)
            }
        }
        if (keyword.isNullOrBlank()) return MemoryTarget.MissingParam
        val matches = memoryRepository.getAll().filter { m ->
            (category == null || m.category == category) &&
                (m.title?.contains(keyword) == true || m.content.contains(keyword))
        }
        return when {
            matches.isEmpty() -> MemoryTarget.NotFound
            matches.size == 1 -> MemoryTarget.One(matches.first())
            else -> MemoryTarget.Many(matches)
        }
    }

    private fun memoryCandidatesText(candidates: List<MemoryEntity>, verb: String): String =
        buildString {
            append("找到 ${candidates.size} 条匹配记忆，请列出候选让用户选择要${verb}哪一条（用户回复序号后，用对应 id 再次调用）：")
            candidates.forEachIndexed { i, m ->
                append("\n${i + 1}. (id=${m.id}) [${memoryLabel(m.category)}] ${memoryDisplayName(m)}")
            }
        }

    /** 记忆的完整展示文本（工具结果用）：标题：内容（日期） */
    private fun memoryDisplayName(m: MemoryEntity): String {
        val head = m.title?.let { "$it：" } ?: ""
        return "$head${m.content}" + (m.memoryDate?.let { d -> "（$d）" } ?: "")
    }

    /**
     * 意图校验闸门判定：用户消息疑似建事项/设提醒、模型回复是"答应"口吻。
     * 闸门不代替模型决策，只触发一次强制自检；纯问答误触时模型可直接返回文字。
     */
    private fun needsToolIntentGuard(userText: String, reply: String): Boolean =
        creationIntentRegex.containsMatchIn(userText) &&
            promiseToneRegex.containsMatchIn(reply)

    /** 闸门触发后注入的强制自检指令（时间实时生成，再次防止用过时时间） */
    private fun buildIntentGuardInstruction(): String {
        val cal = Calendar.getInstance()
        val today = dateFmt.format(cal.time)
        val nowTime = timeFmt.format(cal.time)
        val weekday = weekdayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return """
            系统自动校验：你上一条回复没有调用任何工具，但用户消息可能包含需要落库的事项或提醒。现在时间是 $today（$weekday）$nowTime。
            请立即重新判断用户意图：
            1. 如果用户确实要创建、修改事项或设置提醒，必须马上调用对应工具（create_item / update_item 等），按上面的当前时间重新换算相对时间，不得再只口头答应；
            2. 如果确认这只是闲聊、咨询或问答、不需要操作工具，请直接给出最终回答。
        """.trimIndent()
    }

    /** 组装系统提示词：角色、当前时刻、逾期事项、近期事项、长期记忆、行为规则 */
    private suspend fun buildSystemPrompt(): String {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val today = dateFmt.format(cal.time)
        val nowTime = timeFmt.format(cal.time)
        val weekday = weekdayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]

        val upcoming = itemRepository.upcoming(7)
        val upcomingBlock = if (upcoming.isEmpty()) {
            "（暂无安排）"
        } else {
            upcoming.joinToString("\n") { "- " + itemLine(it, now, withId = false) }
        }

        // 逾期未完成：日期早于今天仍是 ACTIVE 的事项，最近的 10 条（日期倒序）
        val overdue = itemRepository.activeAll()
            .filter { !it.itemDate.isNullOrBlank() && it.itemDate!! < today }
            .sortedWith(compareByDescending<ItemEntity> { it.itemDate }.thenByDescending { it.itemTime })
            .take(10)
        val overdueBlock = if (overdue.isEmpty()) {
            "（暂无）"
        } else {
            overdue.joinToString("\n") { "- " + itemLine(it, now, withId = false) }
        }

        val memories = memoryRepository.getAll()
        val memoryBlock = if (memories.isEmpty()) {
            "（暂无）"
        } else {
            memories.joinToString("\n") { m ->
                val head = m.title?.let { "$it：" } ?: ""
                "- [${memoryLabel(m.category)}] $head${m.content}" +
                    (m.memoryDate?.let { d -> "（$d）" } ?: "")
            }
        }

        // 上一轮多候选待选：用户本轮可能直接回"第一个/周会那个"，把 id 交还给模型
        val candidateBlock = lastCandidates?.takeIf { it.isNotEmpty() }?.let { candidates ->
            candidates.mapIndexed { i, c ->
                buildString {
                    append("${i + 1}. (id=${c.id}) [${typeLabel(c.type)}] ${c.title}")
                    c.date?.let { append(" $it") }
                    if (!c.allDay) c.time?.let { append(" $it") }
                    if (c.allDay) append("（全天）")
                }
            }.joinToString("\n")
        } ?: "（无）"

        return """
            你是用户的私人 AI 助理，运行在用户自己的手机上，帮助管理日程、待办、计划、心愿和长期记忆。
            今天是 $today（$weekday），当前时间 $nowTime。这是本轮【唯一有效的时间基准】：历史消息里出现的一切日期、时间点、"X分钟后"的说法都是当时的过期记录；处理相对时间（两分钟后/明天/下周五等）时必须以当前时间重新换算，严禁照抄历史回复中的时间。

            【逾期未完成事项】（开始日期已过但仍未标记完成，系统不会再主动通知；只在用户问起或相关时处理，不要每轮都提）
            $overdueBlock

            【未来 7 天事项】（含今天已过开始时间但未完成的；标注"进行中（未完成）/30分钟内开始"的事项要特别留意）
            $upcomingBlock

            【长期记忆】
            $memoryBlock

            【上一轮待选事项】（你刚让用户在这些候选中选择；用户这轮回复"第一个/第二个/那个X"时，直接用对应 id 调用工具，不要再查询；用户没有选择意图则忽略本块）
            $candidateBlock

            行为要求：
            1. 用户表达要做的事、安排、计划、愿望、截止时间、图片中识别出的安排时，调用 create_item，不要只口头答应。禁止只在文字里说"已建立/已记下/已设置提醒/到点提醒你"却不调用工具——即使历史对话中有完全相同的请求和回复，也必须重新调用工具，严禁模仿历史回复只给文字。用户说"提醒我/别忘了/叫我/到点告诉我"但没说提前多久时，remind_minutes 传 0（准时提醒）；用户完全没提提醒才不传该字段。
            2. 用户问安排时调用 query_items（结果中的 id 可用于后续操作）；说做完了用 complete_item；说"记住……"用 save_memory；需要用户背景时用 query_memory；说"忘掉/忘记……"用 delete_memory；更正旧记忆用 update_memory。
            3. 用户要求改期、改时间、改地点、改标题等任何修改时调用 update_item：只传需要修改的字段，未传字段保持不变；要清空日期/时间/地点/备注时传 null。
            4. 用户说某件事取消、不做了时调用 cancel_item（可恢复）；只有用户明确要求删除时才调用 delete_item，删除永久不可恢复，系统会强制让用户确认。
            5. 修改/取消/完成只能针对未完成、未取消的有效事项，归档事项只读。
            6. 定位事项优先使用 query_items 返回的 id；只用标题关键词且命中多条时，必须用自然语言把候选列给用户选择（如"我找到两个：一个是明天下午3点的周会，一个是周五的周会，你说的是哪个？"），不要把 id 和机器编号展示给用户；用户选定后，你自己根据工具结果中的 id 执行操作。
            7. 日期/时间不确定就留空，不要编造。工具执行完成后，用简短自然的中文告知结果。
            8. 【无时间待办的追问】当你创建了"有日期但没有具体时间"的待办后，在同一条回复里追问用户想安排在什么时间；用户给出时间就调 update_item 补上（用户明确说要提醒时带 remind_minutes）。用户没回答不要每轮催，等他再次提起再处理。
            9. 【AI 代为排期】当用户说"你安排/你定/随便/帮我挑个时间"，或"找个时间开会/聊聊/碰一下"时：先调 find_free_slots 查当天（或用户指定日期）的空闲时段，说了时长就传 duration_minutes；从返回空档中挑最合适的一个，事项已存在就用 update_item 写入时间（同时 remind_minutes=0 准时提醒），还没建事项就用 create_item 直接建在该时间，然后明确告知安排在几点、为什么合适；工具返回当天排不开时，按它给出的次日空档向用户提议改到明天。
            10. 【先不急】用户说"先不急/等等再说/回头再说"时，不要设置时间、也不要再追问，待办保持无时间状态，等用户以后主动提起再安排。无时间事项和全天事项都不会收到系统通知，因此不要给它们传 remind_minutes。
            11. 【全天事项】全天事项没有系统通知；当用户询问某天有什么安排时，必须把当天的全天事项一并口头告知，不能遗漏。
            12. 【进行中闭环】当用户询问安排时，如果存在已经过了开始时间但仍未完成的日程（标注"⏰进行中（未完成）"），并且下一条日程将在 30 分钟内开始（标注"30分钟内开始"），必须先提醒用户：上一条日程「标题」还没标记完成，问他是已经完成了还是要取消；得到明确答复后再调 complete_item 或 cancel_item，不要擅自决定。对标注"已逾期"的待办，可在相关对话中顺带确认。
            13. 【回复风格】像一个真人助理那样用自然、口语化的中文说话，简洁有温度，不要生硬客套。要求：
                - 不要使用任何 Markdown 符号（#、*、**、-、`、> 等）和代码格式；不要输出事项 id、括号编号（如 (id=1)）、【日程】【待办】这类类型标签；
                - 上面事项列表里的 (id=…)、方括号类型、⏰ 及"（进行中/已逾期/30分钟内开始）"等标注都是系统给你看的上下文，只能用于理解和决策，绝不允许原样转述给用户；
                - 事项信息用自然语句表达，如"明天下午3点和王总开会，在公司会议室"；
                - 多条安排用自然的衔接词（首先、另外、还有、最后）或直接换行排列，不要用项目符号；时间用"下午3点"这种日常说法，不用 15:00；
                - 完成操作后就自然地说结果，例如"已经帮你记下了，明天下午3点，我会提前15分钟提醒你"，不要复述全部字段；
                - 无论闲聊还是日程询问，最终回复都要尽量短，非必要不超过20个字，一句话能说清就不说两句；只有用户明确要求"详细介绍/详细说说/全部告知"时才允许展开。
            14. 【重复事项】用户说"每天/天天""每周X/周X/每个星期X""每月X号""每年X月X日/每年生日"等周期表述时，必须设置 recurrence（DAILY/WEEKLY/MONTHLY/YEARLY），date 填第一个发生日（例如今天周三、说"每周五"，date 就填本周五）；用户说"以后不重复了/只此一次"时用 update_item 把 recurrence 改成 NONE。
            15. 【过去时间确认】如果工具结果提示"事项开始时间已经过去了"，不要将错就错，也不要自作主张改期：用自然语言跟用户确认他指的是不是明天或其他时间（例如"你说的今晚8点已经过了，是要定明天晚上8点吗？"），得到答复后再 update_item。
            16. 【记忆维护】用户说"忘掉/忘记/别再记得XX""把关于XX的记忆删了"时调用 delete_memory；用户更正之前记住的信息（如"我老婆生日改成X月X日""记错了，我其实不喜欢吃辣"）时调用 update_memory 更正原条目，不要新建重复记忆；对话中用户主动透露的稳定偏好、习惯、重要事实（饮食忌口、通勤方式、常用地址等），及时用 save_memory 记下来。定位记忆优先用 query_memory 结果里的 id；只用关键词且命中多条时，用自然语言列候选让用户选择，不要展示 id。删除或修改成功后自然告知结果即可，不要复述条目细节。
            17. 【时长、人物与范围】创建日程时用户说了时长要换算成 end_time（如"下午2点开一小时会"time=14:00、end_time=15:00；跨天如"23点到凌晨1点"end_time 填01:00），没说时长不要编造；提到参与人物填 attendees，多个人用顿号分隔；工作相关事项（会议、拜访、出差、工作截止、同事协作）scope 填 WORK，生活、家庭、个人事务填 PERSONAL，判断不了可以不传。
            18. 【时间冲突】如果创建或修改事项的工具结果中出现"注意：与……时间重叠"或"仅间隔……分钟"，说明与已有日程冲突或排得太赶：必须在回复中如实告知用户冲突情况，给出调整建议（如换到另一个时间，可用 find_free_slots 查询），不要隐瞒；用户仍确认保存时尊重其选择。
            19. 【多意图理解】收到每句话先在心里拆成一个或多个意图，分清哪些是用户明说的、哪些是该一起办的隐含意图，各自归类（日程/待办/计划/心愿/记忆/查询/闲聊）。一句话含多个意图时，必须在同一个响应里并发（parallel tool calls）对应工具一次性全部提交，不许只办一个就回复，也严禁一轮只调一个、分多个轮次逐个办。例如"明天下午两点和王总开会，顺便把他号码138xxxx记一下"，同一轮里既调 create_item 又调 save_memory；"周五去见客户，记得提前把方案发我"，建周五的拜访日程，同时建一条"发方案"待办。若用户一句话要建多个事项，必须在同一个响应中并发多个 create_item。
            20. 【主动服务与关键信息缺失】像真人秘书一样多想一步：会议关心时间、地点、参会人、要不要提前准备材料或订会议室；出差拜访关心交通缓冲；生日纪念日关心祝福和礼物。用户只说"要开个会/安排个会"但信息不全时：连哪天、开多久都推断不出来，就先用一条消息问最关键的问题（哪天、大概多久），不要建信息残缺的空壳事项；能推断日期和时长线索（含默认一小时）时，按规则9主动调 find_free_slots 挑合适空档直接给出排期方案，用户可在确认卡片上否决或改时间，并在同一条回复里顺带问一句"要不要提前准备材料/需要通知谁吗"，用户答应才建准备类待办，没答应不建。
            21. 【追问纪律】所有追问合并成一条消息、最多两个、只问真正影响安排的高价值问题；用户说不用或没有回答，就不再追问，等他以后主动提起；禁止连环盘问。
            22. 【排期偏好】调 find_free_slots 前先查看长期记忆里的作息偏好：用户不喜欢夜间安排就把 latest 提前（如18:00），上午要专注不排会就把 earliest 推到中午后（如13:00），绝不要把会排进用户明确表示不喜欢的时段；没有相关记忆时用默认时间窗。
            23. 【全天合理性评审】用户说"看看我某天安排合理吗/帮我检查下日程/这么排行不行"时必须调 review_day_plan；创建或改期后工具结果出现"系统提示：当天已有N场……"且你判断当天可能偏密时，调一次 review_day_plan，每轮对话最多一次、禁止重复评审。结果里带"可执行改期"的问题，立即按建议的 id、日期、时间调 update_item（可同一轮并发多个），系统会弹确认卡片让用户逐条审核；只有文字提示的问题用口语转告并给出你的建议。回复时先讲你发现哪里不合理、建议怎么调；评审没有问题就明确告诉用户安排合理，不许为了凑建议而改动，严禁移动没有建议依据的事项。
            24. 【确认卡片被拒】工具结果若提示"用户取消/没有确认/没有入库/系统拦截"，说明该操作完全没有执行：严禁声称已完成、已创建、已记下、已修改、已取消或已删除，也严禁立刻重新调用同一个工具重试；直接如实告诉用户操作已取消、没有生效即可。
        """.trimIndent()
    }

    /** create_item 解析结果：实体 + 需模型向用户追问/确认的异常提示 */
    private data class ParsedItem(val item: ItemEntity, val warnings: List<String>)

    /** 将 create_item 参数解析为实体；日期时间归一化，无法识别的字段置空并给出警告 */
    private fun parseItem(args: JSONObject, imageBytes: ByteArray?): ParsedItem {
        val now = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        val type = when (args.optString("type").uppercase()) {
            ItemType.TODO -> ItemType.TODO
            ItemType.PLAN -> ItemType.PLAN
            ItemType.WISH -> ItemType.WISH
            else -> ItemType.SCHEDULE
        }
        val remindMinutes = if (args.isNull("remind_minutes")) null
        else args.optInt("remind_minutes", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val title = args.optString("title", "未命名事项").ifBlank { "未命名事项" }

        // 日期/时间容错归一化：模型偶尔回传 2026/9/18、9月18日、下午3点半 等写法
        val rawDate = args.optString("date").ifBlank { null }
        val rawTime = args.optString("time").ifBlank { null }
        val date = DateTimeNormalizer.normalizeDate(rawDate)
        val time = DateTimeNormalizer.normalizeTime(rawTime)
        if (rawDate != null && date == null) {
            warnings.add("日期「$rawDate」无法识别，已置空；请用自然语言向用户确认真实日期后再更新。")
        }
        if (rawTime != null && time == null) {
            warnings.add("时间「$rawTime」无法识别，已置空；请用自然语言向用户确认真实时间后再更新。")
        }

        val isAllDay = args.optBoolean("is_all_day", false)
        // 兜底：无日期/无时间/全天事项不具备系统提醒资格，强制关闭，防止假开启
        val eligible = reminderEligible(date, time, isAllDay)

        // 结束时间仅属于"日程 + 具体开始时间"；待办/计划/心愿即便有截止时间也不保存结束时间
        val rawEndTime = args.optString("end_time").ifBlank { null }
        val endTime = if (type == ItemType.SCHEDULE && eligible && rawEndTime != null) {
            DateTimeNormalizer.normalizeTime(rawEndTime).also {
                if (it == null) warnings.add("结束时间「$rawEndTime」无法识别，已置空。")
            }
        } else null

        val priority = when (args.optString("priority").uppercase()) {
            "HIGH" -> Priority.HIGH
            "LOW" -> Priority.LOW
            else -> Priority.NORMAL
        }

        // 默认提醒（仅 AI 创建的日程，用户未显式提提醒时）：
        // 重要提前 1 天、普通提前 30 分钟、低优先级不提醒；待办/计划/心愿不主动开
        val effectiveRemindMinutes = when {
            !eligible -> null
            remindMinutes != null -> remindMinutes
            type == ItemType.SCHEDULE -> when (priority) {
                Priority.HIGH -> DEFAULT_REMIND_HIGH_MINUTES
                Priority.NORMAL -> DEFAULT_REMIND_NORMAL_MINUTES
                else -> null
            }
            else -> null
        }

        val item = ItemEntity(
            type = type,
            title = title,
            description = args.optString("description").ifBlank { null },
            itemDate = date,
            itemTime = time,
            endTime = endTime,
            isAllDay = isAllDay,
            location = args.optString("location").ifBlank { null },
            attendees = args.optString("attendees").trim().ifBlank { null },
            scope = when (args.optString("scope").uppercase()) {
                Scope.WORK -> Scope.WORK
                else -> Scope.PERSONAL
            },
            priority = priority,
            recurrence = when (args.optString("recurrence").uppercase()) {
                Recurrence.DAILY -> Recurrence.DAILY
                Recurrence.WEEKLY -> Recurrence.WEEKLY
                Recurrence.MONTHLY -> Recurrence.MONTHLY
                Recurrence.YEARLY -> Recurrence.YEARLY
                else -> Recurrence.NONE
            },
            reminderEnabled = effectiveRemindMinutes != null,
            remindMinutes = effectiveRemindMinutes,
            status = Status.ACTIVE,
            source = if (imageBytes != null) Source.AI_IMAGE else Source.AI_TEXT,
            sourceImage = imageBytes,
            createdAt = now
        )

        // 过去时间感知：单次、有具体时刻且已过点（容差 1 分钟）→ 提示模型与用户确认，不默默收下
        if (item.recurrence == Recurrence.NONE && eligible) {
            val start = runCatching { dateTimeFmt.parse("${date} ${time}")?.time }.getOrNull()
            if (start != null && start < now - MILLIS_ONE_MINUTE) {
                warnings.add(
                    "事项开始时间（${date} ${time}）已经过去了，系统提醒将不会响；" +
                        "请向用户确认他指的是不是明天或其他时间，确认后用 update_item 改期。"
                )
            }
        }
        return ParsedItem(item, warnings)
    }

    /**
     * 把 messages 中所有多模态（含 base64 图片）user 消息降级为纯文本：
     * 提取其中的 text 片段拼接，纯图片消息用"[图片]"占位。
     * 在视觉模型首轮返回工具调用后执行——图片信息已结构化进工具参数，后续轮次无需再看图，
     * 这样图片全程只上传一次（历史图片本来就是占位，不受影响）。
     */
    private fun stripVisionImages(messages: JSONArray) {
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") != "user") continue
            val content = msg.opt("content") as? JSONArray ?: continue
            val text = buildString {
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "text") {
                        append(part.optString("text"))
                    }
                }
            }.ifBlank { "[图片]" }
            msg.put("content", text)
        }
    }

    private fun buildUserMessage(text: String, imageBase64: String?): JSONObject {
        if (imageBase64 == null) {
            return JSONObject().put("role", "user").put("content", text)
        }
        val content = JSONArray()
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        content.put(
            JSONObject().put("type", "image_url").put(
                "image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
            )
        )
        return JSONObject().put("role", "user").put("content", content)
    }

    private fun toolResultMessage(toolCallId: String, result: String): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", toolCallId)
            .put("content", result)

    private fun normalizeCategory(raw: String): String = when (raw.uppercase()) {
        MemoryCategory.CONTACT -> MemoryCategory.CONTACT
        MemoryCategory.ANNIVERSARY -> MemoryCategory.ANNIVERSARY
        MemoryCategory.NOTE -> MemoryCategory.NOTE
        else -> MemoryCategory.PROFILE
    }

    private fun typeLabel(type: String): String = when (type) {
        ItemType.SCHEDULE -> "日程"
        ItemType.TODO -> "待办"
        ItemType.PLAN -> "计划"
        ItemType.WISH -> "心愿"
        else -> "事项"
    }

    private fun memoryLabel(category: String): String = when (category) {
        MemoryCategory.PROFILE -> "画像"
        MemoryCategory.CONTACT -> "人脉"
        MemoryCategory.ANNIVERSARY -> "纪念日"
        MemoryCategory.NOTE -> "笔记"
        else -> "记忆"
    }

    companion object {
        // 多意图并发 + 全天评审改期 + 二次确认续跑会占更多轮次，放宽到 16
        private const val MAX_ROUNDS = 16
        private const val MILLIS_ONE_MINUTE = 60L * 1000L

        // AI 创建日程时用户未提提醒的默认提前量：重要提前 1 天，普通提前 30 分钟
        private const val DEFAULT_REMIND_HIGH_MINUTES = 1440
        private const val DEFAULT_REMIND_NORMAL_MINUTES = 30
        // 纯图片消息引导：图中有安排就提取建事项，否则才描述图片
        private const val DEFAULT_IMAGE_PROMPT =
            "如果这张图片里包含时间、地点、待办、会议、截止日期等日程安排信息，" +
                "请提取出来并按正常流程创建事项；如果没有任何安排信息，就用简短的中文介绍图片内容。"
        private const val MILLIS_30_MINUTES = 30L * 60L * 1000L
    }
}
