package moe.lolosia.web.interceptor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.*
import moe.lolosia.web.util.log.Log
import kotlin.time.TimeSource

private val log = Log.of("Traffic")

/**
 * 第 1 层：流量统计（最外层）。
 *
 * 挂在 `Monitoring` 相位上，比所有业务相位都靠外，故无论请求最终命中路由、404 还是抛异常，
 * 都会留下一条 `方法 路径 耗时`。进入侧逻辑写在 `proceed()` 之前，返回侧写在之后。
 *
 * 与旧 JVM 版一致：只记访问行，不记请求体 / 响应体。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-9)
fun Application.installTrafficInterceptor() {
    intercept(ApplicationCallPipeline.Monitoring) {
        val start = TimeSource.Monotonic.markNow()
        try {
            proceed()
        } finally {
            log.info { "${call.request.httpMethod.value} ${call.request.uri} ${start.elapsedNow().inWholeMilliseconds}ms" }
        }
    }
}
