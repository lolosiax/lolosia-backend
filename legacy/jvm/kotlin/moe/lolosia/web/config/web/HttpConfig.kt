package moe.lolosia.web.config.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders

/**
 * HTTP 基础设施：CORS / 压缩 / 默认响应头 / 反代头。
 * [ConditionalHeaders]：配合响应中的 Last-Modified/ETag，对 If-Modified-Since/If-None-Match
 * 返回 304，让前端浏览器复用缓存、减少请求（零内存开销，/res 资源依赖它）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 12:21
 */
fun Application.configureHttp() {
    install(ConditionalHeaders)
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        // allowHeader(ContentType) 会同时打开 allowNonSimpleContentTypes，
        // 否则 JSON 请求（application/json 非简单 Content-Type）的预检和实际请求都会被 CORS 拦成 403
        allowHeader(HttpHeaders.ContentType)
        // SSE 握手自定义头（见 SseInterceptor），不在白名单则预检 403、无 Access-Control-Allow-Origin
        allowHeader("x-upgrade")
        allowHeader("MyCustomHeader")
        anyHost() // @TODO: 生产环境尽量收敛域名
    }
    install(Compression)
    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // 随每个响应发送
    }
    // WARNING: 仅在反向代理后面时启用
    install(ForwardedHeaders)
    install(XForwardedHeaders)
}
