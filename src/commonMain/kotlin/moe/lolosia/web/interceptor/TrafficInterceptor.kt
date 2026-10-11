package moe.lolosia.web.interceptor

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.*
import moe.lolosia.web.service.dashboard.AccessStatService
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
 * 顺带把这次访问交给 [AccessStatService] 记一笔（仪表板的 IP / Origin 排行）。
 * 它只在内存里按「分钟桶 + IP + Host」累加，**请求链路不写库**（落库在后台循环里），
 * 所以图片直链那种高频请求也不会拖慢。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-9)
fun Application.installTrafficInterceptor(access: AccessStatService) {
    intercept(ApplicationCallPipeline.Monitoring) {
        val start = TimeSource.Monotonic.markNow()
        try {
            proceed()
        } finally {
            log.info { "${call.request.httpMethod.value} ${call.request.uri} ${start.elapsedNow().inWholeMilliseconds}ms" }
            access.record(call.clientIp(), call.request.headers[HttpHeaders.Host])
        }
    }
}

/**
 * 取客户端 IP：优先 `X-Forwarded-For` 的首个（部署在 Caddy 之类的反代后面时，socket 对端是反代本身），
 * 否则回退到连接对端地址。
 */
private fun ApplicationCall.clientIp(): String? {
    val forwarded = request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
    if (!forwarded.isNullOrEmpty()) return forwarded
    return request.local.remoteHost
}
