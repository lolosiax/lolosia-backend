package top.lolosia.web.util.ebean

import io.ebean.Database
import io.ebean.Model
import jakarta.persistence.MappedSuperclass
import java.util.function.Function

@MappedSuperclass
abstract class AbstractModel(dbName: String?) : Model(dbName) {
    companion object {
        /**
         * 给 TQRootBean 反射用的回调。
         * @see io.ebean.typequery.TQRootBean.database
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
    val dbName: String = dbName ?: "db"

    @JvmField
    @Transient
    var database: Database? = null

    override fun db(): Database {
        return database ?: throw IllegalStateException("database is null, try to use Context.createModel<T>()")
    }
}