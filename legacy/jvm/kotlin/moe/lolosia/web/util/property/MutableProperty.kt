package moe.lolosia.web.util.property

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 可修改的可监听的属性
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-08-27 15:25
 */
interface MutableProperty<T> : Property<T>, ReadWriteProperty<Any?, T> {
    companion object {
        operator fun <T> invoke(value: T): MutableProperty<T> {
            return MutablePropertyImpl(value)
        }
    }

    override var value: T
}

private class MutablePropertyImpl<T>(value: T) : AbstractProperty<T>(), MutableProperty<T> {

    override var value = value
        set(value) {
            val oldValue = field
            field = value
            if (value !== oldValue) {
                afterChange(value, oldValue)
            }
        }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return value
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
    }

    /** 创建一个只读的视图  */
    fun readonlyView(): Property<T> = object : Property<T> by this {}
}