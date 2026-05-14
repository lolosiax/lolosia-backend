package moe.lolosia.web.interceptor

import org.springframework.boot.web.reactive.filter.OrderedWebFilter


abstract class AbstractWebFilter(private val order: Int) : OrderedWebFilter {
    override fun getOrder(): Int {
        return order
    }
}