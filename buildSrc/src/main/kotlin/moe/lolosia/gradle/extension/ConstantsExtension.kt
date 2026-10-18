package moe.lolosia.gradle.extension

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * VITE_* / PROJECT_NAME 常量生成配置。
 *
 * 与 [ViteJarExtension] 分开：服务端模块只需要前端上下文路径常量，
 * 不负责 vite 构建，也不应该注册一个永远不会执行的 `viteJar` 任务。
 *
 * @author 洛洛希雅Lolosia
 */
open class ConstantsExtension {

    /** 是否生成常量；纯装配模块（如 app-desktop）应关掉，避免与依赖模块生成同名常量。 */
    @Input
    @Optional
    var enabled: Boolean = true

    internal val platforms = linkedMapOf<String, String?>()

    /** 注册一个平台常量，生成名为 `VITE_<PLATFORM>`；[contextPath] 为 `null` 时生成 `/`。 */
    fun platform(name: String, contextPath: String? = null) {
        platforms[name] = contextPath
    }
}
