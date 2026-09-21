package moe.lolosia.web.util.classpath

/**
 * 可执行文件内的 classpath（资源访问统一入口）。
 *
 * 背景：Kotlin/Native 没有 JVM 的 classloader，也没有 `getResource()`。
 * 但 **classpath 本质就是一个 zip**，所以这里自造一个 `classpath.zip` 并在构建期嵌入二进制，
 * 运行时通过本接口访问 —— 效果与 Java 的 `ClassLoader.getResourceAsStream` 等价，
 * 且路径写法与打包前的资源目录结构**完全一致**（都是 zip 内的相对路径）。
 *
 * 路径约定：
 * - 相对路径以 zip 根为基准，如 `static/home/index.html`、`sql/sys_user.sql`；
 * - 允许前导 `/`（会自动去掉）、允许用 `\` 分隔；
 * - 不支持 `..` 上跳。
 *
 * @author 洛洛希雅Lolosia
 */
public interface Classpath {

    /** 全部条目路径（`/` 分隔、无前导斜杠），已排序。 */
    public val entries: List<String>

    /** 读取条目；不存在返回 `null`。 */
    public fun read(path: String): ByteArray?

    /** 列表查询：返回以 [prefix] 开头的条目路径。 */
    public fun list(prefix: String = ""): List<String>

    /** 是否存在该条目。 */
    public fun exists(path: String): Boolean = read(path) != null
}

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

/** 读取并解码为文本（默认 UTF-8）。 */
public fun Classpath.readText(path: String): String? = read(path)?.decodeToString()

/** 读取条目，不存在则抛异常（适合"必须有"的资源，如前端 index.html）。 */
public fun Classpath.require(path: String): ByteArray =
    read(path) ?: throw NoSuchElementException("classpath 中不存在资源：$path")

// ===== 全局访问 =====

/**
 * 全局 classpath 持有者。
 *
 * 由启动流程安装（`startServer` 内自动完成），业务代码可直接用顶层属性 [classpath]：
 * ```
 * val index = classpath.require("static/home/index.html")
 * ```
 */
public object ClasspathHolder {
    private var current: Classpath? = null

    public val installed: Boolean get() = current != null

    public fun install(cp: Classpath) {
        current = cp
    }

    public fun clear() {
        current = null
    }

    public val instance: Classpath
        get() = current ?: error(
            "classpath 尚未初始化：请在启动服务前调用 ClasspathHolder.install(...)（startServer 会按平台自动安装）"
        )
}

/** 当前进程的 classpath（由启动流程安装）。 */
public val classpath: Classpath get() = ClasspathHolder.instance

/** `classpath.require(path)` 的便捷写法。 */
public fun classpathBytes(path: String): ByteArray = classpath.require(path)

/** `classpath.readText(path)` 的便捷写法。 */
public fun classpathText(path: String): String? = classpath.readText(path)
