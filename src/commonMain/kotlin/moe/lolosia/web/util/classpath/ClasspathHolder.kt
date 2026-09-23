package moe.lolosia.web.util.classpath

/**
 * 全局 classpath 持有者，由启动流程安装（`startServer` 内自动完成）。
 *
 * @author 洛洛希雅Lolosia
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
