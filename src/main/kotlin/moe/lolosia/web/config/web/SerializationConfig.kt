package moe.lolosia.web.config.web

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import moe.lolosia.web.dto.response.AnySerializer

/**
 * 默认 JSON 序列化：kotlinx.serialization。
 *
 * - 与 ktorfitx 编译期对 DTO 的 @Serializable 校验保持一致，运行时使用真实生成的序列化器。
 * - 注册 [AnySerializer] 的 contextual 序列化器，使 Bundle（Map<String, Any?>）等
 *   动态结构也能被序列化（账户系统大量使用 Bundle 返回）。
 * - encodeDefaults/explicitNulls：保证 ApiResult 的 code/msg/data 完整输出。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 12:21
 */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
            serializersModule = SerializersModule {
                contextual(Any::class, AnySerializer)
            }
        })
    }
}
