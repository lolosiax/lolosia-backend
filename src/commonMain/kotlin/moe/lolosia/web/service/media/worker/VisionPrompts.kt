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
        appendLine()
        append("输出示例：")
        append(sample(composition, validity, subject, caption, attributes))
    }

    /** 用户指令（图片按序跟在后面）。 */
    fun user(count: Int): String =
        "下面共有 $count 张图片，已按顺序编号 0 至 ${count - 1}，请逐张标注并输出 JSON 数组。"

    private fun sample(
        composition: Boolean,
        validity: Boolean,
        subject: Boolean,
        caption: Boolean,
        attributes: Boolean,
    ): String = buildString {
        append("[{\"i\":0")
        if (composition) append(",\"c\":1")
        if (validity) append(",\"v\":true")
        if (subject) append(",\"s\":\"doll\"")
        if (caption) append(",\"d\":\"粉发双马尾角色在夜晚街道上比出剪刀手\"")
        if (attributes) append(",\"a\":[\"粉发\",\"双马尾\"]")
        append("}]")
    }
}
