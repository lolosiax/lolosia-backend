package moe.lolosia.web.util.ebean

import io.ebean.Database
import io.ebean.Model
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Transient
import java.util.function.Function

/**
 * Ebean 实体基类。
 *
 * 通过 `Model(dbName)` 把实体绑定到**命名数据库**，配合 [AbstractModel.database] 字段
 * 完成数据库路由；数据库实例由 [applyDatabase]/[createModel] 注入（懒代理）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
@MappedSuperclass
abstract class AbstractModel(dbName: String?) : Model(dbName) {
    companion object {
        /**
         * 给 QueryBean 反射用的回调。
         * @see io.ebean.typequery.QueryBean.database
         */
        @JvmStatic
        @Suppress("unused")
        private val databaseCallback = Function<Array<Any>, Unit> { t ->
            (t[0] as? AbstractModel)?.let {
                it.database = t[1] as Database
            }
        }
    }

    @JvmField
    @Transient
    final val dbName: String = dbName ?: "db"

    @JvmField
    @Transient
    final var database: Database? = null

    override fun db(): Database {
        return database ?: throw IllegalStateException("database is null, try to use Context.createModel<T>()")
    }
}
