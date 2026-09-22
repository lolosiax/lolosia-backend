package moe.lolosia.web.interceptor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import moe.lolosia.web.util.log.Log
import kotlin.time.TimeSource

/**
 * 访问日志（链路最外层）：请求结束后打一行「方法 路径 耗时ms」。
 *
 * 与旧 JVM 版一致：只记访问行，不记请求体 / 响应体。
 *
 * @author 洛洛希雅Lolosia
 */
public fun Application.installTrafficInterceptor() {
    val log = Log.of("Traffic")
    intercept(ApplicationCallPipeline.Monitoring) {
        val start = TimeSource.Monotonic.markNow()
        try {
            proceed()
        } finally {
            log.info {
                "${call.request.httpMethod.value} ${call.request.uri} " +
                    "${start.elapsedNow().inWholeMilliseconds}ms"
            }
        }
    }
}
