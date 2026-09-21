package moe.lolosia.web.config

import kotlinx.serialization.Serializable
import moe.lolosia.web.PORT
import moe.lolosia.web.util.router.Injectable

/**
 * 应用配置。
 *
 * 取代旧 JVM 版的 `SConfig` + snakeyaml + 反射式 `NodeChainConfig` —— Native 无反射，
 * 那套整层删除，改为一个 `@Serializable` 数据类 + JSON，多级覆盖见 [loadAppConfig]。
 *
 * 标注 [Injectable] 是为了让控制器能直接声明形参拿到生效配置（自研路由的按类型注入）。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
@Injectable
public data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val datasource: DatasourceConfig = DatasourceConfig(),
    /** 日志级别：TRACE / DEBUG / INFO / WARN / ERROR / OFF */
    val logLevel: String = "INFO",
) {
    @Serializable
    public data class ServerConfig(
        val host: String = "0.0.0.0",
        val port: Int = PORT,
    )

    /** 数据访问参数（sqlx4k + MariaDB，P0-0.6 起使用）。 */
    @Serializable
    public data class DatasourceConfig(
        val url: String = "mysql://127.0.0.1:3306/ktor-template",
        val username: String = "root",
        val password: String = "123456",
        val maxConnections: Int = 10,
    )
}
