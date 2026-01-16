package top.lolosia.web.interceptor

import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod.*
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

abstract class CorsWebFilter(order: Int) : AbstractWebFilter(order) {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val req = exchange.request
        val resp = exchange.response

        if (req.method != OPTIONS) {
            resp.beforeCommit {
                Mono.fromCallable {
                    val rh = exchange.request.headers
                    resp.headers.apply {
                        remove("Access-Control-Allow-Origin")
                        remove("Access-Control-Allow-Headers")
                        remove("Access-Control-Allow-Methods")

                        accessControlAllowOrigin = rh.origin ?: "*"
                        accessControlAllowHeaders = rh.accessControlRequestHeaders
                        accessControlAllowMethods = listOf(rh.accessControlRequestMethod ?: exchange.request.method)
                    }
                    Unit
                }.then()
            }
            return chain.filter(exchange)
        }

        val rh = exchange.request.headers
        exchange.response.headers.apply {
            accessControlAllowOrigin = rh.origin ?: "*"
            accessControlAllowCredentials = true
            accessControlAllowMethods = listOf(GET, POST, PUT, DELETE, PATCH, TRACE, HEAD)
            accessControlMaxAge = 86400
            accessControlAllowHeaders = rh.accessControlRequestHeaders
        }
        val mono = Mono.just(DefaultDataBufferFactory.sharedInstance.wrap("ok".toByteArray()))
        return exchange.response.writeWith(mono)
    }
}