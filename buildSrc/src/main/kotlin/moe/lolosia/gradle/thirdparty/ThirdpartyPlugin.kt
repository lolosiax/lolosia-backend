package moe.lolosia.gradle.thirdparty

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/**
 * 第三方原生库插件。
 *
 * 在根工程应用后：
 * - 读取 [ThirdpartySettings]（默认关闭）；
 * - 按 `thirdparty/manifest.json` 为每个库 × 目标建一个独立依赖配置与 `thirdpartySync<库><目标>` 任务；
 * - 启用仓库时从 `moe.lolosia.thirdparty:<库>-<目标>:<版本>@zip` 拉取并解压；
 * - 未启用仓库（例如没有 `local.properties`）时，自动先跑源码构建脚本，再回退到
 *   `build/thirdparty/out/include` 与 `build/thirdparty/out/<目标>`；
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
        extension.repoUsername = settings.username
        extension.repoPassword = settings.password

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
        val codecLibraries = setOf("zlib", "png", "webp", "jpeg", "gif", "msgpack")

        // 没有工件仓库（例如 local.properties 不存在）时，源码构建是唯一来源。
        // 这些任务只在回退模式注册，配置了仓库的构建不会误触发 CMake/编译。
        val sourceBuildTasks = mutableMapOf<String, TaskProvider<Exec>>()
        if (!settings.enabled) {
            val root = project.rootProject
            val outRoot = root.layout.buildDirectory.dir("thirdparty/out")

            fun sourceBuild(
                name: String,
                script: String,
                outputFiles: List<java.io.File>,
                outputDirectories: List<java.io.File> = emptyList(),
                dependencies: List<String> = emptyList(),
            ): TaskProvider<Exec> = project.tasks.register(name, Exec::class.java) {
                group = "thirdparty"
                description = "从上游源码编译 $name"
                workingDir = root.projectDir
                inputs.file(root.file(script))
                inputs.file(root.file("thirdparty/gradle/toolchain.ps1"))
                inputs.file(root.file("thirdparty/manifest.json"))
                outputs.files(outputFiles)
                outputs.dirs(outputDirectories)
                if (dependencies.isNotEmpty()) dependsOn(dependencies)
                doFirst {
                    if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
                        throw GradleException("未配置工件仓库，且源码构建脚本目前只支持 Windows：$script")
                    }
                }
                commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", script)
                // 仓库地址与账号不入库：从 local.properties / -P 读入后经环境变量交给脚本
                if (settings.repoUrl.isNotBlank()) {
                    environment("LOLOSIA_THIRDPARTY_REPO", settings.repoUrl)
                    environment("LOLOSIA_THIRDPARTY_USERNAME", settings.username)
                    environment("LOLOSIA_THIRDPARTY_PASSWORD", settings.password)
                }
                // 源码下载可能被网络阻断；已有本地产物时允许降级，避免整个构建失败。
                // 若产物也缺失，仍然抛出明确错误，防止静默产出坏东西。
                isIgnoreExitValue = true
                doLast {
                    val exit = executionResult.get().exitValue
                    if (exit != 0) {
                        val missing = (outputFiles + outputDirectories).filterNot { it.exists() }
                        if (missing.isEmpty()) {
                            logger.warn("源码编译 $name 失败（exit=$exit），回退到已有第三方产物")
                        } else {
                            throw GradleException(
                                "源码编译 $name 失败（exit=$exit），且缺少本地回退产物：" +
                                    missing.joinToString { it.absolutePath },
                            )
                        }
                    }
                }
            }

            val codecFiles = mutableListOf<java.io.File>()
            val codecDirectories = mutableListOf<java.io.File>()
            codecLibraries.forEach { library ->
                val spec = manifest["libraries"][library] ?: return@forEach
                spec["headers"].forEach { header ->
                    val path = outRoot.get().dir("include/${header.asText()}").asFile
                    if (header.asText().endsWith(".h")) codecFiles += path else codecDirectories += path
                }
                spec["libs"].fields().forEach { (target, archives) ->
                    archives.forEach { archive ->
                        codecFiles += outRoot.get().dir(target).file(archive.asText()).asFile
                    }
                }
            }
            val codecs = sourceBuild(
                "thirdpartyBuildCodecs",
                "thirdparty/gradle/build-codecs.ps1",
                outputFiles = codecFiles,
                outputDirectories = codecDirectories,
            )
            codecs.configure { inputs.file(root.file("gradle/libs.versions.toml")) }

            val sdl3 = sourceBuild(
                "thirdpartyBuildSdl3",
                "thirdparty/sdl3/build.ps1",
                outputFiles = listOf(outRoot.get().dir("mingwX64").file("libSDL3.a").asFile),
                outputDirectories = listOf(outRoot.get().dir("include/SDL3").asFile),
            )
            val freetype = sourceBuild(
                "thirdpartyBuildFreetype",
                "thirdparty/freetype/build.ps1",
                outputFiles = listOf(outRoot.get().dir("mingwX64").file("libfreetype.a").asFile),
                outputDirectories = listOf(outRoot.get().dir("include/freetype2").asFile),
            )
            val rmlui = sourceBuild(
                "thirdpartyBuildRmlui",
                "thirdparty/rmlui/build.ps1",
                outputFiles = listOf(
                    outRoot.get().dir("mingwX64").file("librmlui.a").asFile,
                    outRoot.get().dir("mingwX64").file("librmlui_debugger.a").asFile,
                ),
                outputDirectories = listOf(outRoot.get().dir("include/RmlUi").asFile),
                dependencies = listOf("thirdpartyBuildFreetype"),
            )

            codecLibraries.forEach { sourceBuildTasks[it] = codecs }
            sourceBuildTasks["sdl3"] = sdl3
            sourceBuildTasks["freetype"] = freetype
            sourceBuildTasks["rmlui"] = rmlui

            project.tasks.register("thirdpartyBuild") {
                group = "thirdparty"
                description = "从上游源码编译全部第三方原生库（无工件仓库时的回退路径）"
                dependsOn(codecs, sdl3, freetype, rmlui)
            }
        }

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
                    if (!settings.enabled) {
                        sourceBuildTasks[library]?.let { dependsOn(it) }
                    }
                    if (settings.enabled) {
                        from(project.provider { project.zipTree(configuration.singleFile) })
                    } else {
                        from(project.rootProject.layout.buildDirectory.dir("thirdparty/out/include")) {
                            headers.forEach { header ->
                                if (header.endsWith(".h")) include(header) else include("$header/**")
                            }
                            into("include")
                        }
                        from(project.rootProject.layout.buildDirectory.dir("thirdparty/out/$target")) {
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
