package moe.lolosia.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

/** 服务端口（与旧 JVM 版现网部署一致；可用命令行参数覆盖）。 */
public const val PORT: Int = 7129

/**
 * 统一响应信封。
 *
 * 结构固定为 `{code, msg, data}`（与旧 JVM 版逐字一致，前端 `request.ts` 按此解包）。
 * 注解式控制器不必自己拼信封：KSP 生成的注册代码会调 [respondEnveloped] 自动包一层。
 */
@Serializable
public data class ApiResult<T>(
    val code: Int = 200,
    val msg: String = "success",
    val data: T? = null,
) {
    public companion object {
        public fun <T> ok(data: T? = null, msg: String = "success"): ApiResult<T> =
            ApiResult(200, msg, data)

        public fun <T> err(code: Int, msg: String): ApiResult<T> = ApiResult(code, msg, null)
    }
}

/**
 * 带 HTTP 状态码的业务异常：[status] 同时作为 HTTP 状态码与信封的 `code`
 * （旧 JVM 版的 `ErrorResponseException` 在 native 上的对应物，由 StatusPages 转换）。
 *
 * 鉴权失败用 `401`：前端 `request.ts` 对 `401/403` 会清登录态并跳回登录页。
 */
public class ApiException(public val status: Int, message: String) : RuntimeException(message)

/**
 * 发出「已包信封」的响应：注解式控制器返回什么，这里就包成 `{code,msg,data}`。
 *
 * 为什么不在 send pipeline 上做全局信封（旧 JVM 版的做法）：那条路要靠运行时类型判断
 * （`subject is JsonObject`），而 native 上既没有通用反射、`proceedWith` 还会沿用旧的 `typeInfo`
 * 去序列化，容易出现类型错配。改在**生成代码里显式包**，用 reified 类型参数把
 * `ApiResult<T>` 的 `typeInfo` 完整交给 ContentNegotiation，类型安全且没有魔法。
 *
 * 直接 `call.respond(ApiResult...)` 的调用方（StatusPages、手写路由）不会被再包一层。
 */
public suspend inline fun <reified T> ApplicationCall.respondEnveloped(value: T) {
    respond(ApiResult.ok(value))
}
