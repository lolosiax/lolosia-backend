package moe.lolosia.web

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import moe.lolosia.web.configuration.configureApplication
import moe.lolosia.web.configuration.platformModule
import moe.lolosia.web.configuration.startAppKoin
import moe.lolosia.web.generated.repositoryModules
import moe.lolosia.web.generated.serviceModule
import moe.lolosia.web.settings.ENV_SETTINGS_FILE
import moe.lolosia.web.settings.loadAppSettings
import moe.lolosia.web.util.classpath.installPlatformClasspath
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.log.LogLevel
import moe.lolosia.web.util.platform.awaitSignalTermination
import moe.lolosia.web.util.platform.envOrNull

/**
 * 应用程序主入口点：装载 classpath、加载配置、装配 Koin、启动 CIO 引擎。
 *
 * 端口可用第一个命令行参数覆盖：`./ktor-template.kexe 7130`。
 * 优先级：命令行参数 > 环境变量 `KTOR_PORT` > 配置文件 `application.json` > 内置默认值。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-10-07 13:28
 */
fun main(args: Array<String>) {
    val portOverride = args.firstOrNull()?.toIntOrNull()
    val log = Log.of("Main")

    val cp = installPlatformClasspath()
    val loaded = loadAppSettings(
        classpath = cp,
        // KTOR_CONFIG 指了就用它；否则按 SETTINGS_FILES（yaml / yml / json）在启动目录里找第一个存在的
        diskPath = envOrNull(ENV_SETTINGS_FILE),
        env = ::envOrNull,
    )
    // 命令行参数优先级最高：直接写回配置，保证「生效配置」与真实监听端口一致
    val config = if (portOverride == null) loaded else loaded.copy(server = loaded.server.copy(port = portOverride))
    Log.level = LogLevel.parse(config.logLevel)

    // Koin 容器：common 的配置/包路径 + 平台模块（sqlx4k 仓库实现、`@Service` 服务、平台绑定）
    val koin = startAppKoin(config, cp, repositoryModules() + serviceModule() + platformModule())

    log.info { "classpath 已装载：${cp.entries.size} 个条目（来源：${cp.origin}）" }
    log.info { "配置已加载：port=${config.server.port}, db=${config.datasource.url}, 日志级别=${Log.level}" }
    log.info { "starting on ${config.server.host}:${config.server.port} (Kotlin/Native + CIO)" }

    val server = embeddedServer(CIO, port = config.server.port, host = config.server.host) {
        configureApplication(koin)
    }
    // 监听 socket 带 SO_REUSEADDR（CIO 默认 false）：旧进程被强杀后端口上会残留成片的半开连接
    // （FIN-WAIT-2 等），没有它新进程重绑会直接崩在 EADDRINUSE，被 systemd 反复拉起又反复崩
    server.engineConfig.reuseAddress = true
    server.start(wait = false)

    // 信号处理器交给内核默认动作，挂起等进程被 SIGTERM / SIGINT 结束
    awaitSignalTermination()
}
