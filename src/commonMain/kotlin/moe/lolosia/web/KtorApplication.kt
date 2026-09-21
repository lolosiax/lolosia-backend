package moe.lolosia.web

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 响应信封的最简形态（后续会由 dto/response 包完善）。
 */
@Serializable
data class PingResult(val code: Int = 0, val msg: String = "ok", val data: String? = null)

/**
 * 装配应用：内容协商 + 路由。
 *
 * 对应原 JVM 版的 `ApplicationConfigKt.configureApplication`，但启动方式从
 * `EngineMain` + `application.yaml` 改为代码内 `embeddedServer`（Native 无 config-yaml / 无 EngineMain）。
 */
fun Application.configureApplication() {
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }

    routing {
        get("/api/ping") {
            call.respond(PingResult(data = "pong"))
        }
    }
}

/**
 * 程序入口。
 *
 * 注意：Kotlin/Native 无 HTTPS，生产由前置 Nginx 终止 TLS（开发板上已有 nginx）。
 */
fun main() {
    val port = 7129
    println("[ktor-template] starting on 0.0.0.0:$port (Kotlin/Native + CIO)")
    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        configureApplication()
    }.start(wait = true)
}
