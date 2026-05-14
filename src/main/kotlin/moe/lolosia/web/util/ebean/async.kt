package moe.lolosia.web.util.ebean

import io.ebean.typequery.IQueryBean

/**
 * 将IQueryBean转换为AsyncQueryBean
 * @param <T> the entity bean type (normal entity bean type e.g. Customer)
 * @param <Q> the specific query bean type (e.g. QCustomer)
 * @since 2026-04-09 13:38:03
 */
fun <T, Q : IQueryBean<T, Q>> IQueryBean<T, Q>.toAsync(): AsyncQueryBean<T, Q> {
    @Suppress("UNCHECKED_CAST")
    return AsyncQueryBean(this as Q)
}