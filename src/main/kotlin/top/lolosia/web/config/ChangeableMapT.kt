package top.lolosia.web.config

import top.lolosia.web.util.bundle.Bundle
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

abstract class ChangeableMapT<T : Any> {

    protected abstract val data: Bundle
    protected open val defaultValue: T? = null

    protected open operator fun setValue(target: ChangeableMapT<T>, property: KProperty<*>, value: T) {
        data[property.name] = value
    }

    @Suppress("UNCHECKED_CAST")
    protected open operator fun getValue(target: ChangeableMapT<T>, property: KProperty<*>): T {
        return convert(data[property.name], property.returnType.classifier as KClass<T>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> convert(value: Any?, clazz: KClass<T>): T {
        return when (clazz) {
            Double::class -> (value as Number).toDouble() as T
            Float::class -> (value as Number).toFloat() as T
            Byte::class -> (value as Number).toByte() as T
            Char::class -> (value as Number).toInt().toChar() as T
            Short::class -> (value as Number).toShort() as T
            Int::class -> (value as Number).toInt() as T
            Long::class -> (value as Number).toLong() as T
            else -> value as T
        }
    }
}