package moe.lolosia.web.util.koin

import moe.lolosia.web.util.router.RouterInjection
import org.koin.core.Koin

/** 把容器里的实例按类型接进路由参数注入（控制器形参声明该类型即自动注入）。 */
public inline fun <reified T : Any> Koin.registerInjectable(): Unit =
    RouterInjection.register(T::class) { this@registerInjectable.get() }
