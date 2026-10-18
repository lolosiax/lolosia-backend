package moe.lolosia.web.service.anime

import kotlin.time.Instant

/**
 * MioBT 系发布站 RSS 的**手写**解析器（见 `doc/anime-timetable-design.md` §2.1）。
 *
 * Native 上没有 XML 库，故只认本项目用到的 `<item>` 与几个标签，不做通用 XML；
 * 逐字段容错：缺字段不崩、整条坏就跳过。`guid` 缺省时回退 `link`，两者都没有才丢弃。
 *
 * @author 洛洛希雅Lolosia
 */
object AnimeRssParser {

    /** 解析出的一条发布（原始字段；集数与匹配在采集服务里做）。 */
    data class RawRelease(
        val guid: String,
        val groupName: String,
        val title: String,
        val category: String,
        val torrentUrl: String?,
        val detailUrl: String?,
        val publishedAt: Instant?,
    )

    private val ITEM = Regex("<item[^>]*>([\\s\\S]*?)</item>")
    private val ENCLOSURE = Regex("<enclosure[^>]*url=\"([^\"]*)\"")
    private val RFC822 = Regex("(\\d{1,2})\\s+([A-Za-z]{3})\\s+(\\d{4})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\s*([+-]\\d{4})")

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    /** 解析整份 RSS 文档，返回所有可用的发布条目。 */
    fun parse(xml: String): List<RawRelease> = ITEM.findAll(xml).mapNotNull { parseItem(it.groupValues[1]) }.toList()

    private fun parseItem(block: String): RawRelease? {
        val title = tag(block, "title") ?: return null
        val link = tag(block, "link")
        val guid = tag(block, "guid").orEmpty().ifBlank { link.orEmpty() }
        if (guid.isBlank()) return null
        return RawRelease(
            guid = guid,
            groupName = tag(block, "author").orEmpty(),
            title = title,
            category = tag(block, "category").orEmpty(),
            torrentUrl = ENCLOSURE.find(block)?.groupValues?.get(1),
            detailUrl = link,
            publishedAt = tag(block, "pubDate")?.let(::parseRfc822),
        )
    }

    /** 取一个标签的文本（剥 CDATA、反转义实体）；缺失返回 `null`。 */
    private fun tag(block: String, name: String): String? {
        val raw = Regex("<$name[^>]*>([\\s\\S]*?)</$name>").find(block)?.groupValues?.get(1) ?: return null
        return clean(raw).ifBlank { null }
    }

    private fun clean(raw: String): String {
        var s = raw.trim()
        if (s.length >= 12 && s.startsWith("<![CDATA[") && s.endsWith("]]>")) s = s.substring(9, s.length - 3)
        return unescape(s).trim()
    }

    private fun unescape(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                append(c)
                i++
                continue
            }
            val end = text.indexOf(';', i + 1)
            if (end < 0) {
                append(c)
                i++
                continue
            }
            when (val entity = text.substring(i + 1, end)) {
                "amp" -> append('&')
                "lt" -> append('<')
                "gt" -> append('>')
                "quot" -> append('"')
                "apos" -> append('\'')
                "nbsp" -> append(' ')
                else -> {
                    val code = if (entity.startsWith("#x") || entity.startsWith("#X")) {
                        entity.substring(2).toIntOrNull(16)
                    } else if (entity.startsWith("#")) {
                        entity.substring(1).toIntOrNull()
                    } else {
                        null
                    }
                    if (code != null) append(code.toChar()) else append('&').append(entity).append(';')
                }
            }
            i = end + 1
        }
    }

    /**
     * 解析 RFC-822 时间（RSS `pubDate`，如 `Tue, 13 Oct 2026 20:40:02 +0800`）为 UTC [Instant]。
     *
     * 只做纯整数运算（`daysFromCivil`），不依赖平台时区。
     */
    private fun parseRfc822(raw: String): Instant? {
        val m = RFC822.find(raw) ?: return null
        val (day, monthName, year, hh, mm, ss, off) = m.destructured
        val month = MONTHS[monthName.lowercase()] ?: return null
        val y = year.toInt()
        val mo = month
        val d = day.toInt()
        val epochSeconds = daysFromCivil(y, mo, d) * 86400L +
            hh.toInt() * 3600L + mm.toInt() * 60L + ss.toInt() - offsetSeconds(off)
        return Instant.fromEpochSeconds(epochSeconds)
    }

    /** `+0800` → 28800 秒。 */
    private fun offsetSeconds(offset: String): Long {
        val sign = if (offset.startsWith("-")) -1L else 1L
        val digits = offset.substring(1)
        val h = digits.substring(0, 2).toIntOrNull() ?: 0
        val min = digits.substring(2, 4).toIntOrNull() ?: 0
        return sign * (h * 3600L + min * 60L)
    }

    /** Howard Hinnant 的 days_from_civil：公历 (y, m, d) → 距 1970-01-01 的天数。 */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + (if (month > 2) -3 else 9)).toLong()
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}
