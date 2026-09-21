package moe.lolosia.web.config.web

import io.ktor.server.application.Application
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authentication
import io.ktor.server.auth.basic

/**
 * 认证配置（基础占位）。
 *
 * 后续接 JWT：install(Authentication) { jwt { ... } }，
 * 需要登录的路由直接用 ktorfitx 的 @Authentication 注解标记即可。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 12:21
 */
fun Application.configureSecurity() {
    authentication {
        basic(name = "myauth1") {
            realm = "Ktor Server"
            validate { credentials ->
                if (credentials.name == credentials.password) {
                    UserIdPrincipal(credentials.name)
                } else {
                    null
                }
            }
        }
    }
}
