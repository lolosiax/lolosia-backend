package moe.lolosia.web

import moe.lolosia.web.generated.generatedRoutes

/**
 * Windows（mingwX64）入口。
 *
 * 入口必须位于目标源集：KSP 生成的路由注册代码在各目标源集内，
 * 而 common 代码看不到 platform 声明。
 */
fun main() = startServer(PORT) { generatedRoutes() }
