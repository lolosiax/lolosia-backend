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

    /**
     * 部署方式：
     * - "docker"（默认）：布到 [pwd]/docker_build，构建并启动容器（需要项目根目录有 Dockerfile、docker_shell.sh）
     * - "systemd"：no-docker，直接布到 [pwd]（work 的父目录），并把项目根目录的 <rootProject.name>.service
     *   就地更新 ExecStart 指向后，由 systemd 托管启动（保留一份 <name>.previous.jar 便于回滚）
     */
    @Input
    var mode: String = "docker"

    @Input
    @Optional
    var pwd: String? = null
}