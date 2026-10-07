package moe.lolosia.web.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp
import io.ktor.client.plugins.websocket.WebSockets

/** 经系统 WinHTTP，TLS 与 WebSocket 均由引擎直接提供。 */
internal actual fun createApiClient(): HttpClient = HttpClient(WinHttp) {
    install(WebSockets)
}
