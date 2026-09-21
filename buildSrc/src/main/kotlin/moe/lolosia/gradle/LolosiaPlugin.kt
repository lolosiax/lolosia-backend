package moe.lolosia.gradle

import moe.lolosia.gradle.extension.BinaryConfig
import moe.lolosia.gradle.extension.LolosiaExtension
import moe.lolosia.gradle.extension.ServerConfig
import moe.lolosia.gradle.extension.ViteJarExtension
import moe.lolosia.gradle.task.ARTIFACT_BINARY
import moe.lolosia.gradle.task.ARTIFACT_JAR
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
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

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
            // viteJar 必须先注册：classpath 流水线要消费它的产物
            configureViteJar(project, lolosiaExtension.viteJar)
            configureNativeClasspath(lolosiaExtension)
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

    /**
     * 决定这台服务器部署什么：
     * - server 配了 `abi` → Kotlin/Native 可执行文件（ABI 映射到 `linuxArm64` / `linuxX64`）；
     * - 否则 → JVM 的 `bootJar`。
     *
     * ABI 放在 server 上而不是项目上：同一个项目完全可能既部署到 arm64 开发板、又部署到 x64 云主机。
     */
    private fun resolveDeployArtifact(
        project: Project,
        lolosiaExt: LolosiaExtension,
        serverName: String,
        server: ServerConfig,
    ): DeployArtifact? {
        val abi = server.abi
        if (!abi.isNullOrBlank()) {
            return resolveNativeArtifact(project, lolosiaExt.deploy.binaryConfig, serverName, abi, server)
        }

        val bootJar = project.tasks.findByName("bootJar")
        if (bootJar == null) {
            project.logger.info(
                "lolosia deploy：server '$serverName' 未配置 abi，工程里也没有 bootJar，本次跳过"
            )
            return null
        }
        return DeployArtifact(kind = ARTIFACT_JAR, task = bootJar, files = project.files(bootJar))
    }

    private fun resolveNativeArtifact(
        project: Project,
        binary: BinaryConfig?,
        serverName: String,
        abi: String,
        server: ServerConfig,
    ): DeployArtifact? {
        val target = kotlinNativeTargetOf(abi)
        if (target == null) {
            project.logger.warn(
                "lolosia deploy：server '$serverName' 的 abi='$abi' 无法识别（支持 arm64 / x64），本次跳过"
            )
            return null
        }

        val buildType = (binary?.buildType ?: "release").lowercase().replaceFirstChar { it.uppercaseChar() }
        val taskName = "link${buildType}Executable${target.replaceFirstChar { it.uppercaseChar() }}"
        val linkTask = project.tasks.findByName(taskName)
        if (linkTask == null) {
            project.logger.warn(
                "lolosia deploy：server '$serverName' 需要 $taskName，但工程里没有这个任务" +
                    "（请在 kotlin { } 中声明目标 $target），本次跳过"
            )
            return null
        }
        if (linkTask !is KotlinNativeLink) {
            project.logger.warn("lolosia deploy：$taskName 不是 KotlinNativeLink，无法定位可执行文件，本次跳过")
            return null
        }

        return DeployArtifact(
            kind = ARTIFACT_BINARY,
            task = linkTask,
            // 必须用 outputFile：link 任务的 outputs 声明的是「输出目录」，
            // 直接传目录会让 hidetake 的 SFTP put 走递归分支并 mkdir 到远端，报 Failed SFTP MKDIR
            files = project.files(linkTask.outputFile),
            remoteName = binary?.name ?: project.rootProject.name,
            unitName = binary?.unitName,
            launchArgs = server.args.toList(),
        )
    }

    /** ABI（platforms.json 里写的）→ Kotlin/Native 目标名。 */
    private fun kotlinNativeTargetOf(abi: String): String? = when (abi.lowercase()) {
        "arm64", "aarch64" -> "linuxArm64"
        "x64", "amd64", "x86_64" -> "linuxX64"
        else -> null
    }

    private fun configureDeploy(project: Project, lolosiaExt: LolosiaExtension) {
        val deployExt = lolosiaExt.deploy
        if (deployExt.servers.isEmpty()) return

        val deployModes = setOf("docker", "systemd")

        val dockerImageMap = lolosiaExt.docker.images.mapValues {
            DockerImageInfo(mirror = it.value.mirror ?: "", autoUntagMirror = it.value.autoUntagMirror)
        }

        // 记录真正注册了部署任务的服务器：产物解析失败的会被跳过，聚合任务不能指向它们
        val registered = mutableSetOf<String>()

        deployExt.servers.forEach { (name, serverConfig) ->
            if (serverConfig.mode !in deployModes) {
                throw GradleException(
                    "deploy server '$name' 的 mode '${serverConfig.mode}' 不合法，仅支持：${deployModes.joinToString(" / ")}"
                )
            }

            // 产物按服务器逐个解析：各机器的 ABI 可以不同
            val artifact = resolveDeployArtifact(project, lolosiaExt, name, serverConfig) ?: return@forEach

            val taskName = "deploy${name.replaceFirstChar { it.uppercase() }}"
            project.tasks.register(taskName, DeployTask::class.java) {
                group = "deploy"
                dependsOn(artifact.task)
                artifactKind = artifact.kind
                this.artifact = artifact.files
                remoteArtifactName = artifact.remoteName
                unitFileName = artifact.unitName
                launchArgs = artifact.launchArgs
                serverName = serverConfig.name
                serverHost = serverConfig.host
                serverPort = serverConfig.port
                serverUser = serverConfig.user
                serverPassword = serverConfig.password
                serverPwd = serverConfig.pwd
                serverMode = serverConfig.mode
                dockerImages = dockerImageMap
            }
            registered += name
        }

        deployExt.activate?.let { activeName ->
            if (activeName !in registered) return@let
            val activeTaskName = "deploy${activeName.replaceFirstChar { it.uppercase() }}"
            project.tasks.register("deploy") {
                group = "deploy"
                description = "Deploys to the active server '$activeName'"
                dependsOn(activeTaskName)
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
                    // 用 fileTree 而不是 inputs.dir/inputs.file：
                    // 这些路径可能不存在（typings/public 未必存在，交叉编译环境下整个前端目录都可能不在），
                    // 而 Gradle 9 对缺失的 inputs.dir/inputs.file 会直接判定校验失败。
                    cache.dirs.forEach { inputs.files(project.fileTree(baseDir.resolve(it))) }
                    cache.files.forEach { inputs.files(project.fileTree(baseDir.resolve(it))) }
                    cache.filePatterns.forEach { pattern ->
                        inputs.files(project.fileTree(baseDir) { include(pattern) })
                    }
                }
            }

            outputs.cacheIf { true }
        }

        // vite 静态资源并入主产物：Spring Boot 项目并入 bootJar（BOOT-INF/classes 布局）；
        // 其他 JVM 项目并入默认 jar（classpath 根，Ktor 的 staticResources 直接读取，配合 application
        // 插件的 installDist 部署，避免 fat jar 的 Java 9+ 模块问题）。
        // 非 JVM 打包工程（Kotlin/Native）没有 jar，产物由 classpath 资源流水线消费，
        // 见 [configureNativeClasspath] / [ClasspathExtension.includeViteJar]。
        val targetTask = project.tasks.findByName("bootJar") as? Jar
            ?: project.tasks.findByName("jar") as? Jar
            ?: return
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

        // 每个前端平台生成一个 VITE_<平台名> 常量（值即 contextPath），供服务端挂载静态资源时引用，
        // 避免把 "/home" 之类的路径写死在代码里。
        // JVM 工程挂到 java 的 main sourceSet（配合 kapt）；KMP 工程挂到 commonMain。
        val kaptKotlin = project.tasks.findByName("kaptKotlin")

        val contextPaths = vitePlatforms.mapValues { it.value.contextPath }

        val generateTask = project.tasks.register("generateConstants", GenerateConstantsTask::class.java) {
            packageName = lolosiaExt.packageName ?: "moe.lolosia"
            projectName = lolosiaExt.projectName ?: "未命名的应用程序平台"
            this.platforms = contextPaths
            // @file:JvmName 只有 JVM 目标认识，common/Native 源集里会 "Unresolved reference 'JvmName'"
            jvmTarget = kaptKotlin != null
        }

        val outputDir = generateTask.map { it.outputDir }

        if (kaptKotlin != null) {
            kaptKotlin.dependsOn(generateTask)
            project.extensions.getByType<SourceSetContainer>().named("main") {
                java.srcDir(outputDir)
            }
        } else {
            val kotlinExt = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
            if (kotlinExt == null) {
                project.logger.warn("lolosia generateConstants：既没有 kapt 也不是 KMP 工程，跳过常量生成")
                return
            }
            kotlinExt.sourceSets.getByName("commonMain").kotlin.srcDir(outputDir)
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

/**
 * 一次部署的产物：形态 + 产出它的任务 + 文件集合。
 * [remoteName] / [unitName] / [launchArgs] 仅原生形态使用。
 */
private class DeployArtifact(
    val kind: String,
    val task: Task,
    val files: FileCollection,
    val remoteName: String? = null,
    val unitName: String? = null,
    val launchArgs: List<String> = emptyList(),
)