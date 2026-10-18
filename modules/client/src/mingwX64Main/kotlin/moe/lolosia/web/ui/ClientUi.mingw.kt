package moe.lolosia.web.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import moe.lolosia.web.ui.app.ClientApp
import moe.lolosia.web.ui.app.ClientHost
import moe.lolosia.web.ui.client.CoreLocalServiceApi
import moe.lolosia.web.ui.client.CoreRemoteApi
import moe.lolosia.web.ui.client.WindowsImageLoader
import moe.lolosia.web.ui.client.WindowsEventSpoolWatcher
import moe.lolosia.web.ui.client.WindowsNotifier
import moe.lolosia.web.ui.client.WindowsSettingsStore
import moe.lolosia.web.ui.client.WindowsUiBackend
import moe.lolosia.web.util.classpath.installPlatformClasspath
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.log.Logger
import moe.lolosia.web.util.platform.envOrNull

private const val CLIENT_TITLE = "洛洛希雅"
private const val CLIENT_WIDTH = 420
private const val CLIENT_HEIGHT = 820
private const val FONT_FAMILY = "lolosia-ui"

/** 字体候选：优先带中日韩字形的，最后才是纯拉丁字体。 */
private val FONT_CANDIDATES = listOf(
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/msyh.ttf",
    "C:/Windows/Fonts/simhei.ttf",
    "C:/Windows/Fonts/segoeui.ttf",
)

/** 环境变量，用于在不改设置文件的情况下临时切换服务端。 */
private const val ENV_BASE_URL = "LOLOSIA_BASE_URL"

actual fun runClientUi(): Int {
    val log = Log.of("UiClient")
    val classpath = installPlatformClasspath()
    val backend = WindowsUiBackend(classpath)

    if (!backend.create(CLIENT_TITLE, CLIENT_WIDTH, CLIENT_HEIGHT)) {
        log.error { "创建窗口失败：${backend.lastError()}" }
        return 1
    }
    backend.installResources()
    loadFont(backend, log)

    val tasks = Channel<() -> Unit>(Channel.UNLIMITED)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 后台服务把事件写进 ProgramData 的 spool，本进程负责弹系统通知。
    val notifier = WindowsNotifier()
    if (!notifier.init()) log.warn { "初始化系统通知失败，推送只在服务日志中可见" }
    scope.launch {
        WindowsEventSpoolWatcher { message -> notifier.show(message.title, message.text) }.run()
    }
    val app = ClientApp(
        settingsStore = WindowsSettingsStore(),
        remoteApiFactory = { CoreRemoteApi(it) },
        localServiceApi = CoreLocalServiceApi(),
        imageLoader = WindowsImageLoader(backend),
        scope = scope,
        postToUi = {
            tasks.trySend(it)
            backend.wake()
        },
        baseUrlOverride = envOrNull(ENV_BASE_URL),
    )
    val host = ClientHost(app, backend) { message -> log.error { message } }

    try {
        host.start()
        while (true) {
            when (backend.frame()) {
                moe.lolosia.web.ui.platform.FrameResult.Continue -> Unit
                moe.lolosia.web.ui.platform.FrameResult.Close -> return 0
                moe.lolosia.web.ui.platform.FrameResult.Error -> {
                    log.error { "渲染失败：${backend.lastError()}" }
                    return 1
                }
            }
            while (true) {
                val task = tasks.tryReceive().getOrNull() ?: break
                task()
            }
            host.onFrameEnd()
        }
    } finally {
        scope.cancel()
        notifier.dispose()
        host.dispose()
        backend.destroy()
    }
}

/** 加载界面字体；一个都加载不上不算致命，但必须留下明确日志。 */
private fun loadFont(backend: WindowsUiBackend, log: Logger) {
    for (path in FONT_CANDIDATES) {
        if (backend.loadFont(path, FONT_FAMILY)) {
            log.info { "已加载字体：$path" }
            return
        }
        log.warn { "字体不可用：$path" }
    }
    log.error { "没有可用字体，界面文字会缺失。候选：${FONT_CANDIDATES.joinToString()}" }
}
