package moe.lolosia.web

import moe.lolosia.web.database.repository.SessionRepository
import moe.lolosia.web.database.repository.SysCacheRepository
import moe.lolosia.web.database.repository.SysRoleRepository
import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.database.repository.SysUserRoleRepository
import moe.lolosia.web.database.repository.ViewUserRoleRepository
import moe.lolosia.web.database.generated.SessionRepositoryImpl
import moe.lolosia.web.database.generated.SysCacheRepositoryImpl
import moe.lolosia.web.database.generated.SysRoleRepositoryImpl
import moe.lolosia.web.database.generated.SysUserRepositoryImpl
import moe.lolosia.web.database.generated.SysUserRoleRepositoryImpl
import moe.lolosia.web.database.generated.ViewUserRoleRepositoryImpl
import moe.lolosia.web.generated.generatedRoutes
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Windows（mingwX64）入口。
 *
 * 端口可用第一个命令行参数覆盖：`ktor-template.exe 8080`。
 * 优先级：命令行参数 > 环境变量 `KTOR_PORT` > 配置文件 `application.json` > 内置默认值。
 *
 * 入口必须位于目标源集：KSP 生成的路由注册代码与仓库实现都在各目标源集内，
 * 而 common 代码看不到 platform 声明。
 */
fun main(args: Array<String>) {
    startServer(args.firstOrNull()?.toIntOrNull(), platformModules()) { generatedRoutes() }
}

/** 目标源集特有的绑定：sqlx4k 生成的仓库实现（只在本目标源集可见）。 */
private fun platformModules(): List<Module> = listOf(
    module {
        single<SysCacheRepository> { SysCacheRepositoryImpl }
        single<SessionRepository> { SessionRepositoryImpl }
        single<SysUserRepository> { SysUserRepositoryImpl }
        single<SysRoleRepository> { SysRoleRepositoryImpl }
        single<SysUserRoleRepository> { SysUserRoleRepositoryImpl }
        single<ViewUserRoleRepository> { ViewUserRoleRepositoryImpl }
    }
)
