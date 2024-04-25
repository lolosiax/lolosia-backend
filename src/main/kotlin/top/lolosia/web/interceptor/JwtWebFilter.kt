package top.lolosia.web.interceptor

import top.lolosia.web.manager.SessionManager
import top.lolosia.web.util.ErrorResponseException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.reactive.filter.OrderedWebFilter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

@Component
class JwtWebFilter : OrderedWebFilter {
    companion object {
        @JvmStatic
        val logger = LoggerFactory.getLogger(JwtWebFilter::class.java)!!
        private val pathPatternParser = PathPatternParser()
        private val apiPattern = pathPatternParser.parse("/api/**")
        private val ignore = listOf(
            "/api/login",
            "/api/user/updatePassword",
            "/api/user/avatar",
            "/api/oAuth/platforms",
            "/api/decisionGame/data/exportXlsx",
            "/api/decisionGame/data/exportDataFrame"
        )
    }

    override fun getOrder(): Int {
        return -3
    }

    @Autowired
    lateinit var sessionManager: SessionManager;

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication();
        var pathStr = path.value();
        if (pathStr.contains('?')) pathStr = pathStr.split('?', limit = 2)[0]
        if (apiPattern.matches(path)) {
            if (ignore.contains(pathStr)) {
                return chain.filter(exchange)
            } else {
                val session = sessionManager.mySession(exchange)
                if (session == null) {
                    val e = ErrorResponseException(HttpStatus.UNAUTHORIZED, "身份认证失败，请重新登录")
                    return Mono.error(e)
                }
                return chain.filter(exchange)
            }
        } else {
            return chain.filter(exchange)
        }
    }
}