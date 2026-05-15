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

    @JvmOverloads
    fun platform(name: String, action: Action<PlatformConfig>? = null): PlatformConfig {
        val config = platforms.getOrPut(name) {
            PlatformConfig(name)
        }
        action?.execute(config)
        return config
    }
}

open class PlatformConfig(val name: String) : Serializable {

    @Input
    @Optional
    var contextPath: String? = null

    @Input
    @Optional
    var dir: String? = null

    @Input
    @Optional
    var cache: CacheConfig = CacheConfig()

    fun cache(action: Action<CacheConfig>) {
        action.execute(cache)
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

open class CacheConfig : Serializable {

    @Input
    @Optional
    var dirs: MutableList<String> = mutableListOf("src", "typings", "public")

    @Input
    @Optional
    var files: MutableList<String> = mutableListOf("package.json")

    @Input
    @Optional
    var filePatterns: MutableList<String> = mutableListOf("vite*.ts")

    companion object {
        private const val serialVersionUID = 1L
    }
}