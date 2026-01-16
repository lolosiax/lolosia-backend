package top.lolosia.web.util.property

import kotlin.properties.ReadOnlyProperty

/**
 * 可监听属性接口
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-08-27 10:40
 */
interface Property<T> : ReadOnlyProperty<Any?, T> {
    val value: T

    /**
     * 监听此属性的更改。
     * 此方法为弱引用，请自行避免意外的GC。
     */
    operator fun plusAssign(callback: PropertyCallback<T>)

    /**
     * 移除一个侦听器。
     * 一般来说，你只需要等待下一次GC，侦听器就会被自动移除。
     */
    operator fun minusAssign(callback: Function<T>)

    /**
     * 监听此属性的更改。
     * 此方法为弱引用，请自行避免意外的GC。
     */
    fun <F : PropertyCallback<T>> addListener(immediate: Boolean = false, callback: F): F

    /**
     * 监听此属性的更改。
     * 此方法为弱引用，请自行避免意外的GC。
     */
    fun <F : PropertyChangeCallback<T>> addChangeListener(immediate: Boolean = false, callback: F): F

    /** 移除一个侦听器 */
    fun removeListener(callback: Function<*>)
}