package moe.lolosia.web.util.platform

/**
 * 读取环境变量；未设置返回 `null`。
 *
 * 平台实现见各目标源集（native 走 posix `getenv`）。
 *
 * @author 洛洛希雅Lolosia
 */
expect fun envOrNull(name: String): String?
