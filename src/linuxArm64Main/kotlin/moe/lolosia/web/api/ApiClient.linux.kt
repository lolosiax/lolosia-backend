package moe.lolosia.web.api

import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.websocket.*

/** 经 libcurl，TLS 与 WebSocket 均由引擎直接提供。 */
internal actual fun createApiClient(): HttpClient = HttpClient(Curl) {
    install(WebSockets)
}
