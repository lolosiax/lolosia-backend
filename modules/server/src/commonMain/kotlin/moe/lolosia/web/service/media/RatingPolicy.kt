package moe.lolosia.web.service.media

import moe.lolosia.web.database.model.RATING_EXPLICIT
import moe.lolosia.web.database.model.RATING_QUESTIONABLE
import moe.lolosia.web.database.model.RATING_SAFE
import moe.lolosia.web.database.model.RATING_UNKNOWN

/**
 * 评级的取值域与**外发闸门**（`doc/media-index-design.md` §7.2 / §10.2）。
 *
 * 评级只有人工与规则两个来源，模型永不参与 —— 它是**合规闸门**而不是标签：
 * 「自动判错」把内容送出去，比人工漏标贵得多。
 *
 * @author 洛洛希雅Lolosia
 */
object RatingPolicy {

    /** 四档评级，顺序即「从松到严」。 */
    val ALL: List<String> = listOf(RATING_UNKNOWN, RATING_SAFE, RATING_QUESTIONABLE, RATING_EXPLICIT)

    /** 需要遮罩的评级（前端 `blurred` 与直链 `NSFW` 位的判定口径）。 */
    val BLURRED: Set<String> = setOf(RATING_QUESTIONABLE, RATING_EXPLICIT)

    /** 归一化外部输入；认不出来的值一律落到 `unknown`（不静默扩枚举，见 §16-4）。 */
    fun normalize(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        return if (value in ALL) value else RATING_UNKNOWN
    }

    /**
     * 外发闸门：是否禁止把该资产交给外部分析。
     *
     * `analysis_skip` 取「属性页显式勾选」与「`rating = explicit`」的 **OR** —— 任一为真就禁止；
     * 反向不成立（「这张不涉黄，但我就是不想让它上云」是合法状态）。
     */
    fun outboundBlocked(rating: String, analysisSkip: Boolean): Boolean =
        analysisSkip || normalize(rating) == RATING_EXPLICIT

    /** 该评级是否需要遮罩。 */
    fun needsBlur(rating: String): Boolean = normalize(rating) in BLURRED
}
