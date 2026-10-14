package org.example.myapplication.data.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Function Calling 工具定义（智谱 OpenAI 兼容格式）
 *
 * 共 12 个工具：
 * - create_item     创建事项（日程/待办/计划/心愿）
 * - query_items     查询事项（结果带 id，供修改/取消/删除时精确定位）
 * - update_item     修改事项（部分更新，只改传入字段；仅限有效事项）
 * - complete_item   标记事项完成
 * - cancel_item     标记事项取消（进入归档，可恢复）
 * - delete_item     永久删除事项（系统会强制要求用户确认）
 * - find_free_slots 查询某天空闲时段（安排会议/"找个时间"时调用）
 * - review_day_plan 评审某一天日程安排的合理性（冲突/过载/午休/跨地点/边界），返回具体改期建议
 * - save_memory     保存长期记忆（画像/人脉/纪念日/笔记）
 * - query_memory    查询长期记忆（结果带 id，供删除/修改时精确定位）
 * - update_memory   修改一条长期记忆（部分更新，只改传入字段）
 * - delete_memory   删除一条长期记忆（用户说"忘掉/忘记"时调用）
 */
object AgentTools {

    val JSON: JSONArray = JSONArray().apply {
        put(function("create_item", "创建一个事项（日程/待办/计划/心愿）。当用户表达了任何要做的事、安排、计划、愿望、截止时间时调用。一句话包含多个事项时，必须在同一个响应中并发（parallel tool calls）多个 create_item 一次性全部提交，严禁分多个轮次逐个创建。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("type", JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray().put("SCHEDULE").put("TODO").put("PLAN").put("WISH"))
                        .put("description", "事项类型：SCHEDULE 有日期和开始时间的日程；TODO 有截止时间的待办；PLAN 中长期计划；WISH 无明确时间的心愿"))
                    .put("title", JSONObject().put("type", "string").put("description", "简短标题"))
                    .put("description", JSONObject().put("type", "string").put("description", "详细描述，可空"))
                    .put("date", JSONObject().put("type", "string").put("description", "日期 yyyy-MM-dd；无法确定时留空，不要猜测"))
                    .put("time", JSONObject().put("type", "string").put("description", "时间 HH:mm（24小时制）；日程的开始时间或待办的截止时间，可空"))
                    .put("end_time", JSONObject().put("type", "string").put("description", "结束时间 HH:mm（24小时制），仅日程类事项、有开始时间时填写，可空。用户说时长时直接换算，如开始14:00、开1小时会则填15:00；跨天事项（如23:00开始到次日凌晨1点）填01:00即可。无法判断时长时留空"))
                    .put("is_all_day", JSONObject().put("type", "boolean").put("description", "是否全天事项"))
                    .put("location", JSONObject().put("type", "string").put("description", "地点，可空"))
                    .put("attendees", JSONObject().put("type", "string").put("description", "参与人物，多个用顿号分隔，如\"王总、小李\"，可空"))
                    .put("scope", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("WORK").put("PERSONAL"))
                        .put("description", "事务范围：WORK 工作事务（开会、拜访、出差、工作截止等）；PERSONAL 私人事务（生活、家庭、个人心愿等）。无法判断时可不传"))
                    .put("priority", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("LOW").put("NORMAL").put("HIGH")))
                    .put("recurrence", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("NONE").put("DAILY").put("WEEKLY").put("MONTHLY").put("YEARLY"))
                        .put("description", "重复规则：NONE 不重复/DAILY 每天/WEEKLY 每周/MONTHLY 每月/YEARLY 每年。用户说\"每天/天天\"用 DAILY；\"每周X/周X/每个星期X\"用 WEEKLY；\"每月X号/每个月X号/每月X日\"用 MONTHLY；\"每年X月X日/每年生日\"用 YEARLY；日期填第一个发生日（如\"每周三\"就取下一个周三的日期）。无重复表述时留 NONE"))
                    .put("remind_minutes", JSONObject().put("type", "integer")
                        .put("description", "提前多少分钟提醒：0准时/15/30/60/120/1440。用户主动要求提醒（如\"提醒我/别忘了/叫我/到点告诉我\"）但没说提前多久时传 0（准时提醒）；用户完全没提提醒才留空。仅当有具体时间且非全天时有效，无时间或全天事项不要传"))
                )
                .put("required", JSONArray().put("type").put("title"))
        ))
        put(function("query_items", "按时间范围和类型查询用户的有效事项。用户问有什么安排、计划时调用。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("range", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("today").put("tomorrow").put("week").put("all"))
                        .put("description", "时间范围：today 今天/tomorrow 明天/week 未来7天（含今天）/all 全部"))
                    .put("type", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("SCHEDULE").put("TODO").put("PLAN").put("WISH"))
                        .put("description", "事项类型过滤，可空"))
                )
                .put("required", JSONArray().put("range"))
        ))
        put(function("complete_item", "把一个已有事项标记为完成。用户表示某件事做完了时调用。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("id", JSONObject().put("type", "integer").put("description", "事项 id（query_items 结果中给出，优先使用）"))
                    .put("title", JSONObject().put("type", "string").put("description", "要完成的事项标题关键词（无 id 时使用；匹配到多条时系统会返回候选，需让用户选择）"))
                )
        ))
        put(function(
            "update_item",
            "修改一个已有事项（仅限未完成/未取消的有效事项）。部分更新：只传需要修改的字段，未传字段保持不变；可空字段传 null 表示清空。用户要求改期、改地点、改标题、改优先级等时调用。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("id", JSONObject().put("type", "integer").put("description", "事项 id（query_items 结果中给出，优先使用）"))
                    .put("title_keyword", JSONObject().put("type", "string").put("description", "无 id 时按标题关键词匹配；命中多条时系统返回候选，应让用户选择后用 id 再调用"))
                    .put("type", JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray().put("SCHEDULE").put("TODO").put("PLAN").put("WISH"))
                        .put("description", "修改事项类型"))
                    .put("title", JSONObject().put("type", "string").put("description", "新标题"))
                    .put("description", JSONObject().put("type", "string").put("description", "新的详细描述；传 null 清空"))
                    .put("date", JSONObject().put("type", "string").put("description", "新日期 yyyy-MM-dd；传 null 清空日期"))
                    .put("time", JSONObject().put("type", "string").put("description", "新时间 HH:mm；传 null 清空时间"))
                    .put("is_all_day", JSONObject().put("type", "boolean").put("description", "是否全天"))
                    .put("end_time", JSONObject().put("type", "string").put("description", "新结束时间 HH:mm；传 null 清空；早于开始时间表示次日凌晨结束"))
                    .put("location", JSONObject().put("type", "string").put("description", "新地点；传 null 清空"))
                    .put("attendees", JSONObject().put("type", "string").put("description", "新的参与人物（多个用顿号分隔）；传 null 清空"))
                    .put("scope", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("WORK").put("PERSONAL"))
                        .put("description", "事务范围：WORK 工作 / PERSONAL 私人"))
                    .put("priority", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("LOW").put("NORMAL").put("HIGH")))
                    .put("recurrence", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("NONE").put("DAILY").put("WEEKLY").put("MONTHLY").put("YEARLY")))
                    .put("remind_minutes", JSONObject().put("type", "integer")
                        .put("description", "提前提醒分钟数：0准时/15/30/60/120/1440；传 null 关闭提醒；不传保持不变。用户要求提醒但没说提前多久时给 0。仅当有具体时间且非全天时有效"))
                )
        ))
        put(function("cancel_item", "把一个有效事项标记为取消（进入归档，之后可在日程页恢复）。用户说某件事取消了、不做了时调用。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("id", JSONObject().put("type", "integer").put("description", "事项 id（优先使用）"))
                    .put("title", JSONObject().put("type", "string").put("description", "标题关键词；命中多条时需让用户选择"))
                )
        ))
        put(function("delete_item", "永久删除一个事项（不可恢复，归档中也找不到）。仅当用户明确要求删除时调用，系统会强制弹出确认让用户二次确认。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("id", JSONObject().put("type", "integer").put("description", "事项 id（优先使用）"))
                    .put("title", JSONObject().put("type", "string").put("description", "标题关键词；命中多条时需让用户选择"))
                )
        ))
        put(function("find_free_slots", "查询某一天有哪些空闲时段。当用户说\"找个时间开会/聊聊/碰一下\"、\"我明天什么时候有空\"、\"帮我安排个时间\"且需要先看空档时调用；返回若干可用时间段，再由你结合语境推荐或让用户选择，确定后用 create_item 或 update_item 落库。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("date", JSONObject().put("type", "string").put("description", "查询日期 yyyy-MM-dd；用户说今天/明天等相对日期时换算；不传默认今天"))
                    .put("duration_minutes", JSONObject().put("type", "integer").put("description", "需要的时长（分钟），如开会1小时填60；不传默认60"))
                    .put("earliest", JSONObject().put("type", "string").put("description", "可接受的最早开始时间 HH:mm，默认09:00"))
                    .put("latest", JSONObject().put("type", "string").put("description", "可接受的最晚结束时间 HH:mm，默认18:00"))
                )
        ))
        put(function("review_day_plan", "评审某一天日程安排得是否合理（只读，不改动任何事项）。用户说\"看看我明天/某天安排合理吗\"\"帮我排一下/检查一下日程\"时调用；新建或改期后系统提示该日日程偏密时也应调用，每轮对话最多调用一次。返回当天时间轴、问题清单（时间重叠、赶场太紧、日程过载、午休被占、跨地点通勤、过早过晚）和具体改期建议。拿到带建议的问题后，用 update_item 按建议的 id、date、time、end_time 逐条提交（系统会让用户审核确认）；没有建议依据的事项严禁擅自移动；没有问题时直接口头告诉用户当天安排合理。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("date", JSONObject().put("type", "string").put("description", "评审日期 yyyy-MM-dd；用户说今天/明天/周几等相对日期时换算；不传默认今天"))
                )
        ))
        put(function("save_memory", "保存需要长期记住的信息：用户画像/事实、人脉、纪念日、自由笔记。用户说\"记住/我喜欢/我老婆/生日是\"等时调用。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("category", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("PROFILE").put("CONTACT").put("ANNIVERSARY").put("NOTE"))
                        .put("description", "PROFILE 用户画像与事实；CONTACT 人脉；ANNIVERSARY 纪念日/生日；NOTE 自由笔记"))
                    .put("title", JSONObject().put("type", "string").put("description", "记忆条目标题（如人名、主题）；自由笔记可空"))
                    .put("content", JSONObject().put("type", "string").put("description", "记忆内容"))
                    .put("date", JSONObject().put("type", "string").put("description", "纪念日使用 MM-dd 或 yyyy-MM-dd，其余留空"))
                )
                .put("required", JSONArray().put("category").put("content"))
        ))
        put(function("query_memory", "查询长期记忆（画像事实、人脉、纪念日、笔记）。需要了解用户背景、偏好、重要的人时调用。结果中给出 id，删除/修改记忆时优先用 id 精确定位。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("category", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("PROFILE").put("CONTACT").put("ANNIVERSARY").put("NOTE"))
                        .put("description", "分类过滤，可空表示全部"))
                )
        ))
        put(function("update_memory", "修改一条已有的长期记忆（更正画像、人脉信息、纪念日等）。优先用 id 精确定位；部分更新，只传需要修改的字段，未传字段保持不变。用户更正之前记住的信息时调用，而不是新建一条。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("id", JSONObject().put("type", "integer").put("description", "记忆 id（query_memory 结果中给出，优先使用）"))
                    .put("title_keyword", JSONObject().put("type", "string").put("description", "无 id 时按标题关键词匹配；命中多条时系统返回候选，应让用户选择后用 id 再调用"))
                    .put("category", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("PROFILE").put("CONTACT").put("ANNIVERSARY").put("NOTE"))
                        .put("description", "新的分类"))
                    .put("title", JSONObject().put("type", "string").put("description", "新标题"))
                    .put("content", JSONObject().put("type", "string").put("description", "新内容"))
                    .put("date", JSONObject().put("type", "string").put("description", "纪念日新日期 MM-dd 或 yyyy-MM-dd；传 null 清空日期"))
                )
        ))
        put(function("delete_memory", "删除一条长期记忆。用户说\"忘掉/忘记/别再记得/删除关于……的记忆\"时调用。优先用 id 精确定位；没有 id 时传标题关键词，匹配多条时系统返回候选让用户选择。",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("id", JSONObject().put("type", "integer").put("description", "记忆 id（优先使用）"))
                    .put("title", JSONObject().put("type", "string").put("description", "记忆标题关键词；命中多条时需让用户选择"))
                    .put("category", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("PROFILE").put("CONTACT").put("ANNIVERSARY").put("NOTE"))
                        .put("description", "分类过滤，可空"))
                )
        ))
    }

    private fun function(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters)
            )
}
