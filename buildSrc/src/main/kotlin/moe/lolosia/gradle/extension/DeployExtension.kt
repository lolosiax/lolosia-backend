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

    /**
     * 原生部署产物配置。写了 `binary { }` 就部署 Kotlin/Native 可执行文件，
     * 否则部署 JVM 的 `bootJar`。
     */
    internal var binaryConfig: BinaryConfig? = null

    internal val servers = linkedMapOf<String, ServerConfig>()

    @JvmOverloads
    fun server(name: String, action: Action<ServerConfig>? = null): ServerConfig {
        val config = servers.getOrPut(name) { ServerConfig(name) }
        action?.execute(config)
        return config
    }

    @JvmOverloads
    fun binary(action: Action<BinaryConfig>? = null): BinaryConfig {
        val config = binaryConfig ?: BinaryConfig().also { binaryConfig = it }
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

/**
 * 原生（Kotlin/Native）部署的共享设置。
 *
 * 注意：**哪台机器部署原生、部署哪个架构，由 [ServerConfig.abi] 决定**，不在这里写。
 * 这里只放跨服务器共享的构建与单元命名。
 *
 * @author 洛洛希雅Lolosia
 */
open class BinaryConfig {

    /** 链接类型：`release`（默认）或 `debug`。 */
    @Input
    var buildType: String = "release"

    /** 远端可执行文件名（放在部署目录下）；默认取 `rootProject.name`。 */
    @Input
    @Optional
    var name: String? = null

    /**
     * systemd 单元名；默认 `<rootProject.name>-native.service`。
     * 与 JVM 版用不同的单元名，便于并行验证、随时回滚。
     */
    @Input
    @Optional
    var unitName: String? = null
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
     * 目标机 ABI：`arm64` 或 `x64`（也接受 `aarch64` / `amd64` / `x86_64`）。
     *
     * 配置了就表示**这台机器部署 Kotlin/Native 可执行文件**（对应 `linuxArm64` / `linuxX64` 目标）；
     * 不配则部署 JVM 的 `bootJar`。同一项目可以给不同服务器配不同 ABI。
     */
    @Input
    @Optional
    var abi: String? = null

    /**
     * 传给可执行文件的启动参数。本模板的 `main()` 取第一个参数作为监听端口，
     * 所以并行验证时在这里写新端口（如 `["7130"]`）。
     */
    @Input
    @Optional
    var args: MutableList<String> = mutableListOf()

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