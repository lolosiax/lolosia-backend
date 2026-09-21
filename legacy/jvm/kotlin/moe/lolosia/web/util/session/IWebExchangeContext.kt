package moe.lolosia.web.util.session

import io.ktor.server.application.ApplicationCall
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.request.host

/**
 * 请求上下文接口。
 * 业务代码一般只传 [Context]/EventSource，不直接传 [call]（[ApplicationCallContext.call] 供中间件使用）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
interface IWebExchangeContext {
    val call: ApplicationCall

    val isWebSocket get() = call.request.headers["Upgrade"] == "websocket"
    val request get() = call.request
    val response get() = call.response
    val headers get() = call.request.headers
    val clientCookie get() = call.request.cookies
    val serverHost get() = call.request.host()
    val serverHostString get() = call.request.host()
    val clientReferer get() = headers[HttpHeaders.Referrer]
    val clientUserAgent get() = headers[HttpHeaders.UserAgent]
    val clientAddress get() = call.request.origin.remoteAddress
    val clientAddressString get() = call.request.origin.remoteAddress
}
