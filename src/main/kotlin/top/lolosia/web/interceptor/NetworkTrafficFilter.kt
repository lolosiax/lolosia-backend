package top.lolosia.web.interceptor

import kotlinx.coroutines.*
import org.reactivestreams.Publisher
import org.springframework.boot.web.reactive.filter.OrderedWebFilter
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

//@Component
class NetworkTrafficFilter : OrderedWebFilter {

    companion object {
        val inboundBytes: Long get() = mLastInboundBytes
        private var mInboundBytes = AtomicLong(0L)
        private var mLastInboundBytes = 0L
        val inboundCount: Int get() = mLastInboundCount
        private var mInboundCount = AtomicInteger(0)
        private var mLastInboundCount = 0
        val outboundBytes: Long get() = mLastOutboundBytes
        private var mOutboundBytes = AtomicLong(0L)
        private var mLastOutboundBytes = 0L
        val outboundCount: Int get() = mLastOutboundCount
        private var mOutboundCount = AtomicInteger(0)
        private var mLastOutboundCount = 0
        private fun countInBound(count: Int) {
            mInboundBytes.addAndGet(count.toLong())
            mInboundCount.incrementAndGet()
        }

        private fun countOutBound(count: Int) {
            mOutboundBytes.addAndGet(count.toLong())
            mOutboundCount.incrementAndGet()
        }

        init {
            CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    mLastInboundBytes = mInboundBytes.get()
                    mInboundBytes.set(0)
                    mLastInboundCount = mInboundCount.get()
                    mInboundCount.set(0)

                    mLastOutboundBytes = mOutboundBytes.get()
                    mOutboundBytes.set(0)
                    mLastOutboundCount = mOutboundCount.get()
                    mOutboundCount.set(0)
                    delay(1.seconds)
                }
            }
        }
    }

    override fun getOrder(): Int {
        return -9
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val exchange1 = exchange.mutate()
            .request(Request(exchange.request))
            .response(Response(exchange.response))
            .build()
        return chain.filter(exchange1)
    }

    inner class Request(delegate: ServerHttpRequest) : ServerHttpRequestDecorator(delegate) {
        override fun getBody(): Flux<DataBuffer> {
            return super.getBody().map { countInBound(it.capacity());it }
        }
    }

    inner class Response(delegate: ServerHttpResponse) : ServerHttpResponseDecorator(delegate) {
        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            val body2 = when (body) {
                is Mono -> body.map { countOutBound(it.capacity());it }
                is Flux -> body.map { countOutBound(it.capacity());it }
                else -> throw NotImplementedError("不支持的Publisher类型")
            }
            return super.writeWith(body2)
        }
    }
}