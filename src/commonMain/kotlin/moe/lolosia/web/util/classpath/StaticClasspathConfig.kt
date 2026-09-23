package moe.lolosia.web.util.classpath

import io.ktor.server.application.ApplicationCall

/**
 * 静态资源路由 `Route.staticClasspath` 的配置。
 *
 * @author 洛洛希雅Lolosia
 */
public class StaticClasspathConfig {
    /** 目录请求（或以 `/` 结尾）时返回的文件名；设为 null 则不自动补 index。 */
    public var index: String? = "index.html"

    /** `Cache-Control` 值；null 表示不加该响应头。 */
    public var cacheControl: String? = "max-age=3600"

    /** 未命中时的回退（SPA 常用：把未知路径交给前端路由，返回 index.html）。 */
    public var fallback: (suspend ApplicationCall.(path: String) -> Unit)? = null
}
