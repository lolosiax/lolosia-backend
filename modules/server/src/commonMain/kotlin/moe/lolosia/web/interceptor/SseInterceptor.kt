package moe.lolosia.web.interceptor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import moe.lolosia.web.dto.RawJson
import moe.lolosia.web.service.SseService
import moe.lolosia.web.util.sse.SSE_UPGRADE_HEADER
import moe.lolosia.web.util.sse.SSE_UPGRADE_VALUE
import moe.lolosia.web.util.sse.SseContent
import moe.lolosia.web.util.sse.SseSourceKey

/**
 * 第 4 层：SSE 特殊协议（Ctx 之内、Auth 之外）。
 *
 * 两件事：
 * 1. 请求头 `x-upgrade: event-source` → 分配连接标识，应答裸的 `{data, code}` 并**不进路由**
 *    （前端直接读 `body.data` 当标识，故用 [EnvelopeSkipKey] 让信封层放行）；
 * 2. 响应体替换：请求的 `call.attributes` 里带了 [SseSourceKey]（即 `GET /api/sse` 建流）
 *    时，把路由的普通响应换成 `text/event-stream` 流，直到客户端断开。
 *
 * 建流请求本身是匿名的（浏览器原生 `EventSource` 不能带自定义头），登录态由
 * `POST /api/sse/registry` 事后补绑。
 *
 * @author 洛洛希雅Lolosia
 */
@Interceptor(-4)
fun Application.installSseInterceptor(sse: SseService) {

    sendPipeline.intercept(ApplicationSendPipeline.After) { _ ->
        val source = call.attributes.getOrNull(SseSourceKey)
        if (source == null) proceed() else proceedWith(SseContent(source))
    }

    intercept(ApplicationCallPipeline.Call) {
        if (call.request.headers[SSE_UPGRADE_HEADER] == SSE_UPGRADE_VALUE) {
            call.attributes.put(EnvelopeSkipKey, Unit)
            call.respond(HttpStatusCode.OK, RawJson("""{"data":"${sse.createId()}","code":200}"""))
            finish()
        } else {
            proceed()
        }
    }
}
