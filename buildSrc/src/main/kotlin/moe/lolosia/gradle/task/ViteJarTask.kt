package moe.lolosia.gradle.task

import moe.lolosia.gradle.extension.PlatformConfig
import moe.lolosia.gradle.util.getGitInfo
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Jar
import org.gradle.process.ExecOperations
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Vite 构建任务
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 11:46:01
 */
abstract class ViteJarTask : Jar() {

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @Input
    @Optional
    var platforms: Map<String, PlatformConfig> = emptyMap()

    init {
        group = "build"
        val proj = project.rootProject
        archiveFileName.set("${proj.name}-${proj.version}-vite.jar")
        destinationDirectory.set(project.layout.buildDirectory.dir("libs"))
    }

    @TaskAction
    fun buildVite() {
        val viteBuildDir = project.layout.buildDirectory.dir("tmp/viteJar").get().asFile
        project.delete(viteBuildDir)

        platforms.forEach { (platformName, config) ->
            if (config.dir == null) {
                logger.error("vite前端${platformName}没有配置工程文件夹")
                return@forEach
            }
            val path = project.file(config.dir!!)

            val vite = if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                path.resolve("node_modules/.bin/vite.CMD")
            } else {
                path.resolve("node_modules/.bin/vite")
            }

            if (!vite.exists()) {
                logger.error("vite前端${platformName}没有配置vite可执行文件或未进行npm安装操作")
                return@forEach
            }

            execOperations.exec {
                workingDir = path
                commandLine(vite.toString(), "build", "--mode", "build")
                environment("VITE_APP_BASE_MODE", "local")
                environment("VITE_BUILD_TIMESTAMP", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()))
                environment("VITE_PROJECT_VERSION", project.rootProject.version.toString())
                getGitInfo(project)?.let {
                    environment("VITE_GIT_COMMIT", it.shortHash)
                    environment("VITE_BUILD_DISPLAY_NAME", it.branchName)
                }
            }

            project.copy {
                from(path.resolve("dist"))
                into(viteBuildDir.resolve("static/${config.contextPath}"))
            }
        }

        from(viteBuildDir)
        manifest {
            attributes(
                mapOf(
                    "Implementation-Version" to project.rootProject.version,
                    "Implementation-Title" to project.rootProject.name
                )
            )
        }

        super.copy()
    }
}