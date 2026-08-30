package moe.lolosia.web.util

/**
 * 工具软件包
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */

import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundleScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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

/** 获取所在包的Logger */
@Suppress("NOTHING_TO_INLINE")
inline fun packageLogger() = packageLogger("PackageKt")

/**
 * 获取所在包的Logger
 * @param className 指定一个新的类名
 */
@Suppress("NOTHING_TO_INLINE")
inline fun packageLogger(className: String): Logger {
    return LoggerFactory.getLogger(object {}::class.java.packageName + ".$className")
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
