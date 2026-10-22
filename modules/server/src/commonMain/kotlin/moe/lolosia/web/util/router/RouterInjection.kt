package moe.lolosia.web.util.router

import io.ktor.server.application.*
import kotlin.reflect.KClass

/**
 * 可注入类型的注册表。
 *
 * 由应用在启动时注册，例如 `RouterInjection.register(Context::class) { call -> ... }`。
 * 控制器形参声明为已注册类型（或其子类型）时无需任何注解即自动注入。
 *
 * @author 洛洛希雅Lolosia
 */
object RouterInjection {
    private val factories = mutableMapOf<KClass<*>, (ApplicationCall) -> Any>()

    fun <T : Any> register(type: KClass<T>, factory: (ApplicationCall) -> T) {
        factories[type] = factory
    }

    fun resolve(type: KClass<*>, call: ApplicationCall): Any =
        factories[type]?.invoke(call)
            ?: error("未注册的可注入类型：${type.simpleName}；请先在启动时调用 RouterInjection.register")

    fun isRegistered(type: KClass<*>): Boolean = factories.containsKey(type)
}
