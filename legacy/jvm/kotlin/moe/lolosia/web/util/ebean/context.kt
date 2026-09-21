package moe.lolosia.web.util.ebean

/**
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */

import io.ebean.Database
import io.ebean.Transaction
import io.ebean.typequery.IQueryBean
import moe.lolosia.web.config.web.DEFAULT_DB_NAME
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.createType

/** 从 Koin 获取命名数据库（懒代理，非默认库）。 */
fun database(name: String = DEFAULT_DB_NAME): Database =
    GlobalContext.get().get<Database>(named(name))

/** 开启一个数据库事务 */
inline fun <R> transaction(dbName: String = DEFAULT_DB_NAME, block: (tran: Transaction) -> R): R =
    database(dbName).transaction(block)

/** 给模型注入数据库实例 */
fun AbstractModel.applyDatabase() {
    database = database(dbName)
}

/** 创建数据库模型并注入数据库 */
inline fun <reified T : AbstractModel> createModel(block: T.() -> Unit = {}): T =
    T::class.java.getConstructor().newInstance().apply {
        this.database = database(dbName)
        block()
    }

private val queryDatabaseMap = mutableMapOf<KClass<*>, String>()

/** 创建数据库查询（Q* bean） */
inline fun <reified T : IQueryBean<*, *>> query(): T = query(T::class)

/** 创建数据库查询（Q* bean） */
inline fun <reified T : IQueryBean<*, *>> query(block: T.() -> Unit): T = query(T::class).apply(block)

fun <T : IQueryBean<*, *>> query(kClass: KClass<T>): T {
    val dbName = queryDatabaseMap.getOrPut(kClass) {
        // 将KClass转为KType，用于搜索父类的模板类型
        var parentType: KType? = kClass.createType()
        // 沿父类链向上遍历，直到找到携带具体实体类型参数的超类型（如 QueryBean<Entity, QBean>）
        var modelClass: KClass<*>? = null
        while (parentType != null && parentType.classifier != Any::class) {
            val classifier = parentType.arguments.firstOrNull()?.type?.classifier
            if (classifier is KClass<*>) {
                modelClass = classifier
                break
            }
            // 获取这个Type的父级Type，然后稍后再次开始循环
            parentType = (parentType.classifier as? KClass<*>)?.supertypes?.firstOrNull()
        }
        // 如果没有找到模型类型，则抛出异常
        val modelClass0 = modelClass ?: throw NoSuchElementException("找不到模型类型")

        // 创建模型实例，返回数据库名称
        (modelClass0.createInstance() as AbstractModel).dbName
    }
    val db = database(dbName)
    val constructor = kClass.java.getConstructor(Database::class.java)
    val query = constructor.newInstance(db)
    query.usingDatabase(db)
    return query
}
