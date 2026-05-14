package moe.lolosia.web.util.ebean

import io.ebean.typequery.IQueryBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ebean.*
import java.sql.Timestamp
import java.util.*
import java.util.stream.Stream

/**
 * EbeanORM 异步查询工具类
 * @param <T> the entity bean type (normal entity bean type e.g. Customer)
 * @param <Q> the specific query bean type (e.g. QCustomer)
 * @since 2026-04-09 13:37:42
 */
class AsyncQueryBean<T, Q : IQueryBean<T, Q>>(val query: Q) {

    /**
     * 执行异步查询
     * @param block 异步查询块
     * @return 异步查询结果
     */
    private suspend fun <R> execute(block: suspend () -> R): R {
        return withContext(Dispatchers.IO) {
            block()
        }
    }

    /**
     * 异步执行findOne操作
     * @return 查询到的单个实体或null
     */
    suspend fun findOne(): T? = execute { query.findOne() }

    /**
     * 异步执行findOneOrEmpty操作
     * @return 查询到的单个实体的Optional
     */
    suspend fun findOneOrEmpty(): Optional<T> = execute { query.findOneOrEmpty() }

    /**
     * 异步执行findList操作
     * @return 查询到的实体列表
     */
    suspend fun findList(): List<T> = execute { query.findList() }

    /**
     * 异步执行findStream操作
     * @return 查询到的实体流
     */
    suspend fun findStream(): Stream<T> = execute { query.findStream() }

    /**
     * 异步执行findSet操作
     * @return 查询到的实体集合
     */
    suspend fun findSet(): Set<T> = execute { query.findSet() }

    /**
     * 异步执行findIds操作
     * @return 查询到的ID列表
     */
    suspend fun <A> findIds(): List<A> = execute { query.findIds() }

    /**
     * 异步执行findMap操作
     * @return 查询到的实体映射
     */
    suspend fun <K> findMap(): Map<K, T> = execute { query.findMap() }

    /**
     * 异步执行findIterate操作
     * @return 查询迭代器
     */
    suspend fun findIterate(): QueryIterator<T> = execute { query.findIterate() }

    /**
     * 异步执行findSingleAttributeList操作
     * @return 单属性值列表
     */
    suspend fun <A> findSingleAttributeList(): List<A> = execute { query.findSingleAttributeList() }

    /**
     * 异步执行findSingleAttributeSet操作
     * @return 单属性值集合
     */
    suspend fun <A> findSingleAttributeSet(): Set<A> = execute { query.findSingleAttributeSet() }

    /**
     * 异步执行findSingleAttribute操作
     * @return 单属性值
     */
    suspend fun <A> findSingleAttribute(): A? = execute { query.findSingleAttribute() }

    /**
     * 异步执行findSingleAttributeOrEmpty操作
     * @return 单属性值的Optional
     */
    suspend fun <A> findSingleAttributeOrEmpty(): Optional<A> = execute { query.findSingleAttributeOrEmpty() }

    /**
     * 异步执行findCount操作
     * @return 查询计数
     */
    suspend fun findCount(): Int = execute { query.findCount() }

    /**
     * 异步执行findFutureCount操作
     * @return 异步计数结果
     */
    suspend fun findFutureCount(): FutureRowCount<T> = execute { query.findFutureCount() }

    /**
     * 异步执行findFutureIds操作
     * @return 异步ID结果
     */
    suspend fun findFutureIds(): FutureIds<T> = execute { query.findFutureIds() }

    /**
     * 异步执行findFutureList操作
     * @return 异步列表结果
     */
    suspend fun findFutureList(): FutureList<T> = execute { query.findFutureList() }

    /**
     * 异步执行findPagedList操作
     * @return 分页列表结果
     */
    suspend fun findPagedList(): PagedList<T> = execute { query.findPagedList() }

    /**
     * 异步执行exists操作
     * @return 是否存在结果
     */
    suspend fun exists(): Boolean = execute { query.exists() }

    /**
     * 异步执行findVersions操作
     * @return 版本列表
     */
    suspend fun findVersions(): List<Version<T>> = execute { query.findVersions() }

    /**
     * 异步执行findVersionsBetween操作
     * @param start 起始时间
     * @param end 结束时间
     * @return 时间范围内的版本列表
     */
    suspend fun findVersionsBetween(start: Timestamp, end: Timestamp): List<Version<T>> = execute {
        query.findVersionsBetween(start, end)
    }

    /**
     * 异步执行delete操作
     * @return 删除的记录数量
     */
    suspend fun delete(): Int = execute { query.delete() }

    /**
     * 异步执行delete操作
     * @param transaction 事务
     * @return 删除的记录数量
     */
    suspend fun delete(transaction: Transaction): Int = execute {
        query.usingTransaction(transaction)
        query.delete()
    }

    /**
     * 异步获取生成的SQL语句
     * @return 生成的SQL语句
     */
    val generatedSql: String get() = query.generatedSql

    /**
     * 异步获取实体类型
     * @return 实体的Class类型
     */
    val beanType: Class<T> get() = query.beanType

}