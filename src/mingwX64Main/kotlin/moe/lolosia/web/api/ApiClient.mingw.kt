package moe.lolosia.web.api

import io.ktor.client.*
import io.ktor.client.engine.winhttp.*
import io.ktor.client.plugins.websocket.*

/** 经系统 WinHTTP，TLS 与 WebSocket 均由引擎直接提供。 */
internal actual fun createApiClient(): HttpClient = HttpClient(WinHttp) {
    install(WebSockets)
}
