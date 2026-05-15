package moe.lolosia.gradle.extension

import org.gradle.api.Action
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * 部署配置扩展
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 11:37:37
 */
open class DeployExtension {

    @Input
    @Optional
    var activate: String? = null

    internal val servers = linkedMapOf<String, ServerConfig>()

    @JvmOverloads
    fun server(name: String, action: Action<ServerConfig>? = null): ServerConfig {
        val config = servers.getOrPut(name) { ServerConfig(name) }
        action?.execute(config)
        return config
    }

    fun activeServer(): ServerConfig {
        val name = activate ?: servers.keys.firstOrNull()
        ?: throw IllegalStateException("No deploy server configured")
        return servers[name]
            ?: throw IllegalStateException("Active server '$name' not found in deploy configuration")
    }
}

open class ServerConfig(val name: String) {

    @Input
    var host: String = "localhost"

    @Input
    var port: Int = 22

    @Input
    var user: String = "root"

    @Input
    var password: String = "123456"

    @Input
    @Optional
    var pwd: String? = null
}