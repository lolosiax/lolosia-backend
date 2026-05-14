package moe.lolosia.web.interceptor

import moe.lolosia.web.annotation.JwtIgnore
import moe.lolosia.web.manager.SessionManager
import moe.lolosia.web.util.ErrorResponseException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.http.HttpStatus
import org.springframework.web.method.HandlerMethod
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

abstract class JwtWebFilter(order: Int) : AbstractWebFilter(order) {
    companion object {
        @JvmStatic
        val logger = LoggerFactory.getLogger(JwtWebFilter::class.java)!!
        private val pathPatternParser = PathPatternParser()
        private val apiPattern = pathPatternParser.parse("/api/**")

        // 全路径匹配
        private val ignore = listOf(
            "/api/sse",
            "/api/login",
            "/api/captcha",
            "/api/verify",
            "/api/register",
            "/api/user/updatePassword",
            "/api/user/avatar",
            "/api/client/",
            "/api/resources/checkFiles"
        )

        // 起始匹配
        private val ignoreMatches = listOf(
            "/api/oss/show/",
            "/api/oss/get/",
            "/api/user/avatar/get/",
        )
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var requestMapping: RequestMappingHandlerMapping

    @Autowired
    lateinit var sessionManager: SessionManager;

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain) = mono {
        val path = exchange.request.path.pathWithinApplication();
        var pathStr = path.value();
        if (pathStr.contains('?')) pathStr = pathStr.split('?', limit = 2)[0]
        if (apiPattern.matches(path)) {
            val method = requestMapping.getHandler(exchange).awaitSingleOrNull() as? HandlerMethod
            var isAnnotateIgnore = false
            method?.let {
                val fClass = AnnotationUtils.findAnnotation(method.beanType, JwtIgnore::class.java)?.value
                val fMethod = AnnotationUtils.findAnnotation(method.method, JwtIgnore::class.java)?.value
                isAnnotateIgnore = when {
                    fClass == true && fMethod == true -> true
                    fClass == true && fMethod == null -> true
                    fClass == null && fMethod == true -> true
                    else -> false
                }
            }

            if (isAnnotateIgnore) {
                return@mono chain.filter(exchange).awaitSingleOrNull()
            } else if (ignore.contains(pathStr)) {
                return@mono chain.filter(exchange).awaitSingleOrNull()
            } else if (ignoreMatches.any { pathStr.startsWith(it) }) {
                return@mono chain.filter(exchange).awaitSingleOrNull()
            } else {
                val session = sessionManager.mySession(exchange)
                if (session == null) {
                    throw ErrorResponseException(HttpStatus.UNAUTHORIZED, "身份认证失败，请重新登录")
                }
                return@mono chain.filter(exchange).awaitSingleOrNull()
            }
        } else {
            return@mono chain.filter(exchange).awaitSingleOrNull()
        }
    }.then()
}