package moe.lolosia.web.util.property

import kotlin.reflect.KProperty

/**
 * 触发式可监听属性基类
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-08-28 17:31
 */
abstract class AbstractTriggerProperty<T> : AbstractProperty<T>() {
    protected object EMPTY
    protected var value0: Any? = EMPTY
    protected abstract val rawValue: T

    override val value: T
        get() {
            if (value0 == EMPTY) {
                rawValue.let {
                    value0 = it
                    afterChange(it, it)
                }
            }
            @Suppress("UNCHECKED_CAST")
            return value0 as T
        }


    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return value
    }

    /** 主动更新属性值 */
    protected open fun trigger() {
        var before = value0
        val after = rawValue
        if (before == EMPTY) before = after
        value0 = after
        @Suppress("UNCHECKED_CAST")
        afterChange(after, before as T)
    }
}