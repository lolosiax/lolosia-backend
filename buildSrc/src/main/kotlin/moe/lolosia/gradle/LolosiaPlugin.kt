package moe.lolosia.gradle

import moe.lolosia.gradle.extension.DeployExtension
import moe.lolosia.gradle.extension.LolosiaExtension
import moe.lolosia.gradle.extension.ViteJarExtension
import moe.lolosia.gradle.task.DeployTask
import moe.lolosia.gradle.task.GenerateConstantsTask
import moe.lolosia.gradle.task.ViteJarTask
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
            configureDeploy(project, lolosiaExtension.deploy)
            configureViteJar(project, lolosiaExtension.viteJar)
            configureGenerateViteConstants(project, lolosiaExtension)
        }
    }

    private fun configureDeploy(project: Project, deployExt: DeployExtension) {
        if (deployExt.servers.isEmpty()) return

        val bootJarTask = project.tasks.named("bootJar")

        deployExt.servers.forEach { (name, serverConfig) ->
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
        }

        project.tasks.named("bootJar", Jar::class.java) {
            dependsOn(viteJarTask)
            val viteJarFile = viteJarTask.get().archiveFile
            from(project.zipTree(viteJarFile)) {
                exclude("META-INF/MANIFEST.MF")
                into("BOOT-INF/classes")
            }
        }
    }

    private fun configureGenerateViteConstants(project: Project, lolosiaExt: LolosiaExtension) {
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
}