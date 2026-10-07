package moe.lolosia.web.util.classpath

import io.ktor.http.ContentType
import io.ktor.server.application.*

/**
 * 静态资源路由 `Route.staticClasspath` 的配置。
 *
 * @author 洛洛希雅Lolosia
 */
class StaticClasspathConfig {
    /** 目录请求（或以 `/` 结尾）时返回的文件名；设为 null 则不自动补 index。 */
    var index: String? = "index.html"

    /** `Cache-Control` 值；null 表示不加该响应头。 */
    var cacheControl: String? = "max-age=3600"

    /** 未命中时的回退（SPA 常用：把未知路径交给前端路由，返回 index.html）。 */
    var fallback: (suspend ApplicationCall.(path: String) -> Unit)? = null

    /** 命中资源后、写出响应前的改写钩子（如按查询参数转码）；返回 null 表示原样返回。 */
    var transform: (suspend ApplicationCall.(bytes: ByteArray) -> Transformed?)? = null

    /** [transform] 的产物：替换后的内容与它自己的 MIME。 */
    class Transformed(val bytes: ByteArray, val contentType: ContentType)
}
