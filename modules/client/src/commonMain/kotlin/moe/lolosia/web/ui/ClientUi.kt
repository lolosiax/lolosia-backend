package moe.lolosia.web.ui

import moe.lolosia.web.ui.api.LocalServiceApi

/**
 * 启动最小客户端：登录 → 主壳（番组 / 我的）。
 *
 * 与 `runUiExperiment()` 共用 RmlUi 图形栈，但页面与 ViewModel 来自 `:modules:ui`。
 * 该函数占住当前线程直到窗口关闭。
 *
 * @param localService 本地服务实现；为空时按默认发现文件走 loopback。
 *   `--embed` 传入进程内直连的实现，不建端口、不写发现文件。
 * @return 进程退出码，0 表示正常退出。
 * @author 洛洛希雅Lolosia
 */
expect fun runClientUi(localService: LocalServiceApi? = null): Int
