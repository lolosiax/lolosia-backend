package moe.lolosia.web.config.web

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.webjars.Webjars

/**
 * 静态资源（WebJars），默认挂载路径 /webjars。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 12:21
 */
fun Application.configureWebjars() {
    install(Webjars) {
        path = "/webjars"
    }
}
