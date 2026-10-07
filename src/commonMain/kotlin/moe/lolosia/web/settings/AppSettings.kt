package moe.lolosia.web.settings

import kotlinx.serialization.Serializable
import moe.lolosia.web.util.router.Injectable

/**
 * 应用配置。
 *
 * 标注 [Injectable] 后，控制器形参声明 `AppSettings` 即可自动注入。
 * 多级覆盖见 [loadAppSettings]。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
@Injectable
data class AppSettings(
    val server: ServerConfig = ServerConfig(),
    val datasource: DatasourceConfig = DatasourceConfig(),
    val ddns: DdnsConfig = DdnsConfig(),
    /** 日志级别：TRACE / DEBUG / INFO / WARN / ERROR / OFF */
    val logLevel: String = "INFO",
) {
    @Serializable
    data class ServerConfig(
        val host: String = "0.0.0.0",
        val port: Int = 7129,
    )

    @Serializable
    data class DatasourceConfig(
        val url: String = "mysql://127.0.0.1:3306/ktor-template",
        val username: String = "root",
        val password: String = "123456",
        val maxConnections: Int = 10,
    )

    /**
     * Cloudflare DDNS：把 [interfaceName] 上的全局 IPv6 同步到 [recordName] 的 AAAA 记录。
     *
     * 板子（eth0 接光猫）拿到 IPv6 依赖光猫周期性 RA，地址前缀会随上游变化，
     * 故按 [intervalSeconds] 周期检查、变化即更新。
     */
    @Serializable
    data class DdnsConfig(
        /** 是否启用自动更新。 */
        val enabled: Boolean = false,
        /** Cloudflare 区域 ID（zone_id）。 */
        val zoneId: String = "",
        /** Cloudflare API Token，需 `Zone → DNS → Edit` 权限。 */
        val apiToken: String = "",
        /** 要维护的完整记录名，如 `ddns-1m.lolosia.moe`。 */
        val recordName: String = "",
        /** 记录 TTL（秒），1 表示自动。 */
        val ttl: Int = 120,
        /** 是否走 Cloudflare 代理（橙云）。要直连设备必须为 false。 */
        val proxied: Boolean = false,
        /** 取哪个网卡的全局 IPv6。 */
        val interfaceName: String = "eth0",
        /** 检查间隔（秒），下限 30。 */
        val intervalSeconds: Int = 300,
    )
}
