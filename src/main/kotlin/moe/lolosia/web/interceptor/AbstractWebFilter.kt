package moe.lolosia.web.interceptor

import org.springframework.boot.webflux.filter.OrderedWebFilter

abstract class AbstractWebFilter(private val order: Int) : OrderedWebFilter {
    override fun getOrder(): Int {
        return order
    }
}