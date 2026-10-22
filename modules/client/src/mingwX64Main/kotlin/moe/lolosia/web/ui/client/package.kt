package moe.lolosia.web.ui.client

import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * UI 与服务进程共享的设置文件。
 *
 * 放在 ProgramData 而不是工作目录：SCM 拉起的服务进程没有固定的 cwd，
 * 而 ProgramData 是两个会话都能访问的公共位置。
 */
actual fun sharedSettingsPath(): String = "${programDataDir()}\\Lolosia\\client-settings.json"

/** `%ProgramData%`；服务与用户进程都能访问的共享位置。 */
internal fun programDataDir(): String =
    getenv("ProgramData")?.toKString()?.takeIf { it.isNotBlank() } ?: "C:\\ProgramData"
