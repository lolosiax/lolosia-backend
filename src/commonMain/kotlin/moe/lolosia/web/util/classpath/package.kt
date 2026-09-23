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

/** `classpath.require(path)` 的便捷写法。 */
public fun classpathBytes(path: String): ByteArray = classpath.require(path)

/** `classpath.readText(path)` 的便捷写法。 */
public fun classpathText(path: String): String? = classpath.readText(path)
