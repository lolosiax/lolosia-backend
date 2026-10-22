package moe.lolosia.web.ui.client

import moe.lolosia.web.util.log.Log

/** Linux 服务进程没有通知宿主，事件只落日志；设置与 UI 共用工作目录下的文件。 */
actual fun sharedSettingsPath(): String = "work/client-settings.json"

actual fun createEventBusRuntime(): EventBusRuntime {
    val log = Log.of("EventService")
    return EventBusRuntime(
        settings = FileSettingsStore(sharedSettingsPath()),
        transportFactory = { CurlEventStreamTransport() },
        onMessage = { message -> log.info { "推送：${message.title} - ${message.text}" } },
        onStatus = { status -> log.info { "状态：$status" } },
        onLog = { line -> log.info { line } },
    )
}
