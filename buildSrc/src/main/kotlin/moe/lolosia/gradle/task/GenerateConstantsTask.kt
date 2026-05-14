package moe.lolosia.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Locale

/**
 * 生成常量任务
 * @author 洛洛希雅Lolosia
 * @since 2026-05-14 13:53:26
 */
open class GenerateConstantsTask : DefaultTask() {

    @Input
    @Optional
    var packageName: String = "moe.lolosia"

    @Input
    @Optional
    var projectName: String = "未命名的应用程序平台"

    @Input
    @Optional
    var platforms: Map<String, String?> = emptyMap()

    @OutputDirectory
    val outputDir = project.layout.buildDirectory.dir("generated/source/vite/main")

    init {
        group = "other"
        description = "Generates Kotlin constants for Vite platform context paths"
    }

    @TaskAction
    fun generate() {
        val output = outputDir.get().asFile
        output.deleteRecursively()

        if (platforms.isEmpty()) return

        val packagePath = packageName.replace('.', '/')
        val packageDir = File(output, packagePath)
        packageDir.mkdirs()

        val constantsFile = File(packageDir, "constants.kt")
        constantsFile.writeText(buildConstantsContent())
    }

    private fun buildConstantsContent(): String {
        val sb = StringBuilder()
        sb.appendLine("@file:JvmName(\"Constants\")")
        sb.appendLine()
        sb.appendLine("package $packageName")
        sb.appendLine()

        var p = projectName
        if (p.contains("\"")) p = p.replace("\"", "\\\"")
        if (p.contains("\n")) p = p.replace("\n", "\\\n")
        sb.appendLine("const val PROJECT_NAME = \"$p\"")

        platforms.forEach { (name, contextPath) ->
            val constantName = buildConstantName(name)
            val path = contextPath ?: "/"
            sb.appendLine("const val $constantName = \"$path\"")
        }

        return sb.toString()
    }

    private fun buildConstantName(name: String): String {
        val constantName = name.uppercase(Locale.getDefault())
            .replace("-", "_")
            .replace(".", "_")
        return "VITE_${constantName}"
    }
}