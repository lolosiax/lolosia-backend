package moe.lolosia.web

import io.ktor.server.netty.EngineMain
import moe.lolosia.web.config.SConfig

/**
 * Ktor项目模板
 *
 * @author 一七年夏
 * @since 2026-08-30 11:42
 */
object KtorApplication {

    @JvmStatic
    fun main(args: Array<String>) {
        // 端口由 SConfig 管理：根目录 application.yaml 的 `server.port`，默认 7129。
        // 以命令行 -port 覆盖 EngineMain 读取的 ktor.deployment.port（resources/application.yaml）。
        val port = SConfig.server.port
        EngineMain.main(args + arrayOf("-port=$port"))
    }
}
