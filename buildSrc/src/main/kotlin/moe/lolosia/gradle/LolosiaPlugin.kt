package moe.lolosia.gradle

import moe.lolosia.gradle.extension.LolosiaExtension
import moe.lolosia.gradle.extension.ViteJarExtension
import moe.lolosia.gradle.task.DeployTask
import moe.lolosia.gradle.task.DockerImageInfo
import moe.lolosia.gradle.task.GenerateConstantsTask
import moe.lolosia.gradle.task.GenerateDiModuleTask
import moe.lolosia.gradle.task.GenerateRouteModuleTask
import moe.lolosia.gradle.task.GitArchiveTask
import moe.lolosia.gradle.task.ViteJarTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.ide.idea.model.IdeaModel

/**
 * Lolosia Spring Boot 构建与部署插件
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 10:47:05
 */
class LolosiaPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.apply("org.hidetake.ssh")

        val lolosiaExtension = project.extensions.create("lolosia", LolosiaExtension::class.java)

        project.afterEvaluate {
            configureGitArchive(project, lolosiaExtension)
            configureDeploy(project, lolosiaExtension)
            configureViteJar(project, lolosiaExtension.viteJar)
            configureGenerateConstants(project, lolosiaExtension)
            configureGenerateDiModule(project)
            configureGenerateRouteModule(project)
        }
    }

    private fun configureGitArchive(project: Project, lolosiaExt: LolosiaExtension) {
        project.tasks.register("gitArchive", GitArchiveTask::class.java) {
            platforms = lolosiaExt.viteJar.platforms
        }
    }

    private fun configureDeploy(project: Project, lolosiaExt: LolosiaExtension) {
        val deployExt = lolosiaExt.deploy
        if (deployExt.servers.isEmpty()) return

        val bootJarTask = project.tasks.named("bootJar")

        val deployModes = setOf("docker", "systemd")

        val dockerImageMap = lolosiaExt.docker.images.mapValues {
            DockerImageInfo(mirror = it.value.mirror ?: "", autoUntagMirror = it.value.autoUntagMirror)
        }

        deployExt.servers.forEach { (name, serverConfig) ->
            if (serverConfig.mode !in deployModes) {
                throw GradleException(
                    "deploy server '$name' 的 mode '${serverConfig.mode}' 不合法，仅支持：${deployModes.joinToString(" / ")}"
                )
            }

            val taskName = "deploy${name.replaceFirstChar { it.uppercase() }}"
            project.tasks.register(taskName, DeployTask::class.java) {
                group = "deploy"
                dependsOn(bootJarTask)
                serverName = serverConfig.name
                serverHost = serverConfig.host
                serverPort = serverConfig.port
                serverUser = serverConfig.user
                serverPassword = serverConfig.password
                serverPwd = serverConfig.pwd
                serverMode = serverConfig.mode
                dockerImages = dockerImageMap
            }
        }

        deployExt.activate?.let { activeName ->
            val activeTaskName = "deploy${activeName.replaceFirstChar { it.uppercase() }}"
            if (deployExt.servers.containsKey(activeName)) {
                project.tasks.register("deploy") {
                    group = "deploy"
                    description = "Deploys to the active server '$activeName'"
                    dependsOn(activeTaskName)
                }
            }
        }
    }

    private fun configureViteJar(project: Project, viteJarExt: ViteJarExtension) {
        if (viteJarExt.platforms.isEmpty()) return

        val viteJarTask = project.tasks.register("viteJar", ViteJarTask::class.java) {
            platforms = viteJarExt.platforms

            viteJarExt.platforms.forEach { (_, config) ->
                config.dir?.let { dirPath ->
                    val baseDir = project.file(dirPath)
                    val cache = config.cache
                    cache.dirs.forEach { inputs.dir(baseDir.resolve(it)) }
                    cache.files.forEach { inputs.file(baseDir.resolve(it)) }
                    cache.filePatterns.forEach { pattern ->
                        project.fileTree(baseDir) { include(pattern) }.forEach {
                            inputs.file(it)
                        }
                    }
                }
            }

            outputs.cacheIf { true }
        }

        // vite 静态资源并入主产物：Spring Boot 项目并入 bootJar（BOOT-INF/classes 布局）；
        // 其他项目并入默认 jar（classpath 根，Ktor 的 staticResources 直接读取，
        // 配合 application 插件的 installDist 部署，避免 fat jar 的 Java 9+ 模块问题）。
        val targetTask = project.tasks.findByName("bootJar") as? Jar
            ?: project.tasks.named("jar", Jar::class.java).get()
        project.tasks.named(targetTask.name, Jar::class.java) {
            dependsOn(viteJarTask)
            val viteJarFile = viteJarTask.get().archiveFile
            from(project.zipTree(viteJarFile)) {
                exclude("META-INF/MANIFEST.MF")
                if (targetTask.name == "bootJar") {
                    into("BOOT-INF/classes")
                }
            }
        }
    }

    private fun configureGenerateConstants(project: Project, lolosiaExt: LolosiaExtension) {
        val vitePlatforms = lolosiaExt.viteJar.platforms
        if (vitePlatforms.isEmpty()) return

        val contextPaths = vitePlatforms.mapValues { it.value.contextPath }

        val generateTask = project.tasks.register("generateConstants", GenerateConstantsTask::class.java) {
            packageName = lolosiaExt.packageName ?: "moe.lolosia"
            projectName = lolosiaExt.projectName ?: "未命名的应用程序平台"
            this.platforms = contextPaths
        }

        project.tasks.named("kaptKotlin").configure {
            dependsOn(generateTask)
        }

        project.extensions.getByType<SourceSetContainer>().named("main") {
            java.srcDir(generateTask.map { it.outputDir })
        }

        project.plugins.withId("idea") {
            project.extensions.getByType<IdeaModel>().module {
                generatedSourceDirs.add(generateTask.get().outputDir.get().asFile)
            }
        }
    }

    /**
     * 扫描 @Service/@Component 生成 Koin 批量注册函数（见 [GenerateDiModuleTask]）。
     * 生成目录挂到 main sourceSet，并确保 compileKotlin 之前执行。
     */
    private fun configureGenerateDiModule(project: Project) {
        val sourceRoot = project.layout.projectDirectory.dir("src/main/kotlin").asFile
        if (!sourceRoot.isDirectory) return

        val generateTask = project.tasks.register("generateDiModule", GenerateDiModuleTask::class.java) {
            this.sourceRoot = sourceRoot
        }

        project.tasks.matching { it.name == "compileKotlin" }.configureEach {
            dependsOn(generateTask)
        }

        project.extensions.getByType<SourceSetContainer>().named("main") {
            java.srcDir(generateTask.map { it.outputDir })
        }

        project.plugins.withId("idea") {
            project.extensions.getByType<IdeaModel>().module {
                generatedSourceDirs.add(generateTask.get().outputDir.get().asFile)
            }
        }
    }

    /**
     * 扫描 @Route 生成手动路由注册函数（见 [GenerateRouteModuleTask]）。
     * 生成目录挂到 main sourceSet，并确保 compileKotlin 之前执行。
     */
    private fun configureGenerateRouteModule(project: Project) {
        val sourceRoot = project.layout.projectDirectory.dir("src/main/kotlin").asFile
        if (!sourceRoot.isDirectory) return

        val generateTask = project.tasks.register("generateRouteModule", GenerateRouteModuleTask::class.java) {
            this.sourceRoot = sourceRoot
        }

        project.tasks.matching { it.name == "compileKotlin" }.configureEach {
            dependsOn(generateTask)
        }

        project.extensions.getByType<SourceSetContainer>().named("main") {
            java.srcDir(generateTask.map { it.outputDir })
        }

        project.plugins.withId("idea") {
            project.extensions.getByType<IdeaModel>().module {
                generatedSourceDirs.add(generateTask.get().outputDir.get().asFile)
            }
        }
    }
}