package moe.lolosia.web.controller

import moe.lolosia.web.service.HomeService
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.IWebExchangeContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.net.URI

@RestController
class HomeController {

    @Autowired
    lateinit var service: HomeService

    @GetMapping("/")
    fun home(resp: ServerHttpResponse): Mono<Void> {
        resp.statusCode = HttpStatus.FOUND
        resp.headers.location = URI.create("/home/")
        return resp.writeWith(Mono.empty())
    }

    private val Context.sub: String
        get() {
            this@sub as IWebExchangeContext
            return request.path.toString().split("/", limit = 3)[2]
        }

    @GetMapping("/home/**")
    suspend fun home(ctx: Context): ResponseEntity<ByteArray> {
        return service.handlePlatform(ctx, "home", ctx.sub)
    }
}