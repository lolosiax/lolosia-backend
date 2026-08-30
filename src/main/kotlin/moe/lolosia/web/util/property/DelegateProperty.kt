package moe.lolosia.web.util.property

import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.isAccessible

/**
 * 委托的可监听属性
 * @author 洛洛希雅Lolosia
 * @since 2024-08-28 23:49
 */
open class DelegateProperty<T> : Property<T> {

    constructor(property: Property<T>) {
        this.property0 = property
        this.propertyGetter = { property }
    }

    constructor(property: KProperty0<T>) {
        kProperty = property
        property.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        this.property0 = property.getDelegate() as Property<T>
        this.propertyGetter = { this.property0!! }
    }

    constructor(property: () -> Property<T>) {
        this.propertyGetter = property
    }

    var kProperty: KProperty0<T>? = null
        private set
    private var property0: Property<T>? = null
    private val propertyGetter: () -> Property<T>

    /** 被委托到的属性 */
    open val property: Property<T>
        get() = property0 ?: propertyGetter()

    override val value: T
        get() = property.value

    override fun removeListener(callback: Function<*>) {
        property.removeListener(callback)
    }

    override fun <F : PropertyChangeCallback<T>> addChangeListener(immediate: Boolean, callback: F): F {
        return property.addChangeListener(immediate, callback)
    }

    override fun <F : PropertyCallback<T>> addListener(immediate: Boolean, callback: F): F {
        return property.addListener(immediate, callback)
    }

    override fun minusAssign(callback: Function<T>) {
        return property.minusAssign(callback)
    }

    override fun plusAssign(callback: PropertyCallback<T>) {
        property.plusAssign(callback)
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return this.property.getValue(thisRef, property)
    }
}