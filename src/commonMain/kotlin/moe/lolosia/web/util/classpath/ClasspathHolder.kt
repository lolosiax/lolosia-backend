package moe.lolosia.web.util.classpath

/**
 * 全局 classpath 持有者，由启动流程安装（[moe.lolosia.web.main] 内自动完成）。
 *
 * @author 洛洛希雅Lolosia
 */
object ClasspathHolder {
    private var current: Classpath? = null

    val installed: Boolean get() = current != null

    fun install(cp: Classpath) {
        current = cp
    }

    fun clear() {
        current = null
    }

    val instance: Classpath
        get() = current ?: error(
            "classpath 尚未初始化：请在启动服务前调用 ClasspathHolder.install(...)（main 启动时会按平台自动安装）"
        )
}
