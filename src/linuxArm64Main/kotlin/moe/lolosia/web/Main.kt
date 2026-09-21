package moe.lolosia.web

import moe.lolosia.web.generated.generatedRoutes

/**
 * Linux ARM64（开发板 lckfb）入口。
 *
 * 端口可用第一个命令行参数覆盖：`./ktor-template.kexe 7129`。
 *
 * 入口必须位于目标源集：KSP 生成的路由注册代码在各目标源集内，
 * 而 common 代码看不到 platform 声明。
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: PORT
    startServer(port) { generatedRoutes() }
}
