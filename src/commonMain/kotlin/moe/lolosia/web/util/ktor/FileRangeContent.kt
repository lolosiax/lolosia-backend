package moe.lolosia.web.util.ktor

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import okio.BufferedSource

private const val RANGE_BUF_SIZE = 64 * 1024

/**
 * 把 [sourceOf] 打开的文件流按 [offset] / [length] 分块写进响应体。
 *
 * 用 [OutgoingContent.WriteChannelContent] 而不是 [ByteArrayContent]：视频动辄几百 MB，
 * 整个读进内存会把板子撑爆，也没法边播边取。`Content-Range` / `Accept-Ranges` 等响应头
 * 由调用方在返回本对象**之前**设置好（响应一旦开始写出就改不动了）。
 *
 * [sourceOf] 是工厂而不是已打开的 [BufferedSource]：真正的读取发生在框架调用 [writeTo] 时，
 * 万一这之前请求就断了，也不会白白占着一个文件句柄。
 *
 * @author 洛洛希雅Lolosia
 */
internal class FileRangeContent(
    private val length: Long,
    private val offset: Long,
    override val contentType: ContentType,
    override val status: HttpStatusCode,
    private val sourceOf: () -> BufferedSource,
) : OutgoingContent.WriteChannelContent() {

    override val contentLength: Long get() = length

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val source = sourceOf()
        try {
            if (offset > 0) source.skip(offset)
            val buffer = ByteArray(RANGE_BUF_SIZE)
            var remaining = length
            while (remaining > 0) {
                val read = source.read(buffer, 0, minOf(RANGE_BUF_SIZE.toLong(), remaining).toInt())
                if (read == -1) break
                channel.writeFully(buffer, 0, read)
                remaining -= read
            }
        } finally {
            source.close()
        }
    }
}
