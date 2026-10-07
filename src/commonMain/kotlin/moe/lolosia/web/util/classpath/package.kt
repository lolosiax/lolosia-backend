package moe.lolosia.web.util.classpath

/** 规范化路径：去前导 `/`、`\` 统一成 `/`、折叠重复斜杠。 */
public fun normalizeClasspathPath(path: String): String {
    val p = path.replace('\\', '/')
    val sb = StringBuilder(p.length)
    var lastSlash = false
    for (ch in p) {
        if (ch == '/') {
            if (!lastSlash && sb.isNotEmpty()) sb.append(ch)
            lastSlash = true
        } else {
            sb.append(ch)
            lastSlash = false
        }
    }
    return sb.toString()
}

/** 当前进程的 classpath（由启动流程安装）。 */
public val classpath: Classpath get() = ClasspathHolder.instance

/**
 * 按平台装载 classpath 并安装到 [ClasspathHolder]（启动流程调用一次）。
 *
 * 平台实现见各目标源集：native 读可执行文件内嵌的 `classpath.zip`，或磁盘回退目录。
 */
public expect fun installPlatformClasspath(): Classpath

/** `classpath.require(path)` 的便捷写法。 */
public fun classpathBytes(path: String): ByteArray = classpath.require(path)

/** `classpath.readText(path)` 的便捷写法。 */
public fun classpathText(path: String): String? = classpath.readText(path)
