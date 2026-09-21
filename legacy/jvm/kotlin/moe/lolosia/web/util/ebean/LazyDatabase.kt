package moe.lolosia.web.util.ebean

import io.ebean.Database
import io.ebeaninternal.api.SpiEbeanServer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 懒加载 + 失败自动重试的 Database 代理。
 *
 * 规避 Ebean 默认数据库的静态初始化中毒问题（NoClassDefFoundError 永久失败）：
 * - 代理直到第一次真正访问数据库时才调用 [creator] 创建底层实例
 * - 创建失败时底层实例保持 null，下次访问自动重试
 * - 所有持有方拿到的是同一个代理，底层实例可随时重建
 *
 * 基于 java.lang.reflect.Proxy 代理 [SpiEbeanServer] 接口（Ebean 内部可安全 cast），
 * 无需 Spring 的 CGLib。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 14:05
 */
class LazyDatabase(
    private val creator: () -> Database,
) {
    @Volatile
    private var real: Database? = null

    fun get(): Database {
        real?.let { return it }
        synchronized(this) {
            real?.let { return it }
            return creator().also { real = it }
        }
    }

    /** 返回可注入的代理实例 */
    fun proxy(): Database {
        return Proxy.newProxyInstance(
            SpiEbeanServer::class.java.classLoader,
            arrayOf(SpiEbeanServer::class.java),
            Handler(),
        ) as Database
    }

    private inner class Handler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                // shutdown 只对已创建的实例生效，避免强制拉起数据库
                "shutdown" -> {
                    real?.let { method.invoke(it, *(args ?: emptyArray())) }
                    null
                }
                "toString" -> "LazyDatabase(${real?.javaClass?.simpleName ?: "uninitialized"})"
                else -> method.invoke(get(), *(args ?: emptyArray()))
            }
        }
    }
}
