package top.lolosia.web.interceptor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.boot.web.reactive.filter.OrderedWebFilter
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class ReturnWebFilter : OrderedWebFilter {
    companion object {
        private val logger = LoggerFactory.getLogger(ReturnWebFilter::class.java)
        private val mapper = ObjectMapper()
        private val bufferFactory = DefaultDataBufferFactory.sharedInstance
    }

    override fun getOrder(): Int {
        return -2
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val url = exchange.request.path.pathWithinApplication().toString();
        val next = {
            // 创建解码器
            val decorator = Decorator(exchange.response)
            chain.filter(exchange.mutate().response(decorator).build())
        }
        return when {
            url.startsWith("/api/") -> next()
            else -> chain.filter(exchange)
        }
    }

    inner class Decorator(delegate: ServerHttpResponse) : ServerHttpResponseDecorator(delegate) {
        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            val out = when (body) {
                is Flux -> body.map(map)
                is Mono -> body.map(map)
                else -> body
            }
            return super.writeWith(out)
        }

        private val map: (DataBuffer) -> DataBuffer = map@{ buffer ->
            val type = this.headers[HttpHeaders.CONTENT_TYPE]?.get(0) ?: ""
            val str by lazy {
                val str = buffer.asInputStream().readBytes().toString(Charsets.UTF_8)
                // 离谱，这个输入流关了后Buffer居然不会自动释放
                DataBufferUtils.release(buffer)
                return@lazy str
            }
            var obj: JsonNode = when {
                type.startsWith("application/json") || type.startsWith("text/json") -> {
                    mapper.readTree(str)
                }

                type.startsWith("text/plain") -> {
                    TextNode.valueOf(str)
                }

                else -> {
                    return@map buffer;
                }
            }

            obj = onResult(obj)
            val bytes = mapper.writeValueAsBytes(obj)
            headers.set(HttpHeaders.CONTENT_TYPE, "application/json;charset=utf-8")
            headers.set(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
            return@map bufferFactory.wrap(bytes)
        }
    }

    fun onResult(result: JsonNode): JsonNode {
        return mapper.createObjectNode().apply {
            replace("data", result)
            put("code", 200)
            put("msg", "success")
        }
    }
}