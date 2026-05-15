package moe.lolosia.gradle.extension

import org.gradle.api.Action
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * Docker 镜像配置扩展
 * @author 洛洛希雅Lolosia
 * @since 2026-05-15
 */
open class DockerExtension {

    internal val images = linkedMapOf<String, DockerImageConfig>()

    @JvmOverloads
    fun image(name: String, action: Action<DockerImageConfig>? = null): DockerImageConfig {
        val config = images.getOrPut(name) {
            DockerImageConfig(name)
        }
        action?.execute(config)
        return config
    }
}

open class DockerImageConfig(val name: String) {

    @Input
    @Optional
    var mirror: String? = null

    @Input
    @Optional
    var autoUntagMirror: Boolean = false
}