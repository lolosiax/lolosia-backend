package moe.lolosia.web.config.web

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import moe.lolosia.web.generated.generateRoutes
import moe.lolosia.web.generated.manualRoutes

/**
 * 路由注册。
 * - [generateRoutes]：ktorfitx 根据 @Controller/@GET/@POST 等注解在**编译期**生成
 * - [manualRoutes]：@Route 手动路由（文件下载/上传/静态资源/重定向等无法用注解
 *   表达的端点），由 GenerateRouteModuleTask 扫描各 Controller 内的 @Route 函数生成
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 12:21
 */
fun Application.configureRouting() {
    routing {
        generateRoutes()
        manualRoutes()
    }
}
