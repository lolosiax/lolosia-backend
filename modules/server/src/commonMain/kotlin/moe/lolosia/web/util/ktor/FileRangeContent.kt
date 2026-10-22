package moe.lolosia.web.util.ktor

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import okio.FileHandle

private const val RANGE_BUF_SIZE = 64 * 1024

/**
 * 把 [open] 拿到的文件句柄从 [offset] 起、抄 [length] 字节写进响应体。
 *
 * 分块读走 [FileHandle] 的**定位读**（POSIX 的 `pread`），不是「打开再一路 `skip(offset)`」：
 * okio 的 `BufferedSource.skip` 是「读出来丢掉」，要先把 offset 之前的字节全读一遍才停 —— 几百 MB
 * 的视频取一次尾部（MP4 的 `moov` 常在文件末尾）得等几十秒，文件越大越慢。定位读是 O(1)，与文件大小无关。
 *
 * 用 [OutgoingContent.WriteChannelContent] 而不是 [ByteArrayContent]：视频动辄几百 MB，
 * 整个读进内存会把板子撑爆，也没法边播边取。`Content-Range` / `Accept-Ranges` 等响应头
 * 由调用方在返回本对象**之前**设置好（响应一旦开始写出就改不动了）。
 *
 * [open] 是工厂而不是已打开的句柄：真正的读取发生在框架调用 [writeTo] 时，
 * 万一这之前请求就断了，也不会白白占着一个文件句柄。
 *
 * @author 洛洛希雅Lolosia
 */
internal class FileRangeContent(
    private val length: Long,
    private val offset: Long,
    override val contentType: ContentType,
    override val status: HttpStatusCode,
    private val open: () -> FileHandle,
) : OutgoingContent.WriteChannelContent() {

    override val contentLength: Long get() = length

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val handle = open()
        try {
            writeRange(handle, offset, length, channel)
        } finally {
            handle.close()
        }
    }
}

/** 从 [handle] 的 [offset] 起把 [length] 字节写进 [channel]（文件直链与 WebDAV 的 GET 共用）。 */
internal suspend fun writeRange(handle: FileHandle, offset: Long, length: Long, channel: ByteWriteChannel) {
    val buffer = ByteArray(RANGE_BUF_SIZE)
    var position = offset
    var remaining = length
    while (remaining > 0) {
        val read = handle.read(position, buffer, 0, minOf(RANGE_BUF_SIZE.toLong(), remaining).toInt())
        if (read <= 0) break
        channel.writeFully(buffer, 0, read)
        position += read
        remaining -= read
    }
}
