package moe.lolosia.thirdparty

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import java.util.Properties

/**
 * 第三方库发布插件。
 *
 * 每个子项目只声明 `thirdpartyLibrary { id = "..." }`，插件负责：
 * - 从 `thirdparty/manifest.json` 读取版本、头文件与各目标静态库；
 * - 生成 `package<目标>` 任务，把 `thirdparty/include` 与 `thirdparty/<目标>` 中属于该库的
 *   文件打成 `moe.lolosia.thirdparty:<库>-<目标>:<版本>@zip`；
 * - 配置 `maven-publish`，仓库地址与账号来自主工程根目录的 `local.properties`；
 * - 若子项目存在 `build.ps1`，注册 `buildFromSource`（仅 Windows）。
 *
 * @author 洛洛希雅Lolosia
 */
class ThirdpartyPublishPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply("maven-publish")
        val extension = project.extensions.create("thirdpartyLibrary", ThirdpartyLibraryExtension::class.java)

        project.afterEvaluate {
            val libraryId = extension.id
                ?: throw GradleException("${project.path} 未声明 thirdpartyLibrary { id = \"...\" }")
            val backendRoot = project.rootProject.projectDir.parentFile
            val manifest = ObjectMapper().readTree(project.rootProject.file("manifest.json"))
            val spec = manifest["libraries"][libraryId]
                ?: throw GradleException("manifest.json 中找不到库 '$libraryId'")

            val groupId = manifest["groupId"].asText()
            val libraryVersion = spec["version"].asText()
            val headers = spec["headers"].map { it.asText() }
            val includeRoot = backendRoot.resolve("thirdparty/include")

            val targets = mutableListOf<Pair<String, List<String>>>()
            val targetIterator = spec["libs"].fields()
            while (targetIterator.hasNext()) {
                val (target, archivesNode) = targetIterator.next()
                targets += target to archivesNode.map { it.asText() }
            }

            val publishing = project.extensions.getByType(PublishingExtension::class.java)
            targets.forEach { (target, archives) ->
                val suffix = target.replaceFirstChar { it.uppercaseChar() }
                val packageTask = project.tasks.register("package$suffix", Zip::class.java) {
                    archiveFileName.set("$libraryId-$target-$libraryVersion.zip")
                    destinationDirectory.set(project.layout.buildDirectory.dir("dist"))
                    from(includeRoot) {
                        headers.forEach { header ->
                            if (header.endsWith(".h")) include(header) else include("$header/**")
                        }
                        into("include")
                    }
                    from(backendRoot.resolve("thirdparty/$target")) {
                        archives.forEach { include(it) }
                        into("lib")
                    }
                }

                publishing.publications.create("maven$suffix", MavenPublication::class.java) {
                    this.groupId = groupId
                    artifactId = "$libraryId-$target"
                    version = libraryVersion
                    artifact(packageTask)
                }
            }

            val properties = Properties().apply {
                val file = backendRoot.resolve("local.properties")
                if (file.isFile) file.inputStream().use { load(it) }
            }

            fun configValue(key: String): String? =
                (project.findProperty(key) as String?) ?: properties.getProperty(key)

            val repoUrl = configValue("lolosia.thirdparty.repo").orEmpty()
            val repoUsername = configValue("lolosia.thirdparty.username").orEmpty()
            val repoPassword = configValue("lolosia.thirdparty.password").orEmpty()
            publishing.repositories {
                if (repoUrl.isNotBlank()) {
                    maven {
                        url = project.uri(repoUrl)
                        isAllowInsecureProtocol = true
                        if (repoUsername.isNotBlank() || repoPassword.isNotBlank()) {
                            credentials {
                                username = repoUsername
                                password = repoPassword
                            }
                        }
                    }
                }
            }

            val scriptName = extension.buildScript
            val script = scriptName?.let { project.file(it) }
            if (script != null && script.isFile) {
                project.tasks.register("buildFromSource", Exec::class.java) {
                    group = "build"
                    description = "从源码编译 $libraryId"
                    workingDir = project.projectDir
                    doFirst {
                        if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
                            throw GradleException("源码编译脚本目前只支持 Windows：$script")
                        }
                    }
                    commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
                }
            }
        }
    }
}
