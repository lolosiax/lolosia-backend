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

    /**
     * 未命中时是否把根 [index] 当命中返回（SPA 的 history 路由回退）。
     *
     * 走的是和命中完全一样的缓存头逻辑：回退响应若不带 `ETag` / `Last-Modified`，
     * 浏览器会把它当一份「一直有效」的入口页缓存住，重新部署后照样白屏。
     */
    var spaFallback: Boolean = false

    /** 未命中时的回退（如「前端资源缺失」的 404 提示）；[spaFallback] 都救不回来时才轮到它。 */
    var fallback: (suspend ApplicationCall.(path: String) -> Unit)? = null

    /** 命中资源后、写出响应前的改写钩子（如按查询参数转码）；返回 null 表示原样返回。 */
    var transform: (suspend ApplicationCall.(bytes: ByteArray) -> Transformed?)? = null

    /** [transform] 的产物：替换后的内容与它自己的 MIME。 */
    class Transformed(val bytes: ByteArray, val contentType: ContentType)
}
