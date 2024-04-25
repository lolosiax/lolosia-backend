package top.lolosia.web.interceptor

import com.fasterxml.jackson.databind.ObjectMapper
import top.lolosia.web.util.kotlin.PromiseContinuation
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.flux
import kotlinx.coroutines.reactor.mono
import org.reactivestreams.Publisher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.web.reactive.filter.OrderedWebFilter
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@Component
class LogWebFilter : OrderedWebFilter, CoroutineScope {

    private val mapper = ObjectMapper()
    private val bufferFactory = DefaultDataBufferFactory.sharedInstance
    private val up = "\u001B[34m>\u001B[0m"
    private val down = "\u001B[32m<\u001B[32m"
    private val err0 = "\u001B[33m<"
    private val err1 = "\u001B[0;33m"
    private val end = "\u001B[0m"

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    override fun getOrder(): Int {
        return -4
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        var url = exchange.request.path.pathWithinApplication().toString()
        if (url.contains('?')) url = url.split("?", limit = 1)[0]
        val logger = lazy { getLogger(url) }
        val start = System.currentTimeMillis()
        val request = Request(exchange.request)
        val response = Response(exchange.response)
        var writeInfo: (suspend () -> Unit)? = null
        fun String.s200() = slice(0..200) + "...(${length - 200} more)"
        fun time(s: Long) = if (s < 10000) String.format("%4dms", s)
        else String.format("%5ds", s / 1000)
        // 请求处理步骤
        val reqJob = launch {
            try {
                val f = suspend {
                    var bytes: ByteArray? = null
                    try {
                        bytes = request.data.await()
                        if (bytes.isEmpty()) bytes = null
                    } catch (_: Throwable) {
                    }
                    bytes = bytes ?: mapper.writeValueAsBytes(request.queryParams)
                    var str = bytes!!.toString(Charsets.UTF_8)
                    if (str.length > 200) str = str.s200()
                    logger.value.info("$up       $str")
                }
                if (url.startsWith("/api/")) f()
                else writeInfo = f
            } catch (_: Throwable) {
            }
        }
        return mono {
            var data = ""
            var code = 200
            try {
                // 将变换后的请求响应体传递到下一个中间件。
                chain.filter(exchange.mutate().apply {
                    request(request)
                    response(response)
                }.build()).awaitSingleOrNull()
                // 拿到响应体
                data = response.data
            } catch (e: Throwable) {
                code = when (e) {
                    is HttpStatusCodeException -> e.statusCode
                    is ErrorResponse -> e.statusCode
                    else -> HttpStatus.INTERNAL_SERVER_ERROR
                }.value()
                data = e.message ?: "未知异常"
                if (e is HttpStatusCodeException) code = e.statusCode.value()
                throw e
            } finally {
                val f = suspend {
                    // 响应处理步骤
                    val length = data.length
                    if (length > 200) data = data.s200()
                    val end1 = System.currentTimeMillis()
                    // 消费掉请求体，避免由于空请求体导致下方的方法死锁。
                    request.consume()
                    // 等待请求处理完成
                    reqJob.join()
                    if (code in 200 until 400) {
                        logger.value.info("$down${time(end1 - start)}$end $data")
                    } else {
                        writeInfo?.let { it() }
                        logger.value.warn("$err0 \u001B[1;30;43m $code $err1 $data  $end")
                    }
                }
                if (url.startsWith("/api/")) f()
                else if (code !in 200 until 400) f()
            }
        }.then()
    }

    fun getLogger(url: String): Logger {
        val end = if (url.endsWith("/")) "/" else ""
        val name = run {
            if (url.length <= 40) return@run url
            val urlArr = url.replace("^/".toRegex(), "").split("/")
            var out = ""
            val regex = "(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_".toRegex()
            val cache = mutableListOf<String>()
            for (arg in urlArr) {
                val strList = regex.split(arg).joinToString("") {
                    if (it.isNotEmpty()) it[0].lowercase() else ""
                }
                cache += strList
                val strList2 = cache + urlArr.slice(cache.size until urlArr.size)
                out = strList2.joinToString("/", prefix = "/", postfix = end)
                if (out.length <= 40) return@run out
            }
            return@run out
        }
        return LoggerFactory.getLogger(name)
    }

    /**
     * 处理请求体，并拿到请求体的JSON内容。
     */
    inner class Request(delegate: ServerHttpRequest) : ServerHttpRequestDecorator(delegate) {
        private val _body: Flux<DataBuffer>

        /** 请求体的JSON内容 */
        val data: Deferred<ByteArray>

        init {
            // 拿到原始请求体，对其进行变换
            val body = super.getBody()
            val type = this.headers[HttpHeaders.CONTENT_TYPE]?.get(0) ?: ""
            when {
                type.startsWith("application/json") || type.startsWith("text/json") -> {

                    val promise = PromiseContinuation<ByteArray>(Dispatchers.IO)

                    data = async { promise.await() }

                    _body = flux {
                        val byteArray = suspendCoroutine {
                            val stream = ByteArrayOutputStream()

                            body.subscribe({
                                stream.writeBytes(it.asInputStream().readBytes())
                                DataBufferUtils.release(it)
                            }, { e ->
                                stream.close()
                                it.resumeWithException(e)
                            }, {
                                val data = stream.toByteArray()
                                stream.close()
                                it.resume(data)
                            })
                        }

                        if (!promise.isResumed) promise.resume(byteArray)

                        send(bufferFactory.wrap(byteArray))
                    }
                }
                // 请求体类型不是json，返回原始内容。
                else -> {
                    data = async { byteArrayOf() }
                    _body = body
                }
            }
        }

        override fun getBody(): Flux<DataBuffer> {
            return _body
        }

        /** 消费掉这个Body */
        suspend fun consume() {
            if (!data.isCompleted) {
                body.then().awaitSingleOrNull()
            }
        }
    }

    inner class Response(delegate: ServerHttpResponse) : ServerHttpResponseDecorator(delegate) {

        var data: String = "{}"
        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            var type = this.headers[HttpHeaders.CONTENT_TYPE]?.get(0) ?: ""
            return when {
                type.startsWith("application/json") ||
                        type.startsWith("text/json") ||
                        type.startsWith("text/plain") -> {
                    val out = when (body) {
                        is Flux -> body.map(map)
                        is Mono -> body.map(map)
                        else -> body
                    }
                    super.writeWith(out)
                }

                else -> {
                    if (type.contains(';')) type = type.split(";", limit = 1)[0]
                    data = "{${type}}"
                    super.writeWith(body)
                }
            }
        }

        private val map: (DataBuffer) -> DataBuffer = map@{ buffer ->
            val out = buffer.asInputStream().readBytes()
            DataBufferUtils.release(buffer)
            data = try {
                out.toString(Charsets.UTF_8)
            } catch (_: Throwable) {
                "{decode error}"
            }
            return@map bufferFactory.wrap(out)
        }
    }
}