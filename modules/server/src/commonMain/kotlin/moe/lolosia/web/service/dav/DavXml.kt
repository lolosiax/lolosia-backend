package moe.lolosia.web.service.dav

import io.ktor.http.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.FileMetadata
import kotlin.time.Instant

/** 星期与月份的英文缩写（HTTP-date 用）。 */
private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private const val MILLIS_PER_DAY = 86_400_000L

/** PROPFIND / PROPPATCH 的响应 MIME（Windows 认 `application/xml`，且期望 UTF-8）。 */
val DAV_XML: ContentType = ContentType.parse("application/xml; charset=utf-8")

/**
 * epoch 毫秒 → HTTP-date（RFC 1123，如 `Wed, 15 Nov 1995 04:58:08 GMT`）。
 *
 * 星期几由「1970-01-01 是周四」的纪元天数推算，不依赖日期库的枚举下标。
 */
fun httpDate(epochMillis: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC)
    val weekday = WEEKDAYS[((epochMillis.floorDiv(MILLIS_PER_DAY) + 4) % 7 + 7).toInt() % 7]
    return "$weekday, ${pad(t.day)} ${MONTHS[t.month.ordinal]} ${t.year} " +
        "${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)} GMT"
}

/** ISO-8601（`creationdate` 用），如 `2026-10-08T01:56:56Z`。 */
fun isoDate(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString()

private fun pad(value: Int): String = if (value < 10) "0$value" else "$value"

/** 转义 XML 文本与属性值里的五个特殊字符。 */
fun escapeXml(text: String): String {
    if (text.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return text
    return buildString(text.length + 16) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

/** 把挂载前缀 [base] 与相对分段拼成 href（逐段 URL 编码；集合以 `/` 结尾）。 */
fun davHref(base: String, segments: List<String>, isCollection: Boolean): String {
    val encoded = segments.joinToString("/") { it.encodeURLPathPart() }
    val path = if (encoded.isEmpty()) "$base/" else "$base/$encoded"
    return if (isCollection && !path.endsWith("/")) "$path/" else path
}

/** 实体标签：大小 + 修改时间，与静态资源那边一样的思路。 */
fun etagOf(meta: FileMetadata): String = "\"${meta.size ?: 0L}-${meta.lastModifiedAtMillis ?: 0L}\""

/**
 * 单个 `<D:response>`：返回客户端关心的核心属性。
 *
 * Windows 只取其中一部分，多给不影响；`getetag` / `getlastmodified` 是缓存与「文件是否变化」
 * 判定的关键，必须给全。
 */
fun davResponseXml(href: String, meta: FileMetadata, isCollection: Boolean): String {
    val name = href.trimEnd('/').substringAfterLast('/')
    val modified = meta.lastModifiedAtMillis
    val created = meta.createdAtMillis ?: modified
    return buildString(512) {
        append("<D:response><D:href>").append(escapeXml(href)).append("</D:href>")
        append("<D:propstat><D:prop>")
        append("<D:displayname>").append(escapeXml(name)).append("</D:displayname>")
        if (isCollection) {
            append("<D:resourcetype><D:collection/></D:resourcetype>")
        } else {
            append("<D:resourcetype/>")
            append("<D:getcontentlength>").append(meta.size ?: 0L).append("</D:getcontentlength>")
            append("<D:getcontenttype>").append(escapeXml(contentTypeOf(name))).append("</D:getcontenttype>")
        }
        if (modified != null) append("<D:getlastmodified>").append(httpDate(modified)).append("</D:getlastmodified>")
        if (created != null) append("<D:creationdate>").append(isoDate(created)).append("</D:creationdate>")
        append("<D:getetag>").append(escapeXml(etagOf(meta))).append("</D:getetag>")
        // Class 2 能力声明：客户端据此判断可以加锁写入
        append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>")
        append("<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
        append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }
}

/** XML 文档声明（UTF-8）。 */
const val XML_DECL: String = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"

private fun contentTypeOf(name: String): String = ContentType.defaultForFilePath(name).toString()
