package moe.lolosia.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * 生成 DI 注册函数任务
 *
 * 扫描 [sourceRoot] 下标注了 `@Service` / `@Component`
 * （`moe.lolosia.web.annotation.Service/Component`）的类，
 * 生成一个 `Module` 扩展函数（默认名 `diServices`），批量注册
 * `single<T> { T() }`，省去在 appModule 里手动逐个声明。
 *
 * 用法（见 [moe.lolosia.web.config.web.appModule]）：
 * ```kotlin
 * val appModule = module {
 *     single<JsonMapper> { JsonMapper() }  // 非 @Service 的基础设施
 *     diServices()                          // @Service/@Component 自动注册
 * }
 * ```
 *
 * 要求：被标注的类必须无参构造，依赖用字段注入（`by inject()`）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 16:18:12
 */
open class GenerateDiModuleTask : DefaultTask() {

    @get:Input
    @get:Optional
    var generatedPackageName: String = "moe.lolosia.web.generated"

    @get:Input
    @get:Optional
    var functionName: String = "diServices"

    @get:Input
    var annotationNames: List<String> = listOf("Service", "Component")

    @get:InputDirectory
    @get:Optional
    lateinit var sourceRoot: File

    @get:OutputDirectory
    val outputDir = project.layout.buildDirectory.dir("generated/source/di/main")

    init {
        group = "other"
        description = "Scans @Service/@Component classes and generates a Koin Module registration function"
    }

    @TaskAction
    fun generate() {
        val root = sourceRoot
        if (!root.isDirectory) return

        val classes = scanClasses(root)
        if (classes.isEmpty()) return

        val packagePath = generatedPackageName.replace('.', '/')
        val dir = File(outputDir.get().asFile, packagePath)
        dir.mkdirs()
        File(dir, "GeneratedDiModule.kt").writeText(buildContent(classes))
    }

    // ===== 扫描 =====

    private val packageRegex = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

    /** 匹配简单名或全限定名的 @Service/@Component */
    private val annotationRegex =
        Regex("""@(?:moe\.lolosia\.web\.annotation\.)?(${annotationNames.joinToString("|")})\b""")

    private val classRegex = Regex("""\bclass\s+([A-Za-z_]\w*)""")

    private val badModifiers = listOf("data ", "sealed ", "enum ", "abstract ", "annotation ")

    private fun scanClasses(root: File): List<String> {
        val found = mutableSetOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val stripped = stripComments(file.readText())
            val packageName = packageRegex.find(stripped)?.groupValues?.get(1) ?: return@forEach
            for (m in annotationRegex.findAll(stripped)) {
                val tail = stripped.substring(m.range.last + 1)
                val cls = classRegex.find(tail) ?: continue
                val before = cls.value.substringBefore("class")
                if (badModifiers.any { before.contains(it) }) continue
                found += "$packageName.${cls.groupValues[1]}"
            }
        }
        return found.sorted()
    }

    /** 剥离注释（KDoc/块注释/行注释），避免文档里的 @Service 字样被误扫 */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    // ===== 生成 =====

    private fun buildContent(classes: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("// 本文件由 moe.lolosia.gradle.task.GenerateDiModuleTask 自动生成，请勿手动修改。")
        sb.appendLine("// 新增服务时：类上加 @Service（或 @Component）注解即可被自动注册，无需改动本文件。")
        sb.appendLine()
        sb.appendLine("package $generatedPackageName")
        sb.appendLine()
        classes.forEach { sb.appendLine("import $it") }
        sb.appendLine("import org.koin.core.module.Module")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * 扫描 @Service/@Component 自动生成的 DI 注册。")
        sb.appendLine(" * 用法：在 `module { }` 内调用 `$functionName()` 批量注册所有被标注的服务。")
        sb.appendLine(" */")
        sb.appendLine("fun Module.$functionName() {")
        classes.forEach { clazz ->
            val simple = clazz.substringAfterLast('.')
            sb.appendLine("    single<$simple> { $simple() }")
        }
        sb.appendLine("}")
        return sb.toString()
    }
}
