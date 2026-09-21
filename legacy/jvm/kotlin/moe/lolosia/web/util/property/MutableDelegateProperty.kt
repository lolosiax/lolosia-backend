package moe.lolosia.web.util.property

import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty

/**
 * 委托的可监听属性
 * @author 洛洛希雅Lolosia
 * @since 2024-08-28 23:49
 */
open class MutableDelegateProperty<T> : DelegateProperty<T>, MutableProperty<T> {

    constructor(property: MutableProperty<T>) : super(property)

    constructor(property: KMutableProperty0<T>) : super(property)

    constructor(property: () -> MutableProperty<T>) : super(property)

    override var value: T
        get() = super.value
        set(value) {
            (property as MutableProperty<T>).value = value
        }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        (this.property as MutableProperty<T>).setValue(thisRef, property, value)
    }
}