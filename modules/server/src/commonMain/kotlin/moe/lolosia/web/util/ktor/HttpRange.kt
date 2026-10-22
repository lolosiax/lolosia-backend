package moe.lolosia.web.util.ktor

/**
 * HTTP `Range` 请求头的解析：只支持单段 `bytes=a-b` / `bytes=a-` / `bytes=-n`。
 *
 * WebDAV 的 GET 与文件管理器的原图直链（`/api/files/raw`）共用这一份。视频播放器要靠它分段取流：
 * 没有 206 就只能把整个文件读进内存，既拖不动进度条，也会把板子撑爆。
 *
 * 多段范围（`bytes=0-1,5-6`）与非法格式一律按「整文件」处理 —— 播放器不会发多段请求。
 *
 * @author 洛洛希雅Lolosia
 */

/** Range 解析结果。 */
internal sealed interface RangeParse {
    data object None : RangeParse
    data object Unsatisfiable : RangeParse
    data class Ok(val start: Long, val endInclusive: Long) : RangeParse
}

/** 解析 `Range` 头；[size] 是文件总字节数。 */
internal fun parseRange(header: String?, size: Long): RangeParse {
    if (header == null || !header.startsWith("bytes=", ignoreCase = true)) return RangeParse.None
    val spec = header.substring(6).substringBefore(',').trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return RangeParse.None
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()
    return try {
        if (startText.isEmpty()) {
            // 后缀范围：取最后 n 字节
            val n = endText.toLong()
            if (n <= 0 || size == 0L) RangeParse.Unsatisfiable
            else RangeParse.Ok((size - n).coerceAtLeast(0), size - 1)
        } else {
            val start = startText.toLong()
            val end = if (endText.isEmpty()) size - 1 else endText.toLong().coerceAtMost(size - 1)
            if (start > end || start >= size) RangeParse.Unsatisfiable else RangeParse.Ok(start, end)
        }
    } catch (_: NumberFormatException) {
        RangeParse.None
    }
}
