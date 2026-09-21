package moe.lolosia.web.util

/**
 * 工具软件包
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */

import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.routing.RoutingContext
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundleScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val mapper = JsonMapper()

/** 业务成功响应 */
fun success(msg: Any? = "success"): Bundle = bundleScope {
    "code" set 200
    "msg" set msg
}

/**
 * 业务异常：携带 HTTP 状态码 + 消息 + 可选数据。
 * 由全局 StatusPages 统一转换为 {code, msg, data} 响应。
 */
class ErrorResponseException(
    val status: HttpStatusCode,
    override val message: String,
    val data: Any? = null,
    override val cause: Throwable? = null
) : RuntimeException(message, cause)

@Suppress("NOTHING_TO_INLINE")
inline fun packageLogger() = packageLogger(null)

/**
 * 获取**调用方**所在包的Logger。
 *
 * 依赖 inline 特性：函数体会被复制进调用方的方法，运行时栈上没有本函数自己的帧，
 * 栈顶第一个应用帧就是调用方，因此能拿到真实的调用方包名（无需传入完整包名）。
 * 仅初始化时调用一次（如 `private val logger = packageLogger("XxxKt")`），栈回溯开销可忽略。
 * @param className 指定类名后缀，如 "TrafficInterceptorKt"
 */
@Suppress("NOTHING_TO_INLINE")
inline fun packageLogger(className: String?): Logger {
    // frame.className 是全限定类名（含类名自身），只需取其包名部分，再拼上 className
    val caller = Throwable().stackTrace.firstOrNull { frame ->
        frame.className != "moe.lolosia.web.util.PackageKt" && !frame.className.startsWith("java.")
    }?.className ?: "moe.lolosia.web.util.PackageKt"

    if (className == null) return LoggerFactory.getLogger(caller)

    val packageName = caller.substringBeforeLast('.')
    return LoggerFactory.getLogger("$packageName.$className")
}

/** 获取所在类的Logger */
@JvmName("packageLoggerReified")
inline fun <reified T> packageLogger(): Logger = LoggerFactory.getLogger(T::class.java)

/**
 * 收集 multipart 中的文件：
 * 在 [forEachPart] 回调内立即读取每个 FileItem 的字节并转存到内存 channel，
 * 之后 dispose 原 part（Ktor 3.4 的 readPart 取下一个 part 时会丢弃上一个的 body）。
 * 这样 service 拿到的 [PartData.FileItem] 始终可读。
 */
internal suspend fun RoutingContext.collectFileItems(): List<PartData.FileItem> {
    val files = mutableListOf<PartData.FileItem>()
    call.receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FileItem -> {
                val bytes = part.provider().readRemaining().readByteArray()
                part.dispose()
                files += PartData.FileItem(
                    provider = { ByteReadChannel(bytes) },
                    dispose = {},
                    part.headers
                )
            }
            else -> part.dispose()
        }
    }
    return files
}

/**
 * 带缓存协商的文件响应：
 * - 若请求带 If-Modified-Since / If-None-Match 且文件未变更 → 304（由 ConditionalHeaders 之外手动处理，适合 respondFile）
 * - 设置 Last-Modified / ETag / Cache-Control
 * - [downloadName] 非空时附加 Content-Disposition: attachment（下载）
 */
suspend fun ApplicationCall.respondFileCached(
    file: File,
    downloadName: String? = null,
    cacheControl: String = "max-age=3600",
) {
    val lastModified = file.lastModified()
    val etag = "\"${lastModified}-${file.length()}\""

    // 304 协商：If-Modified-Since 按 RFC 1123（秒级精度）解析
    val ifModifiedSince = request.headers[HttpHeaders.IfModifiedSince]?.let { header ->
        runCatching {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }
    if (ifModifiedSince != null && lastModified <= ifModifiedSince) {
        response.status(HttpStatusCode.NotModified)
        return
    }
    if (request.headers[HttpHeaders.IfNoneMatch] == etag) {
        response.status(HttpStatusCode.NotModified)
        return
    }

    response.header(HttpHeaders.LastModified, GMTDate(lastModified).toHttpDate())
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, cacheControl)
    if (downloadName != null) {
        response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, downloadName).toString()
        )
    }
    respondFile(file)
}
