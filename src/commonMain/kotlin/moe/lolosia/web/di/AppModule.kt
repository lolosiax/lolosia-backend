package moe.lolosia.web.di

import moe.lolosia.web.config.AppConfig
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.router.RouterInjection
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 依赖注入（Koin 4.2.2，已核实发布 `linuxarm64` / `mingwx64` 产物）。
 *
 * 容器实例由启动流程显式持有并向下传，而不是各处去摸全局容器 —— Koin 4 里读全局容器的
 * `GlobalContext` 已不是公开 API（`org.koin.mp.KoinPlatform` 属内部工具），显式传递更稳。
 *
 * 0.6 起的服务按 `single { XxxService(get(), get()) }` 挂到 [appModule] 里。
 *
 * 控制器是 `object`（静态方法），拿不到构造注入，因此服务通过 [registerInjectable] 接进
 * 自研路由的「按类型注入」表 —— 控制器方法形参声明该类型即自动注入，无需注解。
 *
 * @author 洛洛希雅Lolosia
 */
public fun appModule(config: AppConfig, cp: Classpath): Module = module {
    single<AppConfig> { config }
    single<Classpath> { cp }
}

/** 启动容器。 */
public fun startAppKoin(config: AppConfig, cp: Classpath): Koin =
    startKoin { modules(appModule(config, cp)) }.koin

/** 把容器里的实例按类型接进路由参数注入（形参写 `config: AppConfig` 即自动注入）。 */
public inline fun <reified T : Any> Koin.registerInjectable(): Unit =
    RouterInjection.register(T::class) { this@registerInjectable.get() }
