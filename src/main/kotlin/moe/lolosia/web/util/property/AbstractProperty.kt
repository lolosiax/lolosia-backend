package moe.lolosia.web.util.property

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

/**
 * 抽象可观察属性
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-08-28 16:25
 */
abstract class AbstractProperty<T> : Property<T> {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(AbstractProperty::class.java)
    }

    protected open val handler: MutableMap<Function<*>, Unit> = WeakHashMap()

    @Suppress("UNCHECKED_CAST")
    protected open fun afterChange(newValue: T, oldValue: T) {
        synchronized(handler) {
            handler.keys.forEach {
                try {
                    if (it is Function2<*, *, *>) {
                        (it as PropertyChangeCallback<T>).invoke(oldValue, newValue)
                    } else if (it is Function1<*, *>) {
                        (it as PropertyCallback<T>).invoke(newValue)
                    }
                } catch (e: Exception) {
                    logger.error(e.message, e)
                }
            }
        }
    }

    override operator fun plusAssign(callback: PropertyCallback<T>) {
        addListener(false, callback)
    }

    override operator fun minusAssign(callback: Function<T>) {
        removeListener(callback)
    }

    override fun <F : PropertyCallback<T>> addListener(immediate: Boolean, callback: F): F {
        synchronized(handler) {
            handler.put(callback, Unit)
        }
        if (immediate) callback(value)
        return callback
    }

    override fun <F : PropertyChangeCallback<T>> addChangeListener(immediate: Boolean, callback: F): F {
        synchronized(handler) {
            handler.put(callback, Unit)
        }
        if (immediate) callback(value, value)
        return callback
    }

    override fun removeListener(callback: Function<*>) {
        synchronized(handler) {
            handler.remove(callback)
        }
    }
}