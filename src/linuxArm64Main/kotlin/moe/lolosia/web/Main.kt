package moe.lolosia.web

import moe.lolosia.web.generated.configureInterceptors
import moe.lolosia.web.generated.generatedRoutes
import moe.lolosia.web.generated.registerInjectables
import moe.lolosia.web.generated.repositoryModules

/**
 * Linux ARM64（开发板 lckfb）入口。
 *
 * 端口可用第一个命令行参数覆盖：`./ktor-template.kexe 7130`。
 * 优先级：命令行参数 > 环境变量 `KTOR_PORT` > 配置文件 `application.json` > 内置默认值。
 *
 * 入口必须位于目标源集：KSP 生成的中间件注册表、路由注册代码、仓库实现与 DI 绑定表
 * 都在各目标源集内，而 common 代码看不到 platform 声明。
 */
fun main(args: Array<String>) {
    startServer(
        portOverride = args.firstOrNull()?.toIntOrNull(),
        platformModules = repositoryModules(),
        installInterceptors = { koin -> configureInterceptors(koin) },
        installInjectables = { registerInjectables() },
    ) { generatedRoutes() }
}
