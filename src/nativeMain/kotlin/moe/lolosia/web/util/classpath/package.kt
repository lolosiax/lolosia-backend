package moe.lolosia.web.util.classpath

import kotlinx.cinterop.toKString
import platform.posix.getenv

/** 开发期回退目录的环境变量名。 */
public const val ENV_CLASSPATH_DIR: String = "KTOR_CLASSPATH_DIR"

/**
 * 按平台装载 classpath 并安装到 [ClasspathHolder]，由 `startServer` 启动时调用一次。
 *
 * 设置了 [ENV_CLASSPATH_DIR] 时读磁盘目录，否则读可执行文件内嵌的 `classpath.zip`。
 */
public fun installPlatformClasspath(): Classpath {
    val diskRoot = getenv(ENV_CLASSPATH_DIR)?.toKString()
    val installed: Classpath = if (!diskRoot.isNullOrBlank()) {
        DiskClasspath(diskRoot)
    } else {
        embeddedClasspathImage()?.let { ZipClasspath.open(it.pointer, it.size) } ?: EmptyClasspath
    }
    ClasspathHolder.install(installed)
    return installed
}
