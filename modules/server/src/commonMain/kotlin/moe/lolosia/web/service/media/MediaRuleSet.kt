package moe.lolosia.web.service.media

import moe.lolosia.web.database.model.RATING_UNKNOWN

/**
 * 一批规则的匹配器。
 *
 * 规则数量是几十条级，但每个文件都要过一遍全部规则，所以**预筛 + 预编译**：整轮扫描只建一次，
 * 之后每次 [ratingFor] / [tagsFor] 都只是若干次字符串比较（`name_glob` 的正则在
 * [MediaRule] 里 `lazy` 编译一次）。
 *
 * @author 洛洛希雅Lolosia
 */
class MediaRuleSet(rules: List<MediaRule>) {

    private val rules: List<MediaRule> = rules.filter { it.pattern.isNotEmpty() }

    /** 参与匹配的规则条数（用于日志）。 */
    val size: Int get() = rules.size

    /**
     * 命中规则给出的评级：多条命中时取 [MediaRule.priority] 最小的那条（§7.3）。
     * 没有任何规则给评级时返回 `null`（调用方保持原值）。
     */
    fun ratingFor(path: String, name: String): String? =
        rules.filter { it.rating != null && it.hits(path, name) }
            .minByOrNull { it.priority }
            ?.rating
            ?.let { RatingPolicy.normalize(it) }
            ?.takeIf { it != RATING_UNKNOWN }

    /** 命中规则给出的标签（`"命名空间:名字"`，已去重）。 */
    fun tagsFor(path: String, name: String): List<String> =
        rules.filter { it.tagParts() != null && it.hits(path, name) }
            .mapNotNull { it.tag }
            .distinct()
}
