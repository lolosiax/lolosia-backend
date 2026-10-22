package moe.lolosia.gradle.thirdparty

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync

/**
 * 第三方原生库插件。
 *
 * 在根工程应用后：
 * - 读取 [ThirdpartySettings]（默认关闭）；
 * - 按 `thirdparty/manifest.json` 为每个库 × 目标建一个独立依赖配置与 `thirdpartySync<库><目标>` 任务；
 * - 启用仓库时从 `moe.lolosia.thirdparty:<库>-<目标>:<版本>@zip` 拉取并解压；
 * - 未启用时回退到仓库里现有的 `thirdparty/include` 与 `thirdparty/<目标>`；
 * - 暴露 [ThirdpartyExtension.dir] / [ThirdpartyExtension.syncTask] 给各模块的 cinterop 使用。
 *
 * @author 洛洛希雅Lolosia
 */
class ThirdpartyPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("thirdparty", ThirdpartyExtension::class.java)
        val settings = ThirdpartySettings.load(project)
        extension.enabled = settings.enabled
        extension.repoUrl = settings.repoUrl

        if (settings.enabled) {
            if (settings.repoUrl.isBlank()) {
                throw GradleException("已启用 lolosia.thirdparty.enabled，但未配置 lolosia.thirdparty.repo")
            }
            project.repositories.maven {
                url = project.uri(settings.repoUrl)
                isAllowInsecureProtocol = true
                if (settings.username.isNotBlank() || settings.password.isNotBlank()) {
                    credentials {
                        username = settings.username
                        password = settings.password
                    }
                }
            }
        }

        val manifest = ObjectMapper().readTree(project.rootProject.file("thirdparty/manifest.json"))
        val groupId = manifest["groupId"].asText()
        val syncTasks = mutableListOf<org.gradle.api.tasks.TaskProvider<*>>()

        manifest["libraries"].fields().forEach { (library, spec) ->
            val version = spec["version"].asText()
            val headers = spec["headers"].map { it.asText() }
            spec["libs"].fields().forEach { (target, archivesNode: JsonNode) ->
                val archives = archivesNode.map { it.asText() }
                val configurationName = "thirdparty${library.cap()}${target.cap()}"
                val configuration = project.configurations.findByName(configurationName)
                    ?: project.configurations.create(configurationName) { isTransitive = false }

                if (settings.enabled) {
                    project.dependencies.add(configurationName, "$groupId:$library-$target:$version@zip")
                }

                val outputDir = project.layout.buildDirectory.dir("thirdparty/$library/$target")
                val sync = project.tasks.register("thirdpartySync${library.cap()}${target.cap()}", Sync::class.java) {
                    group = "thirdparty"
                    description = "准备第三方库 $library / $target"
                    if (settings.enabled) {
                        from(project.provider { project.zipTree(configuration.singleFile) })
                    } else {
                        from(project.rootProject.file("thirdparty/include")) {
                            headers.forEach { header ->
                                if (header.endsWith(".h")) include(header) else include("$header/**")
                            }
                            into("include")
                        }
                        from(project.rootProject.file("thirdparty/$target")) {
                            archives.forEach { include(it) }
                            into("lib")
                        }
                    }
                    into(outputDir)
                }
                extension.register(library, target, outputDir, sync)
                syncTasks += sync
            }
        }

        project.tasks.register("thirdpartySync") {
            group = "thirdparty"
            description = "准备全部第三方原生库（仓库拉取或本地回退）"
            dependsOn(syncTasks)
        }
    }
}

/** 任务名片段首字母大写。 */
private fun String.cap(): String = replaceFirstChar { it.uppercaseChar() }
