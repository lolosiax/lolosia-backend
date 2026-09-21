package moe.lolosia.web.db

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.mysql.IMySQL
import io.github.smyrgeorge.sqlx4k.mysql.mySQL
import moe.lolosia.web.config.AppConfig
import moe.lolosia.web.util.router.Injectable

/**
 * 数据访问入口（sqlx4k + MySQL/MariaDB）。
 *
 * sqlx4k **不是 ORM**：它只提供执行原语（`execute` / `fetchAll` / 事务 / `RowMapper`），
 * native 侧底层是 Rust `sqlx` 的 FFI（Maven 产物自带预编译静态库，消费者不需要 Rust 工具链）。
 * 0.6 起按方案 §5 清单把 legacy 的实体与查询迁到 `service/`，控制器注入本类型或各 Service 使用。
 *
 * 驱动是**懒连接**：连接池在首次执行语句时才建连，因此数据库不可用不会挡住服务启动。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
public class Database(config: AppConfig.DatasourceConfig) {

    public val driver: IMySQL = mySQL(
        url = config.url,
        username = config.username,
        password = config.password,
        options = ConnectionPool.Options(maxConnections = config.maxConnections),
    )

    /** 连通性探测（运维 / 冒烟用）：返回「服务端版本 / 当前库名」。 */
    public suspend fun ping(): String {
        val rs = driver.fetchAll("select version() as version, database() as db").getOrThrow()
        val row = rs.rows.firstOrNull() ?: error("数据库没有返回结果")
        return "${row.get("version").asString()} / ${row.get("db").asString()}"
    }
}
