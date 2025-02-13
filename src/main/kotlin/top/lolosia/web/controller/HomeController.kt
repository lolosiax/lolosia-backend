package top.lolosia.web.controller

import top.lolosia.web.service.HomeService
import top.lolosia.web.util.bundle.Bundle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.net.URI

@RestController
class HomeController {

    @Autowired
    lateinit var service: HomeService

    @GetMapping(path = ["/", "/api/", "/home/api/"])
    fun home(resp: ServerHttpResponse): Mono<Void> {
        // resp.statusCode = HttpStatus.FOUND
        // resp.headers.location = URI.create("/oAuth/")
        resp.statusCode = HttpStatus.OK
        resp.headers.contentType = MediaType.parseMediaType("text/plain;charset=UTF-8")
        return resp.writeWith(
            Mono.just(
                DefaultDataBufferFactory.sharedInstance.wrap(
                    "你好，这里是Lolosia的后端".toByteArray(Charsets.UTF_8)
                )
            )
        )
    }

    private val ServerHttpRequest.sub: String
        get() {
            return path.toString().split("/", limit = 3)[2]
        }

    @GetMapping("/oAuth/**")
    fun oAuth(req: ServerHttpRequest, resp: ServerHttpResponse): ByteArray {
        return service.handlePlatform("oAuth", req.sub, resp)
    }

    @PostMapping("/api/oAuth/platforms")
    fun oAuthPlatforms(): Bundle {
        return service.oAuthPlatforms()
    }

    @GetMapping("/decisionGame/**")
    fun decisionGame(req: ServerHttpRequest, resp: ServerHttpResponse): ByteArray {
        return service.handlePlatform("decisionGame", req.sub, resp)
    }

    @GetMapping("/produceGame/**")
    fun produce(req: ServerHttpRequest, resp: ServerHttpResponse): ByteArray {
        return service.handlePlatform("produceGame", req.sub, resp)
    }

    @GetMapping("/sellGame/**")
    fun sell(req: ServerHttpRequest, resp: ServerHttpResponse): ByteArray {
        return service.handlePlatform("sellGame", req.sub, resp)
    }
}