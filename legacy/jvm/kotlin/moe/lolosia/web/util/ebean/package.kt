package moe.lolosia.web.util.ebean

/**
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */

import com.fasterxml.jackson.databind.ObjectMapper
import io.ebean.Database
import io.ebean.Model
import io.ebean.Transaction
import io.ebean.bean.EntityBean
import io.ebean.typequery.IQueryBean
import jakarta.persistence.Table
import moe.lolosia.web.util.bundle.Bundle
import java.util.*
import kotlin.reflect.KProperty

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
inline fun <reified T : Model> Database.delete(id: Any): Int {
    return this.delete(T::class.java, id)
}

fun String.toUuid(): UUID {
    return UUID.fromString(this)
}

fun String?.toUuidOrNull(): UUID? {
    if (this.isNullOrBlank()) return null
    return UUID.fromString(this)
}

inline fun <T, R : IQueryBean<T, R>> IQueryBean<T, R>.or(block: () -> Unit) {
    or()
    block()
    endJunction()
}

inline fun <T, R : IQueryBean<T, R>> IQueryBean<T, R>.and(block: () -> Unit) {
    and()
    block()
    endJunction()
}

inline fun <T, R : IQueryBean<T, R>> IQueryBean<T, R>.not(block: () -> Unit) {
    not()
    block()
    endJunction()
}

@Suppress("UNCHECKED_CAST")
inline fun <T, R : IQueryBean<T, R>> IQueryBean<T, R>.orderBy(block: R.() -> Unit): R {
    orderBy()
    (this as R).block()
    return this as R
}

/**
 * Execute as a delete query returning the number of rows deleted using the given transaction.
 */
fun <T, R : IQueryBean<T, R>> IQueryBean<T, R>.delete(tran: Transaction): Int {
    usingTransaction(tran)
    return delete()
}

inline fun <R> Database.transaction(block: (tran: Transaction) -> R): R {
    val transaction = this.createTransaction()
    var err: Throwable? = null
    try {
        return block(transaction)
    } catch (e: Throwable) {
        err = e
        throw e
    } finally {
        if (err == null) transaction.commit()
        else transaction.rollback()
    }
}

private val modelPropCache = mutableMapOf<Class<out Model>, Set<String>>()
val Model.props: Set<String>
    get() {
        val clazz = this::class.java
        if (clazz in modelPropCache) return modelPropCache[clazz]!!
        val list = (this as EntityBean)._ebean_getPropertyNames()
        val set = Collections.unmodifiableSet(LinkedHashSet(list.toList()))
        modelPropCache[clazz] = set

        return set
    }

/**
 * Mark the property as set or 'is loaded'.
 */
fun Model.markPropertySet(propertyName: String) {
    (this as EntityBean)._ebean_getIntercept().setPropertyLoaded(propertyName, true)
}

/**
 * 更新时排除一些属性
 */
fun Model.unmark(vararg prop: String) {
    val prop0 = listOf(*prop, "createdAt", "updatedAt") intersect props
    prop0.forEach(this::markPropertyUnset)
}

/**
 * 更新时排除一些属性
 */
fun Model.unmark(vararg prop: KProperty<*>) {
    val prop1 = prop.map { it.name }
    val prop2 = (prop1 + listOf("createdAt", "updatedAt")) intersect props

    prop2.forEach(this::markPropertyUnset)
}

inline fun <reified T : Model> tableName(): String {
    return T::class.java.getAnnotation(Table::class.java).name
}

fun <T : Model> ObjectMapper.toModel(obj: Bundle, clazz: Class<T>, markProperty: Boolean = false): T {
    val model = readValue(writeValueAsString(obj), clazz)
    if (markProperty) model.mark(obj)
    return model
}

/**
 * 将一个Bundle对象转为Model
 */
inline fun <reified T : Model> ObjectMapper.toModel(obj: Bundle, markProperty: Boolean = false): T {
    return toModel(obj, T::class.java, markProperty)
}

/**
 * 仅标记Bundle包含的属性，将不包含的属性排除（用于部分更新）
 */
fun Model.mark(bundle: Bundle) {
    val props = props - ((bundle.keys - listOf("createdAt", "updatedAt")) intersect props)
    unmark(*props.toTypedArray())
}
