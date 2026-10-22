package moe.lolosia.web.ui.client

import moe.lolosia.web.util.log.Log

/**
 * Windows 事件总线模块：读共享设置、订阅远程 SSE，并把推送追加到 spool 文件。
 *
 * UI 进程通过 [WindowsEventSpool] 读取同一份文件并弹出系统通知。
 */
actual fun createEventBusRuntime(): EventBusRuntime {
    val log = Log.of("EventService")
    return EventBusRuntime(
        settings = FileSettingsStore(sharedSettingsPath()),
        transportFactory = { CurlEventStreamTransport() },
        onMessage = { message ->
            WindowsEventSpool.append(message)
            log.info { "推送：${message.title} - ${message.text}" }
        },
        onStatus = { status -> log.info { "状态：$status" } },
        onLog = { line -> log.info { line } },
    )
}
