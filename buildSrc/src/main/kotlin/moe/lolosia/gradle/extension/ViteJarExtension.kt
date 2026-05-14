package moe.lolosia.gradle.extension

import org.gradle.api.Action
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import java.io.Serializable

/**
 * Lolosia Spring Boot 构建与部署插件
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 11:37:19
 */
open class ViteJarExtension {

    internal val platforms = linkedMapOf<String, PlatformConfig>()

    fun platform(name: String, action: Action<PlatformConfig>) {
        val config = PlatformConfig(name)
        action.execute(config)
        platforms[name] = config
    }
}

open class PlatformConfig(val name: String) : Serializable {

    @Input
    @Optional
    var contextPath: String? = null

    @Input
    @Optional
    var dir: String? = null

    companion object {
        private const val serialVersionUID = 1L
    }
}