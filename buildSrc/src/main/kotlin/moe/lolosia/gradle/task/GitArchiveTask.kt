package moe.lolosia.gradle.task

import moe.lolosia.gradle.extension.PlatformConfig
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Git压缩文档生成任务
 * @author 洛洛希雅Lolosia
 * @since 2026-05-15 14:18:36
 */
abstract class GitArchiveTask : DefaultTask() {

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @Input
    @Optional
    var platforms: Map<String, PlatformConfig> = emptyMap()

    @OutputFile
    val archiveFile: RegularFileProperty = project.objects.fileProperty()

    init {
        group = "build"
        description = "将当前项目及前端项目的 Git 源码导出为归档文件"
        val proj = project.rootProject
        archiveFile.set(project.layout.buildDirectory.file("libs/${proj.name}-${proj.version}-src.zip"))
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun archive() {
        val tempDir = project.layout.buildDirectory.dir("tmp/gitArchive").get().asFile
        project.delete(tempDir)
        tempDir.mkdirs()

        exportGitRepo(project.rootDir, tempDir, project.rootProject.name)

        platforms.forEach { (name, config) ->
            if (config.dir != null) {
                val frontendDir = project.file(config.dir!!)
                if (frontendDir.exists()) {
                    exportGitRepo(frontendDir, tempDir, "${project.rootProject.name}-$name")
                }
            }
        }

        val outputFile = archiveFile.get().asFile
        outputFile.parentFile.mkdirs()

        ZipOutputStream(outputFile.outputStream()).use { zos ->
            tempDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(tempDir).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        project.delete(tempDir)
    }

    private fun exportGitRepo(repoDir: File, destDir: File, folderName: String) {
        val tempTarFile = destDir.resolve("${folderName}_temp.tar")

        execOperations.exec {
            workingDir = repoDir
            commandLine("git", "archive", "--format=tar", "-o", tempTarFile.absolutePath, "HEAD")
        }

        val targetDir = destDir.resolve(folderName)
        targetDir.mkdirs()

        project.copy {
            from(project.tarTree(tempTarFile))
            into(targetDir)
        }

        tempTarFile.delete()
    }
}