package moe.lolosia.web.ui

/** linux 目标还没有 SDL3 交叉构建，客户端入口直接返回失败码。 */
actual fun runClientUi(): Int = 2
