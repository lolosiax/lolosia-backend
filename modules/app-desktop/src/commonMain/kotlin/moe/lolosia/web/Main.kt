package moe.lolosia.web

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import moe.lolosia.local.InProcessTransport
import moe.lolosia.local.LocalDispatcher
import moe.lolosia.local.LocalServer
import moe.lolosia.web.configuration.configureApplication
import moe.lolosia.web.configuration.platformModule
import moe.lolosia.web.configuration.startAppKoin
import moe.lolosia.web.generated.repositoryModules
import moe.lolosia.web.generated.serviceModule
import moe.lolosia.web.localservice.startLocalService
import moe.lolosia.web.settings.ENV_SETTINGS_FILE
import moe.lolosia.web.settings.loadAppSettings
import moe.lolosia.web.ui.runClientUi
import moe.lolosia.web.ui.runUiExperiment
import moe.lolosia.web.ui.client.createEventBusRuntime
import moe.lolosia.web.ui.client.local.LocalClient
import moe.lolosia.web.util.classpath.installPlatformClasspath
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.log.LogLevel
import moe.lolosia.web.util.platform.awaitSignalTermination
import moe.lolosia.web.util.platform.envOrNull

/**
 * 应用程序主入口点：装载 classpath、加载配置、装配 Koin、启动 CIO 引擎。
 *
 * 首个参数为 `ui` 时只开界面；`--service` 是不带界面的服务进程（本地 API + 事件订阅）；
 * `--embed` 在同一进程里把服务与 UI 一起拉起，便于调试；否则按下面的规则解析端口。
 *
 * 端口可用第一个命令行参数覆盖：`./ktor-template.kexe 7130`。
 * 优先级：命令行参数 > 环境变量 `KTOR_PORT` > 配置文件 `application.json` > 内置默认值。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-10-07 13:28
 */
fun main(args: Array<String>) {
    val first = args.firstOrNull()
    // 服务进程：无界面，包含「本地 loopback API」与「远程事件订阅」两个模块。
    // 事件订阅随服务启动，不是独立服务，也不需要单独配置。
    if (first == "--service" || first == "service") {
        val code = runServiceProcess { awaitStop ->
            val log = Log.of("Main")
            val localService = startLocalService()
            val eventBus = createEventBusRuntime()
            eventBus.start()
            log.info { "服务已启动：本地 API 127.0.0.1:${localService.endpoint.port}，事件订阅运行中" }
            try {
                awaitStop()
            } finally {
                eventBus.stop()
                localService.stop()
            }
        }
        if (code != 0) Log.of("Main").error { "服务退出码 $code" }
        return
    }
    // 单进程调试：本地服务、事件订阅与 UI 跑在同一个进程里，无需先安装 / 启动系统服务。
    // 本地服务走进程内传输：不建端口、不写发现文件，但仍经过同一套 dispatcher。
    if (first == "--embed" || first == "embed") {
        val log = Log.of("Main")
        val (serverEnd, clientEnd) = InProcessTransport.create()
        val server = LocalServer(LocalDispatcher(), serverEnd)
        server.start()
        val localClient = LocalClient(clientEnd)
        val eventBus = createEventBusRuntime()
        eventBus.start()
        log.info { "单进程模式：本地服务（进程内传输）与事件订阅随 UI 启动" }
        val code = try {
            runClientUi(localClient)
        } finally {
            eventBus.stop()
            localClient.close()
            server.stop()
        }
        if (code != 0) log.error { "客户端退出码 $code" }
        return
    }
    // UI 会占住当前线程直到窗口关闭，也不参与 Koin 装配，因此先于一切启动逻辑分流。
    // `ui` 是最小客户端；`probe` 保留原来的 RmlUi 控件实验页。
    if (first == "ui") {
        val code = runClientUi()
        if (code != 0) Log.of("Main").error { "客户端退出码 $code" }
        return
    }
    if (first == "probe") {
        val code = runUiExperiment()
        if (code != 0) Log.of("Main").error { "UI 实验退出码 $code" }
        return
    }

    val portOverride = first?.toIntOrNull()
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
