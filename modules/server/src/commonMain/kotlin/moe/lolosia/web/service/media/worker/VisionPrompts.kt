package moe.lolosia.web.service.media.worker

/**
 * 阶段 A 的系统提示与用户指令（短键名 + 强制 JSON + 低输出，见 `doc/vision-autotag-design.md` §8）。
 *
 * 只把**开启的维度**写进提示，避免模型输出用不上的字段浪费输出 token；
 * `i`（图片序号）恒有 —— 批量请求靠它与输入图片对齐。
 *
 * @author 洛洛希雅Lolosia
 */
object VisionPrompts {

    /** 组装系统提示。 */
    fun system(
        composition: Boolean,
        validity: Boolean,
        subject: Boolean,
        caption: Boolean,
        attributes: Boolean,
        objects: Boolean,
        events: Boolean,
        actions: Boolean,
    ): String = buildString {
        appendLine("你是图像标注器，只输出 JSON，不要任何解释、前后缀或 Markdown 代码围栏。")
        appendLine("对输入的每一张图片输出一条记录，聚合成一个 JSON 数组，元素顺序必须与图片输入顺序一致。")
        appendLine()
        appendLine("字段含义：")
        appendLine("- i：图片序号（整数，从 0 开始，与输入顺序一致）")
        if (composition) appendLine("- c：画面中的人数，取值之一：0、1、2、3、many")
        if (validity) appendLine("- v：是否为有效内容（布尔）。纯色 / 模糊废片 / 截图 / 严重遮挡 / 过度曝光等无有效画面时为 false")
        if (subject) appendLine("- s：题材，取值之一：vrchat、doll、misc")
        if (caption) appendLine("- d：一句话描述（不超过 30 个汉字，只陈述画面里发生了什么，不评价、不推测人物身份）")
        if (attributes) appendLine("- a：画面的角色视觉属性数组（如 [\"粉发\",\"双马尾\",\"和服\"]）；无明显角色属性时给空数组 []")
        if (objects) appendLine("- o：画面里的显著物件 / 道具 / 场景元素数组（如 [\"蛋糕\",\"气球\",\"黑板\",\"塔罗牌\"]）；没有则给空数组 []")
        if (events) appendLine("- e：这场合 / 活动是什么（如 [\"生日会\",\"合影留念\"]）；没有则给空数组 []")
        if (actions) appendLine("- x：画面里正在做的事 / 动作数组（如 [\"比耶\",\"切蛋糕\",\"举杯\"]）；没有则给空数组 []")
        appendLine()
        appendLine("每张图前会附上该图的路径与已有信息（已有标签 / 已有描述 / 文件夹备注），请当作参考：")
        appendLine("- 已有标签若仍然成立就继续输出；发现遗漏可补充；确实不成立可不再输出（不要凭空否定）。")
        appendLine("- 已有描述只作改写基础，让它更准更简洁，不必照抄。")
        if (events) {
            appendLine("- 路径与文件夹名是判断「场合 / 活动」（e）的**重要依据**：如路径含「生日会」就应给出 e:[\"生日会\"]。")
            appendLine("  也可以结合画面里正在发生的事（望文生义），给路径里没明说、但画面明确支持的 e（如举杯切蛋糕 → 生日会）。")
        }
        appendLine("- 路径与文件夹备注不要原样抄进描述（d）。")
        appendLine()
        append("输出示例：")
        append(sample(composition, validity, subject, caption, attributes, objects, events, actions))
    }

    /** 用户指令（图片按序跟在后面）。 */
    fun user(count: Int): String =
        "下面共有 $count 张图片，已按顺序编号 0 至 ${count - 1}，请逐张标注并输出 JSON 数组。"

    /**
     * 单张图的前导文本（紧接该图的图片内容）。
     *
     * 把「已有分析结果」摆到模型眼前，它才能判断「沿用 / 增补标签、改写描述」，而不是从零重来。
     */
    fun image(
        index: Int,
        path: String,
        tags: List<String>,
        caption: String?,
        folderRemark: String?,
    ): String = buildString {
        append("图$index（路径：$path")
        if (tags.isNotEmpty()) append("；已有标签：${tags.joinToString("、")}")
        if (!caption.isNullOrBlank()) append("；已有描述：$caption")
        if (!folderRemark.isNullOrBlank()) append("；文件夹备注：$folderRemark")
        append("）：")
    }

    private fun sample(
        composition: Boolean,
        validity: Boolean,
        subject: Boolean,
        caption: Boolean,
        attributes: Boolean,
        objects: Boolean,
        events: Boolean,
        actions: Boolean,
    ): String = buildString {
        append("[{\"i\":0")
        if (composition) append(",\"c\":1")
        if (validity) append(",\"v\":true")
        if (subject) append(",\"s\":\"doll\"")
        if (caption) append(",\"d\":\"粉发双马尾角色在夜晚街道上比出剪刀手\"")
        if (attributes) append(",\"a\":[\"粉发\",\"双马尾\"]")
        if (objects) append(",\"o\":[\"路灯\",\"栏杆\"]")
        if (events) append(",\"e\":[\"夜景街拍\"]")
        if (actions) append(",\"x\":[\"比耶\"]")
        append("}]")
    }
}
