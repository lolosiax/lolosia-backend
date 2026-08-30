package moe.lolosia.web.controller

import cn.ktorfitx.server.annotation.Body
import cn.ktorfitx.server.annotation.Controller
import cn.ktorfitx.server.annotation.GET
import cn.ktorfitx.server.annotation.POST
import cn.ktorfitx.server.annotation.Path
import cn.ktorfitx.server.annotation.Query
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import moe.lolosia.web.VITE_DEFAULT
import moe.lolosia.web.annotation.Route
import moe.lolosia.web.dto.response.ApiResult
import moe.lolosia.web.util.ErrorResponseException

/**
 * 主页面控制器
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Controller("/")
object HomeController {
    // ===== 前端静态资源 / SPA fallback（手动路由，@Route 扫描注册） =====

    /**
     * 前端首页（vite 产物打包后位于 BOOT-INF/classes/static/home/）：
     * - `/` 与 `/home` → 301 到 `/home/`（目录规范形式）
     * - `/home/` 直接返回 index.html（staticResources 对空路径不处理目录请求）
     * - 其余未匹配的 history 路由（如 /home/lolosia）fallback 到 index.html（SPA）
     */
    @Route
    fun frontend(routing: Routing) {
        routing.get("/") { call.respondRedirect("/$VITE_DEFAULT/", permanent = true) }
        routing.get("/$VITE_DEFAULT") { call.respondRedirect("/$VITE_DEFAULT/", permanent = true) }
        routing.get("/$VITE_DEFAULT/") { call.serveHomeIndex(VITE_DEFAULT) }
        routing.staticResources("/$VITE_DEFAULT", "static/home") {
            fallback { _, call -> call.serveHomeIndex(VITE_DEFAULT) }
        }
    }

    /** 返回前端 index.html（SPA 入口，用于 /home 目录请求与 history 路由 fallback） */
    private suspend fun ApplicationCall.serveHomeIndex(contextPath: String) {
        val index = application.environment.classLoader.getResource("static/${contextPath}/index.html")
            ?: throw ErrorResponseException(HttpStatusCode.NotFound, "前端资源缺失，请先构建 viteJar")
        val bytes = withContext(Dispatchers.IO) {
            index.openStream().buffered().use { it.readAllBytes() }
        }
        respondBytes(bytes, ContentType.Text.Html)
    }
}
