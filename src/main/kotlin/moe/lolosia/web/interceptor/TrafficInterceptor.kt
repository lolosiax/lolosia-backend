package moe.lolosia.web.interceptor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import moe.lolosia.web.logging.log
import kotlin.time.TimeSource

/**
 * 第1层中间件：流量统计（最外层）。
 *
 * Ktor 没有 Spring 的 @Order，**注册顺序即洋葱由外到内顺序**（见 [configureInterceptors]）。
 * 进入侧逻辑写在 proceed() 之前，返回侧逻辑写在 proceed() 之后。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
fun Application.installTrafficInterceptor() {
    intercept(ApplicationCallPipeline.Monitoring) {
        val start = TimeSource.Monotonic.markNow()
        try {
            proceed() // 进入内层
        } finally {
            val cost = start.elapsedNow().inWholeMilliseconds
            log.info("{} {} {}ms", call.request.httpMethod, call.request.uri, cost)
        }
    }
}
