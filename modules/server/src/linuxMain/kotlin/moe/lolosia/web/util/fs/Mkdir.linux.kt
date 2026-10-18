package moe.lolosia.web.util.fs

import platform.posix.mkdir

/** 目录权限 `0755`（`mode_t` 是 `UInt`）。 */
private const val DIR_MODE: UInt = 0b111_101_101u

internal actual fun makeDirectory(path: String) {
    mkdir(path, DIR_MODE)
}
