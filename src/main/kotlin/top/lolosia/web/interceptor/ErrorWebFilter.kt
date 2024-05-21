package top.lolosia.web.interceptor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import kotlinx.coroutines.isActive
import top.lolosia.web.util.bundle.bundleScope
import top.lolosia.web.util.kotlin.pass
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.boot.web.reactive.filter.OrderedWebFilter
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class ErrorWebFilter : OrderedWebFilter {
    interface Ignore;
    companion object {
        @JvmStatic
        val logger = LoggerFactory.getLogger(ErrorWebFilter::class.java)!!
        private val mapper = ObjectMapper()
        private val bufferFactory = DefaultDataBufferFactory()
    }

    override fun getOrder(): Int {
        return -6
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        return mono {
            try {
                chain.filter(exchange).awaitSingleOrNull()
            } catch (e: Throwable) {
                val code = when (e) {
                    is HttpStatusCodeException -> e.statusCode
                    is ErrorResponse -> e.statusCode
                    else -> null
                }
                if (code == HttpStatus.NOT_FOUND) pass
                else if (code == HttpStatus.UNAUTHORIZED) pass
                else if (e is Ignore) pass
                else if (e::class.qualifiedName == "kotlinx.coroutines.JobCancellationException") pass
                else logger.error(e.message, e)

                // 如果响应体被提交，或者连接被切断，直接返回
                if (exchange.response.isCommitted || !isActive) return@mono


                val obj = bundleScope {
                    "data" set NullNode.instance
                    "msg" set e.message
                    "code" set (code?.value() ?: 500)

                    // 所有错误列表
                    val types = mutableListOf<String>()
                    var e1: Throwable? = e;
                    while (e1 != null) {
                        types += e1::class.java.name
                        e1 = e1.cause
                    }

                    if (types.size == 1) "type" set types[0]
                    else "type" set types
                }

                val bytes = mapper.writeValueAsBytes(obj)
                exchange.response.apply {
                    headers.set(HttpHeaders.CONTENT_TYPE, "application/json;charset=utf-8")
                    headers.set(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
                    statusCode = code ?: HttpStatus.INTERNAL_SERVER_ERROR
                    writeWith(Mono.just(bufferFactory.wrap(bytes))).awaitSingleOrNull()
                }
            }
        }.then()
    }
}