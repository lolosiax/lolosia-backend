package moe.lolosia.web.configuration

import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.database.Database
import moe.lolosia.web.util.classpath.Classpath
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 依赖注入（Koin）。
 *
 * 容器实例由启动流程显式持有并向下传，不依赖全局容器。
 * 仓库实现由 sqlx4k 的 KSP 生成、`@Service` 的服务绑定由 `router-ksp` 生成；二者以 `expect/actual`
 * 暴露给 common 后，由 [moe.lolosia.web.main] 经 [startAppKoin] 的 `platformModules` 传入，绑定表不手写。
 *
 * 这里只留「无法由类型推导」的绑定：配置对象 [AppSettings] 与 [Classpath] 是外部实例，
 * `Database` 的构造参数取自配置，都不进 `@Service` 的自动生成范围。
 *
 * @author 洛洛希雅Lolosia
 */
public fun appModule(config: AppSettings, cp: Classpath): Module = module {
    single<AppSettings> { config }
    single<Classpath> { cp }
    single { Database(config.datasource) }
}

/**
 * 手写绑定的逃生口：不能用 `@Service` 注解的服务（需要外部实例、条件绑定等）在此手动注册。
 * KSP 按 `@Service` 生成的绑定是 [moe.lolosia.web.generated.serviceModule]，与本函数同名但不同包。
 */
public fun serviceModule() = module {
}

/** 启动容器。[platformModules] 由 [moe.lolosia.web.main] 传入（sqlx4k 生成的仓库绑定、`@Service` 生成的服务绑定与平台绑定）。 */
public fun startAppKoin(
    config: AppSettings,
    cp: Classpath,
    platformModules: List<Module> = emptyList(),
): Koin = startKoin {
    modules(appModule(config, cp), serviceModule(), *platformModules.toTypedArray())
}.koin

/** 平台特有的 Koin 绑定（`actual` 见各目标源集，如 native 绑 `PosixFileStore`）。 */
public expect fun platformModule(): Module
