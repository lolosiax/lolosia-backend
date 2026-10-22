package moe.lolosia.web.service.anime

/**
 * 「RSS 标题 → 番剧」的**纯函数**匹配器（见 `doc/anime-timetable-design.md` §4.2）。
 *
 * 无 IO：把标题做归一化（全角转半角 / 小写 / 只留字母数字与中日文），再与别名表做双向
 * 「包含」判定，命中多条取**最长别名**判胜。归一化长度 < [MIN_NORM] 的别名不参与，防短词误命中。
 *
 * @author 洛洛希雅Lolosia
 */
object AnimeTitleMatcher {

    /** 参与匹配的别名 / 标题最短归一化长度。 */
    private const val MIN_NORM: Int = 4

    /** 一条用于匹配的别名引用。 */
    data class AliasRef(val seriesId: Long, val norm: String)

    private val LEAD_BRACKET = Regex("^\\s*[\\[【][^\\]】]*[\\]】]")
    private val STAR_DECOR = Regex("★[^★]*★")

    /**
     * 在 [aliases] 里为 [title] 找最可能的番剧 id；无法判定返回 `null`。
     *
     * 归一化长度为 0 或别名表为空时直接返回 `null`。
     */
    fun match(title: String, aliases: List<AliasRef>): Long? {
        if (aliases.isEmpty()) return null
        val norm = normalize(stripDecorations(title))
        if (norm.length < MIN_NORM) return null
        var bestId: Long? = null
        var bestLen = 0
        for (alias in aliases) {
            if (alias.norm.length < MIN_NORM) continue
            if (norm.contains(alias.norm) || alias.norm.contains(norm)) {
                if (alias.norm.length > bestLen) {
                    bestLen = alias.norm.length
                    bestId = alias.seriesId
                }
            }
        }
        return bestId
    }

    /** 从发布标题里解析集数；解析不出来返回 `null`（合集 / SP / OVA 等）。 */
    fun extractEpisode(title: String): Int? {
        Regex("第(\\d{1,3})[话話集]").find(title)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("(?:EP|ep)(\\d{1,3})").find(title)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("\\[(\\d{1,3})]").find(title)?.let { return it.groupValues[1].toIntOrNull() }
        return episodeFromDash(title)
    }

    /** 归一化：全角转半角、小写，只保留字母 / 数字 / 中日文，其余（空白与符号）丢弃。 */
    fun normalize(text: String): String = buildString(text.length) {
        for (c in text) {
            val ch = halfWidth(c.lowercaseChar())
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> append(ch)
                ch.code in 0x4E00..0x9FFF -> append(ch) // 中日韩统一表意文字
                ch.code in 0x3040..0x30FF -> append(ch) // 平假名 / 片假名
            }
        }
    }

    /** 剥掉开头的发布组块（`[...]` / `【...】`）与 `★…★` 装饰，供匹配归一化用。 */
    private fun stripDecorations(title: String): String {
        var s = title.trim()
        while (true) {
            val m = LEAD_BRACKET.find(s) ?: break
            if (m.range.first != 0) break
            s = s.substring(m.range.last + 1).trim()
        }
        return STAR_DECOR.replace(s, " ")
    }

    /** 全角 ASCII（`Ａ-Ｚ` 等）与全角空格转半角。 */
    private fun halfWidth(c: Char): Char = when (c.code) {
        0x3000 -> ' '
        in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar()
        else -> c
    }

    /** 匹配 `- NN`（短横后跟空格再跟 1~3 位数字）：取最靠后的一个有效集数。 */
    private fun episodeFromDash(title: String): Int? {
        var result: Int? = null
        var idx = title.indexOf("- ")
        while (idx >= 0) {
            var j = idx + 2
            while (j < title.length && title[j] == ' ') j++
            var k = j
            while (k < title.length && title[k].isDigit() && k - j < 3) k++
            if (k > j && (k >= title.length || !title[k].isDigit())) {
                title.substring(j, k).toIntOrNull()?.let { if (it in 1..999) result = it }
            }
            idx = title.indexOf("- ", idx + 1)
        }
        return result
    }
}
