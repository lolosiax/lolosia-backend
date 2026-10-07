package moe.lolosia.web.configuration

import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.database.Database
import moe.lolosia.web.service.CacheManager
import moe.lolosia.web.service.LoginService
import moe.lolosia.web.service.SessionManager
import moe.lolosia.web.service.SseService
import moe.lolosia.web.service.ddns.DdnsService
import moe.lolosia.web.service.system.RoleService
import moe.lolosia.web.service.system.UserRoleService
import moe.lolosia.web.service.system.UserService
import moe.lolosia.web.util.classpath.Classpath
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 依赖注入（Koin）。
 *
 * 容器实例由启动流程显式持有并向下传，不依赖全局容器。
 * 仓库实现由 sqlx4k 的 KSP 生成且只存在于各目标源集，故经 [startAppKoin] 的
 * `platformModules` 参数由各目标 `main()` 传入 —— 传的是 `router-ksp` 生成的
 * `repositoryModules()`，绑定表不手写。
 *
 * @author 洛洛希雅Lolosia
 */
public fun appModule(config: AppSettings, cp: Classpath): Module = module {
    single<AppSettings> { config }
    single<Classpath> { cp }
    single { Database(config.datasource) }
}

/** 业务服务（依赖仓库，仓库由平台模块提供）。请求域服务的 DB 句柄来自方法首参 `ctx: Context`。 */
public fun serviceModule(): Module = module {
    single { CacheManager(get(), get()) }
    single { SessionManager(get(), get()) }
    single { SseService() }
    single { LoginService(get(), get(), get(), get()) }
    single { UserService(get(), get(), get(), get()) }
    single { RoleService(get(), get()) }
    single { UserRoleService(get(), get(), get()) }
    single { DdnsService(get<AppSettings>().ddns) }
}

/** 启动容器。[platformModules] 由各目标 `main()` 传入（含 sqlx4k 生成的仓库实现）。 */
public fun startAppKoin(
    config: AppSettings,
    cp: Classpath,
    platformModules: List<Module> = emptyList(),
): Koin = startKoin {
    modules(appModule(config, cp), serviceModule(), *platformModules.toTypedArray())
}.koin
