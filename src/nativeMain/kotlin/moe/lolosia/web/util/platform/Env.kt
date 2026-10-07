package moe.lolosia.web.util.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * 读取环境变量（两个 native 目标的运行时都提供 posix 的 `getenv`）。未设置返回 `null`。
 *
 * @author 洛洛希雅Lolosia
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun envOrNull(name: String): String? = getenv(name)?.toKString()
