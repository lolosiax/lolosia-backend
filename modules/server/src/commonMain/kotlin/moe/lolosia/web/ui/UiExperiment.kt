package moe.lolosia.web.ui

/**
 * 启动 UI 实验：开一个 SDL3 窗口，用 Vulkan（SDL3 GPU API）逐帧清屏，并把输入事件喂给实验用的 ViewModel。
 *
 * 从命令行进入：`lolosia-backend ui`（见 `Main.kt`）。它会占住当前线程直到窗口关闭，
 * 因此不进服务器启动路径，也不参与 Koin 装配。
 *
 * 平台实现的分布：mingwX64 上是真窗口（`UiExperiment.mingw.kt`）；
 * linux 目标尚未交叉构建 SDL3，`UiExperiment.linux.kt` 直接返回失败码。
 *
 * @return 进程退出码，0 表示正常退出。
 * @author 洛洛希雅Lolosia
 */
expect fun runUiExperiment(): Int
