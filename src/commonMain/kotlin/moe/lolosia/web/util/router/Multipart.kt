package moe.lolosia.web.util.router

/**
 * `multipart/form-data` 解析器（纯字节扫描）。
 *
 * 不处理 `Content-Transfer-Encoding`、嵌套 multipart 与超长头部。
 *
 * @author 洛洛希雅Lolosia
 */

private const val CR: Byte = 13
private const val LF: Byte = 10

private val CRLF = byteArrayOf(CR, LF)

/** 从 `multipart/form-data; boundary=xxx` 里取出 boundary（兼容带引号写法）。 */
fun parseMultipartBoundary(contentType: String): String? {
    val key = "boundary="
    val idx = contentType.indexOf(key, ignoreCase = true)
    if (idx < 0) return null
    var value = contentType.substring(idx + key.length).trim()
    if (value.startsWith("\"")) {
        val end = value.indexOf('"', 1)
        if (end > 0) value = value.substring(1, end)
    } else {
        val end = value.indexOf(';')
        if (end >= 0) value = value.substring(0, end).trim()
    }
    return value.ifBlank { null }
}

/** 解析 multipart 请求体。 */
fun parseMultipartBody(body: ByteArray, boundary: String): FormData {
    val fields = mutableMapOf<String, String>()
    val files = mutableListOf<UploadedFile>()

    val delimiter = "--$boundary".encodeToByteArray()
    var pos = body.indexOfSeq(delimiter, 0)
    if (pos < 0) return FormData(fields, files)
    pos += delimiter.size

    while (pos <= body.size) {
        // 结束分隔符是 "--boundary--"
        if (pos + 1 < body.size && body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) break

        pos = body.skipCrlf(pos)

        // 头部逐行扫描直到空行（空行本身即头部结束标记）
        var lineStart = pos
        var headerEnd = body.size
        while (lineStart < body.size) {
            val lineEnd = body.indexOfSeq(CRLF, lineStart)
            if (lineEnd < 0) break
            if (lineEnd == lineStart) {
                headerEnd = lineStart
                break
            }
            lineStart = lineEnd + CRLF.size
        }
        val contentStart = if (headerEnd >= body.size) body.size else headerEnd + CRLF.size
        val headerText = body.decodeToString(pos, minOf(headerEnd, body.size))
        val headers = parsePartHeaders(headerText)

        // 该 part 的内容结束于下一个 "\r\n--boundary"
        val nextBoundary = body.indexOfSeq(CRLF + delimiter, contentStart)
        val contentEnd = if (nextBoundary < 0) body.size else nextBoundary
        val content = body.copyOfRange(contentStart, contentEnd)

        val disposition = headers["content-disposition"]
        val name = disposition?.let { extractDispositionParam(it, "name") }
        val fileName = disposition?.let { extractDispositionParam(it, "filename") }
        val contentType = headers["content-type"]

        if (name != null) {
            if (fileName != null) {
                files += UploadedFile(
                    fieldName = name,
                    fileName = fileName,
                    contentType = contentType,
                    bytes = content,
                )
            } else {
                fields[name] = content.decodeToString()
            }
        }

        if (nextBoundary < 0) break
        // 越过 CRLF + delimiter
        pos = nextBoundary + CRLF.size + delimiter.size
    }

    return FormData(fields, files)
}

private fun ByteArray.matchesAt(start: Int, needle: ByteArray): Boolean {
    if (start + needle.size > size) return false
    for (i in needle.indices) if (this[start + i] != needle[i]) return false
    return true
}

private fun ByteArray.indexOfSeq(needle: ByteArray, start: Int): Int {
    if (needle.isEmpty() || start < 0) return -1
    for (i in start..(size - needle.size)) {
        if (matchesAt(i, needle)) return i
    }
    return -1
}

private fun ByteArray.skipCrlf(from: Int): Int {
    var p = from
    while (p < size && (this[p] == CR || this[p] == LF)) p++
    return p
}

/** 解析 part 的头部行，key 统一小写。 */
private fun parsePartHeaders(text: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    text.split("\r\n").forEach { line ->
        if (line.isBlank()) return@forEach
        val colon = line.indexOf(':')
        if (colon <= 0) return@forEach
        result[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    return result
}

/** 从 `form-data; name="file"; filename="a.png"` 里取参数。 */
private fun extractDispositionParam(header: String, key: String): String? {
    val idx = header.indexOf("$key=\"", ignoreCase = true)
    if (idx < 0) return null
    val start = idx + key.length + 2
    val end = header.indexOf('"', start)
    if (end < 0) return null
    return header.substring(start, end)
}
