package moe.lolosia.web.util.ebean

import io.ebean.typequery.IQueryBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ebean.*
import java.sql.Timestamp
import java.util.*
import java.util.stream.Stream

/**
 * EbeanORM 异步查询工具类。
 * @param <T> the entity bean type (normal entity bean type e.g. Customer)
 * @param <Q> the specific query bean type (e.g. QCustomer)
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-04-09 13:38
 */
class AsyncQueryBean<T, Q : IQueryBean<T, Q>>(val query: Q) {

    private suspend fun <R> execute(block: suspend () -> R): R {
        return withContext(Dispatchers.IO) {
            block()
        }
    }

    suspend fun findOne(): T? = execute { query.findOne() }

    suspend fun findOneOrEmpty(): Optional<T> = execute { query.findOneOrEmpty() }

    suspend fun findList(): List<T> = execute { query.findList() }

    suspend fun findStream(): Stream<T> = execute { query.findStream() }

    suspend fun findSet(): Set<T> = execute { query.findSet() }

    suspend fun <A> findIds(): List<A> = execute { query.findIds() }

    suspend fun <K> findMap(): Map<K, T> = execute { query.findMap() }

    suspend fun findIterate(): QueryIterator<T> = execute { query.findIterate() }

    suspend fun <A> findSingleAttributeList(): List<A> = execute { query.findSingleAttributeList() }

    suspend fun <A> findSingleAttributeSet(): Set<A> = execute { query.findSingleAttributeSet() }

    suspend fun <A> findSingleAttribute(): A? = execute { query.findSingleAttribute() }

    suspend fun <A> findSingleAttributeOrEmpty(): Optional<A> = execute { query.findSingleAttributeOrEmpty() }

    suspend fun findCount(): Int = execute { query.findCount() }

    suspend fun findFutureCount(): FutureRowCount<T> = execute { query.findFutureCount() }

    suspend fun findFutureIds(): FutureIds<T> = execute { query.findFutureIds() }

    suspend fun findFutureList(): FutureList<T> = execute { query.findFutureList() }

    suspend fun findPagedList(): PagedList<T> = execute { query.findPagedList() }

    suspend fun exists(): Boolean = execute { query.exists() }

    suspend fun findVersions(): List<Version<T>> = execute { query.findVersions() }

    suspend fun findVersionsBetween(start: Timestamp, end: Timestamp): List<Version<T>> = execute {
        query.findVersionsBetween(start, end)
    }

    suspend fun delete(): Int = execute { query.delete() }

    suspend fun delete(transaction: Transaction): Int = execute {
        query.usingTransaction(transaction)
        query.delete()
    }

    val generatedSql: String get() = query.generatedSql

    val beanType: Class<T> get() = query.beanType

}
