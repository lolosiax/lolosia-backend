package moe.lolosia.web.ui.client

/**
 * 客户端与服务进程共享的设置文件（含服务端地址与登录 token）。
 *
 * UI 与事件订阅必须读同一份文件，否则服务启动后仍拿不到登录态。
 */
expect fun sharedSettingsPath(): String

/** 创建服务进程内的事件总线模块；由服务入口在启动时拉起、退出时停止。 */
expect fun createEventBusRuntime(): EventBusRuntime
