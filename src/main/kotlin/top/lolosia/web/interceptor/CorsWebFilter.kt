package top.lolosia.web.interceptor

import org.springframework.boot.web.reactive.filter.OrderedWebFilter
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod.*
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class CorsWebFilter : OrderedWebFilter {

    override fun getOrder(): Int {
        return -8
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val req = exchange.request
        val resp = exchange.response
        val rh = req.headers

        resp.headers.apply {
            if (req.method == OPTIONS) {
                accessControlAllowOrigin = rh.origin
                accessControlAllowCredentials = true
                accessControlAllowMethods = listOf(GET, POST, PUT, DELETE, PATCH, TRACE, HEAD)
                accessControlMaxAge = 86400
                accessControlAllowHeaders = rh.accessControlRequestHeaders
            } else
            //if ("cors" in (rh["Sec-Fetch-Mode"] ?: emptyList()))
            {
                accessControlAllowOrigin = rh.origin
                accessControlAllowHeaders = rh.accessControlRequestHeaders
                accessControlAllowMethods = listOf(rh.accessControlRequestMethod ?: req.method)
            }
        }

        if (req.method != OPTIONS) return chain.filter(exchange)
        val mono = Mono.just(DefaultDataBufferFactory.sharedInstance.wrap("ok".toByteArray()))
        return exchange.response.writeWith(mono)
    }
}