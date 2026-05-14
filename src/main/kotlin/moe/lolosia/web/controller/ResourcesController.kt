package moe.lolosia.web.controller

import moe.lolosia.web.service.ResourcesService
import moe.lolosia.web.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
class ResourcesController {

    @Autowired
    lateinit var service: ResourcesService

    @GetMapping("/res/resources/**")
    suspend fun getResources(exchange: ServerWebExchange): ResponseEntity<*> {
        val path = exchange.request.uri.path.removePrefix("/res")
        return service.get(exchange, "work", path)
    }

    data class CheckFilesFn(val dir: String)

    @PostMapping("/api/resources/checkFiles")
    suspend fun checkFiles(ctx: Context, @RequestBody p: CheckFilesFn): ResponseEntity<*> {
        return service.checkFiles(ctx, p.dir)
    }
}