package moe.lolosia.web.interceptor

import io.ktor.util.AttributeKey

/**
 * 打过此标记的请求，其响应不自动加信封（如 SSE 握手要返回裸的 `{data, code}`）。
 *
 * 信封规则见 [installEnvelopeInterceptor]。
 * @author 洛洛希雅Lolosia
 */
public val EnvelopeSkipKey: AttributeKey<Unit> = AttributeKey("envelope-skip")


/**
 * 标注一个中间件，其注册顺序由 [order] 表达。
 *
 * 被标注的函数必须是 `Application` 的扩展；形参由 Koin 提供（生成 `koin.get()`）。
 * `router-ksp` 扫描全部被标注的函数，按 [order] 升序生成
 * `Application.configureInterceptors(koin)`，因此**不要在别处手写中间件顺序表**。
 *
 * @property order 层次序号，值越小越靠外；同序会在编译期报错
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Interceptor(val order: Int)