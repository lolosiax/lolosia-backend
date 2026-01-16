package top.lolosia.web.util.property

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotMutableState
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.isAccessible

/**
 * 可监听属性
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-08-27 14:48
 */

typealias PropertyCallback<T> = (newValue: T) -> Unit
typealias PropertyChangeCallback<T> = (oldValue: T, newValue: T) -> Unit

/**
 * 将一个类属性转为可监听属性委托
 */
fun <T> KProperty0<T>.asProperty(): Property<T> {
    return DelegateProperty(this)
}

/**
 * 将一个类属性转为可监听属性委托
 */
@Suppress("UNCHECKED_CAST")
fun <T> KProperty0<T>.asPropertyOrNull(): Property<T>? {
    val delegate = getDelegate()
    if (delegate is DelegateProperty<*> && delegate.kProperty != null) return delegate as Property<T>
    if (delegate is Property<*>) return DelegateProperty(this)

    return null
}

/**
 * 将一个类属性转为可监听可修改属性委托
 */
fun <T> KMutableProperty0<T>.asProperty(): MutableProperty<T> {
    asPropertyOrNull()?.let { return it }

    throw ClassCastException("Property $this is not a delegate")
}

/**
 * 将一个类属性转为可监听可修改属性委托
 */
@Suppress("UNCHECKED_CAST")
fun <T> KMutableProperty0<T>.asPropertyOrNull(): MutableProperty<T>? {
    isAccessible = true
    val delegate = getDelegate()
    if (delegate is MutableDelegateProperty<*> && delegate.kProperty != null) return delegate as MutableProperty<T>
    if (delegate is Property<*>) return MutableDelegateProperty(this)

    return null
}

/**
 * 将一个可监听属性转为 Kotlin Compose 状态
 */
fun <T> Property<T>.asState(): State<T> {
    val kProperty = (this as? DelegateProperty<T>)?.kProperty
    val state0 = (kProperty?.let { mutableStateOf(it.get()) }
        ?: mutableStateOf(this.value)) as SnapshotMutableState<T>
    return object : SnapshotMutableState<T> by state0 {

        @Suppress("unused")
        val listener = addListener {
            state0.value = it
        }
    }
}

/**
 * 将一个可修改可监听属性转为 Kotlin Compose 可修改状态
 */
fun <T> MutableProperty<T>.asState(): MutableState<T> {

    val kProperty = (this as? MutableDelegateProperty<T>)?.kProperty
    val state0 = (kProperty?.let { mutableStateOf(it.get()) }
        ?: mutableStateOf(this.value)) as SnapshotMutableState<T>
    return object : SnapshotMutableState<T> by state0 {

        override var value: T
            get() = state0.value
            set(value) {
                component2()(value)
                state0.value = value
            }

        @Suppress("unused")
        val listener = addListener {
            state0.value = it
        }

        override fun component2(): (T) -> Unit {
            return {
                kProperty?.let { p ->
                    (p as KMutableProperty0<T>).set(it)
                } ?: run {
                    this@asState.value = it
                }
            }
        }
    }
}