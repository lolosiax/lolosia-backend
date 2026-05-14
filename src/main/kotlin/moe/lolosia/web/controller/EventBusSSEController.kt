package moe.lolosia.web.controller

import moe.lolosia.web.service.EventBusSSEService
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.IWebExchangeContext
import moe.lolosia.web.util.session.SSEContext
import moe.lolosia.web.util.spring.EventSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.*

@RestController
@RequestMapping("/api")
class EventBusSSEController {

    @Autowired
    lateinit var service: EventBusSSEService

    @PostMapping("/sse")
    suspend fun connect(ctx: Context, source: EventSource) {
        val ctx1 = object : SSEContext(ctx), IWebExchangeContext {
            override val exchange: ServerWebExchange = (ctx as IWebExchangeContext).exchange
        }
        service.connect(ctx1, source)
    }

    data class RegisterUserFn(val sseId: UUID)

    @PostMapping("/sse/registry")
    suspend fun registerUser(ctx: Context, @RequestBody params: RegisterUserFn): ResponseEntity<ByteArray> {
        return service.registerUser(ctx, params.sseId)
    }
}