package moe.lolosia.web.util.config

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSupertypeOf

/**
 *  代理Config根
 *
 * @author 洛洛希雅Lolosia
 * @since 2022-06-23 20:04
 */
abstract class ChainConfigRoot : ChainConfig(null) {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> get(key: String, type: KType): T? {
        return concert(get0(key, type), type) as T?
    }

    override fun <T : Any?> set(key: String, data: T?) {
        set0(key, data)
    }

    override fun getPath(bean: String): String {
        return bean
    }

    abstract fun <T : Any?> get0(key: String, type: KType): T?
    abstract fun <T : Any?> set0(key: String, data: T?)

    open fun concert(obj: Any?, type: KType): Any? {
        obj ?: return null
        if (type.isSupertypeOf(obj::class.createType(nullable = true))) return obj
        val clazz = type.classifier as? KClass<*> ?: return null
        if (obj::class == clazz) return obj
        if (clazz == String::class) return obj.toString()
        if (clazz == Boolean::class) {
            if (obj is String) return obj.toBoolean()
            if (obj is Number) return (obj != 0)
        }
        if (obj is Number) {
            when (clazz) {
                Int::class -> return obj.toInt()
                Long::class -> return obj.toLong()
                Short::class -> return obj.toShort()
                Double::class -> return obj.toDouble()
                Float::class -> return obj.toFloat()
                Char::class -> return obj.toInt().toChar()
                Byte::class -> return obj.toByte()
            }
        }
        if (obj is String) {
            when (clazz) {
                Int::class -> return obj.toInt()
                Long::class -> return obj.toLong()
                Short::class -> return obj.toShort()
                Double::class -> return obj.toDouble()
                Float::class -> return obj.toFloat()
                Char::class -> return obj[0]
                Byte::class -> return obj.toByte()
            }
        }
        return obj
    }
}