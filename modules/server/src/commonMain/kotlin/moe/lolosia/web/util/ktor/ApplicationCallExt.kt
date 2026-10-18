package moe.lolosia.web.util.ktor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.util.router.FormData
import moe.lolosia.web.util.router.RouterInjection
import moe.lolosia.web.util.router.parseMultipartBody
import moe.lolosia.web.util.router.parseMultipartBoundary
import moe.lolosia.web.util.session.ContextKey

/** 取按类型注册的实例（KSP 生成 `call.inject<T>()`）。 */
inline fun <reified T : Any> ApplicationCall.inject(): T =
    RouterInjection.resolve(T::class, this) as T

/**
 * 校验当前请求是否拥有 [nodes] 中的任一权限；未通过抛 403。
 *
 * 由 `RouterProcessor` 在 `@Permission` 标注的路由体首行生成调用，判定在
 * [moe.lolosia.web.util.session.Context.hasPermission]。未登录（上下文里没有会话）时
 * 上游的鉴权拦截器已经 401，这里的 500 只表示装配漏了 `installCtxInterceptor`。
 */
suspend fun ApplicationCall.requirePermission(vararg nodes: String) {
    val ctx = attributes.getOrNull(ContextKey) ?: throw ApiException(500, "请求上下文缺失，无法校验权限")
    if (!ctx.hasPermission(*nodes)) {
        throw ApiException(403, "没有访问该接口的权限：${nodes.joinToString(" / ")}")
    }
}

/**
 * 读取表单（multipart 或 urlencoded）。
 *
 * Ktor 3.4 在 Kotlin/Native 上未实现 `receiveMultipart()`，故 multipart 分支
 * 改为读原始字节后交 [parseMultipartBody] 解析。
 */
suspend fun ApplicationCall.readForm(): FormData {
    val contentType = request.headers[HttpHeaders.ContentType]?.trim().orEmpty()

    if (contentType.startsWith("multipart/", ignoreCase = true)) {
        val boundary = parseMultipartBoundary(contentType) ?: return FormData.Empty
        val body: ByteArray = receive()
        return parseMultipartBody(body, boundary)
    }

    val fields = if (contentType.startsWith(ContentType.Application.FormUrlEncoded.toString(), ignoreCase = true)) {
        runCatching { receiveParameters() }.getOrNull()
            ?.entries()?.associate { it.key to it.value.first() }
            ?: emptyMap()
    } else {
        request.queryParameters.entries().associate { it.key to it.value.first() }
    }
    return FormData(fields, emptyList())
}

/** 读取 urlencoded 表单字段（不处理 multipart）。 */
suspend fun ApplicationCall.readFormFields(): Map<String, List<String>> {
    val params = receiveParameters()
    return params.entries().associate { it.key to it.value }
}
