package moe.lolosia.web.ui

/**
 * 启动服务器事件总线服务。
 *
 * - Windows：以 SCM 服务方式运行，收到 SCM 停止请求后退出；
 * - 其他平台：暂未实现，直接返回失败码。
 *
 * @param console 为 `true` 时跳过 SCM，直接在控制台运行（只用于本地调试）。
 * @return 进程退出码，0 表示正常退出。
 * @author 洛洛希雅Lolosia
 */
expect fun runEventBusService(console: Boolean): Int
