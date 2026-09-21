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
 * 原生（Kotlin/Native）部署产物配置。
 *
 * 可执行文件是自包含单文件，没有 fat jar 的拆包/增量补库流程，
 * 推送时整包替换、随后由 systemd 直接拉起（不是 `java -jar`）。
 *
 * @author 洛洛希雅Lolosia
 */
open class BinaryConfig {

    /** Kotlin/Native 目标名，决定 link 任务与产物路径，如 `linuxArm64`。 */
    @Input
    var target: String = "linuxArm64"

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

    /**
     * 传给可执行文件的启动参数。本模板的 `main()` 取第一个参数作为监听端口，
     * 所以并行验证时在这里写新端口（如 `listOf("7130")`）。
     */
    @Input
    @Optional
    var args: MutableList<String> = mutableListOf()
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