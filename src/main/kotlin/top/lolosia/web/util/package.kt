package top.lolosia.web.util

import com.fasterxml.jackson.databind.json.JsonMapper
import top.lolosia.web.config.ParentConfig
import top.lolosia.web.config.SConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponseException

private val mapper = JsonMapper()

fun success(msg: Any? = "success"): ResponseEntity<ByteArray> {
    val obj = mapper.writeValueAsBytes(msg)
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(obj)
}

fun html(html: String): ResponseEntity<String>{
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)
}

/**
 * Constructor with a given message
 */
@Suppress("NOTHING_TO_INLINE")
inline fun ErrorResponseException(status: HttpStatusCode, msg: String): ErrorResponseException {
    return ErrorResponseException(status, msg, null)
}

/**
 * Constructor with a given message
 */
@Suppress("NOTHING_TO_INLINE")
inline fun ErrorResponseException(status: HttpStatusCode, msg: String, e: Throwable?): ErrorResponseException {
    return ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, msg), e)
}

/**
 * Constructor with a given message
 */
@Suppress("NOTHING_TO_INLINE")
inline fun ErrorResponseException(status: HttpStatusCode, msg: String, data: Any, e: Throwable?): ErrorResponseException {
    val detail = ProblemDetail.forStatusAndDetail(status, msg)
    detail.setProperty("responseBody", data)
    return ErrorResponseException(status, detail, e)
}

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
 * 判断当前是否是客户端状态
 */
val isClient get() = SConfig.host.serviceParent.mode == ParentConfig.HostType.CLIENT