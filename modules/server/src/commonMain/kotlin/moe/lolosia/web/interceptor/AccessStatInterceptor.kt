package moe.lolosia.web.interceptor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import moe.lolosia.web.service.dashboard.AccessStatService

/**
 * 第 2 层（`order = -8`）：访问统计埋点。
 *
 * 挂在 `Monitoring` 相位、紧贴最外层的流量日志：**在 `proceed()` 之前**就记一次
 * 「来源 IP + Host」，这样命中路由、404、抛异常、SSE / 长连接等所有请求都会计入，无例外
 * （若放在 `finally`，SSE 这类不返回的请求要等连接断开才会计数）。
 *
 * **来源 IP 取值**：线上经 Caddy 反代，`remoteHost` 恒为回环地址，故优先取代理头
 * `X-Forwarded-For`（可能是一串，取第一段）→ `X-Real-IP` → 兜底 `remoteHost`。
 * Ip / Host 只在这里取（服务层不允许用 `call`）。
 *
 * 与 `TrafficInterceptor`（-9）分开：那条是日志、这条是业务统计，两者的开关与生命周期不同。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-8)
fun Application.installAccessStatInterceptor(stats: AccessStatService) {
    intercept(ApplicationCallPipeline.Monitoring) {
        stats.record(resolveClientIp(call), call.request.headers[HttpHeaders.Host] ?: "")
        proceed()
    }
}

/** 解析真实来源 IP：代理头优先，兜底 socket 地址。 */
private fun resolveClientIp(call: ApplicationCall): String {
    val headers = call.request.headers
    val forwarded = headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
    if (!forwarded.isNullOrEmpty()) return forwarded
    val real = headers["X-Real-IP"]?.trim()
    if (!real.isNullOrEmpty()) return real
    return call.request.local.remoteHost
}
