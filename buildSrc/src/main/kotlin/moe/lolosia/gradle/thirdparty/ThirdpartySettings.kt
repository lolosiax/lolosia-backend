package moe.lolosia.gradle.thirdparty

import org.gradle.api.Project
import java.util.Properties

/**
 * 第三方工件源配置。
 *
 * 优先取 Gradle 属性（`-Plolosia.thirdparty.*`），其次取主工程根目录的 `local.properties`。
 * 默认关闭：不配置时主工程继续使用 `thirdparty/` 下现有的头文件与静态库。
 *
 * @author 洛洛希雅Lolosia
 */
internal class ThirdpartySettings(
    val enabled: Boolean,
    val repoUrl: String,
    val username: String,
    val password: String,
) {
    companion object {
        fun load(project: Project): ThirdpartySettings {
            val props = Properties()
            val file = project.rootProject.file("local.properties")
            if (file.isFile) file.inputStream().use { props.load(it) }

            fun value(key: String): String? =
                (project.findProperty(key) as String?) ?: props.getProperty(key)

            return ThirdpartySettings(
                enabled = value("lolosia.thirdparty.enabled")?.toBooleanStrictOrNull() ?: false,
                repoUrl = value("lolosia.thirdparty.repo").orEmpty(),
                username = value("lolosia.thirdparty.username").orEmpty(),
                password = value("lolosia.thirdparty.password").orEmpty(),
            )
        }
    }
}
