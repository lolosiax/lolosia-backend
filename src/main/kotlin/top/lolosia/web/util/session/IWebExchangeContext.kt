package top.lolosia.web.util.session

import org.springframework.http.HttpHeaders
import org.springframework.web.server.ServerWebExchange

interface IWebExchangeContext {
    val exchange: ServerWebExchange
    val isWebSocket get() = exchange.request.headers.upgrade == "websocket"
    val request get() = exchange.request
    val response get() = exchange.response
    val headers get() = exchange.request.headers
    val clientCookie get() = exchange.request.cookies
    val serverHost get() = headers.host
    val serverHostString get() = headers[HttpHeaders.HOST]?.first()
    val clientReferer get() = headers[HttpHeaders.REFERER]
    val clientUserAgent get() = headers[HttpHeaders.USER_AGENT]
    val clientAddress get() = exchange.request.remoteAddress!!
    val clientAddressString get() = exchange.request.remoteAddress!!.hostString
}