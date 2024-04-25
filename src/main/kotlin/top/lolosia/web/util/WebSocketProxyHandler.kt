package top.lolosia.web.util

import top.lolosia.web.util.reactor.start
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

class WebSocketProxyHandler(vararg val proto: String, val transformer: (it: URI) -> URI) :
    WebSocketHandler {

    private val logger = LoggerFactory.getLogger(WebSocketProxyHandler::class.java)
    override fun getSubProtocols(): List<String> {
        return proto.toList()
    }

    private fun Throwable.str(): String {
        return "${this::class.qualifiedName}: $message"
    }

    private fun URI.sid(): String {
        return "sid=(?<sid>.{36})".toRegex().find(this.query)?.groups?.get("sid")?.value ?: "null"
    }

    override fun handle(session: WebSocketSession): Mono<Void> {
        return mono {
            val uri = session.handshakeInfo.uri
            val reqSession = withTimeout(15.seconds) {
                suspendCoroutine { scope ->
                    val rs = ReactorNettyWebSocketClient().execute(transformer(uri), object : WebSocketHandler {
                        override fun getSubProtocols(): List<String> {
                            return proto.toList()
                        }

                        override fun handle(req: WebSocketSession): Mono<Void> {
                            scope.resume(req)
                            return mono {
                                val status = req.closeStatus().awaitSingleOrNull() ?: CloseStatus.NORMAL
                                session.close(status).subscribe()
                            }.then()
                        }
                    })
                    rs.start { scope.resumeWithException(it) }
                }
            }

            reqSession.receive().subscribe { msg ->
                val send = { msg1: WebSocketMessage ->
                    session.send(Mono.just(msg1)).start {
                        msg1.release()
                        logger.warn("An error occurred in WebSocket downlink connection, sid: ${uri.sid()}\n${it.str()}")
                        reqSession.close(CloseStatus(1011, it.message)).start()
                    }
                }
                msg.retain()
                send(msg)
            }

            session.receive().subscribe { msg ->
                val send = { msg1: WebSocketMessage ->
                    reqSession.send(Mono.just(msg1)).start {
                        msg1.release()
                        logger.warn("An error occurred in WebSocket uplink connection, sid: ${uri.sid()}\n${it.str()}")
                        reqSession.close(CloseStatus(1011, it.message)).start()
                    }
                }
                msg.retain()
                send(msg)
            }
            // 等待关闭
            val status = session.closeStatus().awaitSingleOrNull() ?: CloseStatus.NORMAL
            if (reqSession.isOpen) {
                reqSession.close(status).subscribe()
            }
        }.then()
    }
}