package moe.lolosia.web.util.fs

import platform.posix.mkdir

internal actual fun makeDirectory(path: String) {
    mkdir(path)
}
