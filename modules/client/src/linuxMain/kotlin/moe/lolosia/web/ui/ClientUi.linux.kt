package moe.lolosia.web.ui

import moe.lolosia.web.ui.api.LocalServiceApi

/** linux 目标还没有 SDL3 交叉构建，客户端入口直接返回失败码。 */
actual fun runClientUi(localService: LocalServiceApi?): Int = 2
