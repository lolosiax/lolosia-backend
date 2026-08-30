package moe.lolosia.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * 生成手动路由注册函数任务
 *
 * 扫描 [sourceRoot] 下所有标注了 `@Route`（`moe.lolosia.web.annotation.Route`）的
 * **object 成员函数**（函数类型 `Function<Routing, Unit>`，形如 `fun xxx(routing: Routing)`
 * 或 `fun Routing.xxx()`），生成一个 `Routing.() -> Unit` 扩展函数（默认名 `manualRoutes`），
 * 内部依次调用 `ObjectName.xxx(this)`，省去在 RoutingConfig 里手动逐条写 `routing { }`。
 *
 * 用法（见 [moe.lolosia.web.config.web.configureRouting]）：
 * ```kotlin
 * routing {
 *     generateRoutes()   // ktorfitx 注解路由
 *     manualRoutes()     // @Route 手动路由
 * }
 * ```
 *
 * 要求：被标注的函数必须是某个 `object` 的成员（生成代码以 `对象名.函数名(this)` 调用），
 * 函数仅负责向 `io.ktor.server.routing.Routing` 注册路由。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 18:10:59
 */
open class GenerateRouteModuleTask : DefaultTask() {

    @get:Input
    @get:Optional
    var generatedPackageName: String = "moe.lolosia.web.generated"

    @get:Input
    @get:Optional
    var functionName: String = "manualRoutes"

    @get:InputDirectory
    @get:Optional
    lateinit var sourceRoot: File

    @get:OutputDirectory
    val outputDir = project.layout.buildDirectory.dir("generated/source/route/main")

    init {
        group = "other"
        description = "Scans @Route-annotated object member functions and generates a Routing.() -> Unit registration function"
    }

    @TaskAction
    fun generate() {
        val root = sourceRoot
        if (!root.isDirectory) return

        val routes = scanRoutes(root)
        val packagePath = generatedPackageName.replace('.', '/')
        val dir = File(outputDir.get().asFile, packagePath)
        dir.mkdirs()
        File(dir, "GeneratedRouteModule.kt").writeText(buildContent(routes))
    }

    // ===== 扫描 =====

    /** (包名, 对象简单名, 函数名) */
    private data class RouteRef(val packageName: String, val objectName: String, val functionName: String)

    private val packageRegex = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val objectRegex = Regex("""\bobject\s+([A-Za-z_]\w*)""")
    private val funRegex = Regex("""\bfun\s+([A-Za-z_]\w*)""")
    private val routeAnnotationRegex = Regex("""@(?:moe\.lolosia\.web\.annotation\.)?Route\b""")

    private fun scanRoutes(root: File): List<RouteRef> {
        val found = sortedSetOf(
            compareBy<RouteRef> { it.packageName }.thenBy { it.objectName }.thenBy { it.functionName }
        )
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = stripCommentsAndStrings(file.readText())
            val packageName = packageRegex.find(text)?.groupValues?.get(1) ?: return@forEach
            found += scanFile(text, packageName)
        }
        return found.toList()
    }

    /**
     * 单文件扫描：按字符追踪大括号深度，记录每层深度对应的 `object` 名称，
     * 遇到 `@Route` 时取其所在深度的 object 与紧跟其后的函数名。
     */
    private fun scanFile(text: String, packageName: String): List<RouteRef> {
        val routes = mutableListOf<RouteRef>()
        val objectAtDepth = mutableMapOf<Int, String>()
        var pendingObject: String? = null
        var depth = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '{' -> {
                    depth++
                    pendingObject?.let {
                        objectAtDepth[depth] = it
                        pendingObject = null
                    }
                }
                c == '}' -> if (depth > 0) depth--
                c == 'o' && text.startsWith("object", i) -> {
                    val m = objectRegex.find(text, i)
                    if (m != null) {
                        pendingObject = m.groupValues[1]
                        i = m.range.last
                    }
                }
                c == '@' -> {
                    // 必须从当前位置精确匹配（matchAt），不能用 find 前向扫描，
                    // 否则前面的 @Controller/@GET 等注解会误命中后面的 @Route 并跳过它
                    val m = routeAnnotationRegex.matchAt(text, i)
                    if (m != null) {
                        val obj = objectAtDepth[depth]
                        val fn = funRegex.find(text, m.range.last + 1)?.groupValues?.get(1)
                        if (obj != null && fn != null) {
                            routes += RouteRef(packageName, obj, fn)
                        }
                        i = m.range.last
                    }
                }
            }
            i++
        }
        return routes
    }

    /** 剥离注释与字符串字面量，避免注释/路径模板里的 `{`、`//` 干扰括号计数与关键字匹配 */
    private fun stripCommentsAndStrings(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val j = text.indexOf('\n', i)
                    i = if (j < 0) n else j
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val j = text.indexOf("*/", i + 2)
                    i = if (j < 0) n else j + 2
                }
                c == '"' -> {
                    if (text.startsWith("\"\"\"", i)) {
                        val j = text.indexOf("\"\"\"", i + 3)
                        i = if (j < 0) n else j + 3
                    } else {
                        var j = i + 1
                        while (j < n) {
                            when (text[j]) {
                                '\\' -> j += 2
                                '"' -> { j++; break }
                                else -> j++
                            }
                        }
                        i = j
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    // ===== 生成 =====

    private fun buildContent(routes: List<RouteRef>): String {
        val sb = StringBuilder()
        sb.appendLine("// 本文件由 moe.lolosia.gradle.task.GenerateRouteModuleTask 自动生成，请勿手动修改。")
        sb.appendLine("// 新增手动路由时：在任意 object 的成员函数上加 @Route 注解（函数类型 Function<Routing, Unit>）即可自动注册，无需改动本文件。")
        sb.appendLine()
        sb.appendLine("package $generatedPackageName")
        sb.appendLine()
        sb.appendLine("import io.ktor.server.routing.Routing")
        routes.map { "${it.packageName}.${it.objectName}" }.distinct().sorted().forEach {
            sb.appendLine("import $it")
        }
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * 扫描 @Route 注解自动生成的手动路由注册。")
        sb.appendLine(" * 用法：在 `routing { }` 内调用 `$functionName()` 注册所有被标注的路由函数。")
        sb.appendLine(" */")
        sb.appendLine("fun Routing.$functionName() {")
        routes.forEach { sb.appendLine("    ${it.objectName}.${it.functionName}(this)") }
        sb.appendLine("}")
        return sb.toString()
    }
}
