package moe.lolosia.web.util.spring

import org.springframework.core.ReactiveAdapterRegistry
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.thymeleaf.context.AbstractContext
import org.thymeleaf.context.Context
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.web.webflux.SpringWebFluxWebApplication
import java.nio.charset.StandardCharsets
import java.util.*

typealias ThymeleafContext = AbstractContext

val THYMELEAF_APP: SpringWebFluxWebApplication by lazy {
    SpringWebFluxWebApplication.buildApplication(ReactiveAdapterRegistry())
}

fun ThymeleafContext(exchange: ServerWebExchange? = null): ThymeleafContext {
    if (exchange == null) return Context()
    return WebContext(THYMELEAF_APP.buildExchange(exchange, Locale.CHINA, MediaType.TEXT_HTML, StandardCharsets.UTF_8))
}