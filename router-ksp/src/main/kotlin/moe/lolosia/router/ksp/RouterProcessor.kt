package moe.lolosia.router.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/**
 * 自研注解式路由的 KSP 处理器。
 *
 * 扫描 `@Controller` object，为其中的 `@GET/@POST/@PUT/@DELETE/@PATCH` 函数生成
 * Ktor `Routing` 注册代码（`generatedRoutes()`）。
 *
 * 设计要点（对应迁移方案决策 9）：
 * - 与 ktorfitx 同名同义，控制器只改 import；
 * - **可注入类型按类型自动注入**（Spring 风格）：形参类型带 `@Injectable`（或继承自带该注解的类型）时无需注解；
 * - `@Path/@Query/@Header/@Form/@FilePart` 的名称可省略，默认取形参名；
 * - 不做通用类型转换、不做嵌套泛型绑定，超出范围请手写路由。
 *
 * @author 洛洛希雅Lolosia
 */
class RouterProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        RouterProcessor(environment.codeGenerator, environment.logger)
}

private const val FQ = "moe.lolosia.web.util.router"

private val HTTP_METHODS = linkedMapOf(
    "GET" to "get",
    "POST" to "post",
    "PUT" to "put",
    "DELETE" to "delete",
    "PATCH" to "patch",
)

private const val GENERATED_PACKAGE = "moe.lolosia.web.generated"
private const val GENERATED_FILE = "GeneratedRoutes"

class RouterProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val annotated = resolver.getSymbolsWithAnnotation("$FQ.Controller")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val deferred = annotated.filterNot { it.validate() }
        if (deferred.isNotEmpty()) return deferred

        val controllers = annotated.filter { it.classKind == ClassKind.OBJECT }
        annotated.filter { it.classKind != ClassKind.OBJECT }.forEach {
            logger.error("@Controller 只能标注在 object 上（形如 `@Controller(\"/api\") object XxxController`）", it)
        }

        val code = buildString {
            appendLine("// 由自研路由插件（router-ksp）生成，请勿手动修改")
            appendLine("// 生成内容：@Controller object 中各 @GET/@POST/... 函数的 Ktor 路由注册")
            appendLine("@file:Suppress(\"UNUSED_PARAMETER\", \"unused\", \"RemoveExplicitTypeArguments\", \"RedundantVisibilityModifier\")")
            appendLine()
            appendLine("package $GENERATED_PACKAGE")
            appendLine()
            appendLine("import io.ktor.server.request.receive")
            appendLine("import io.ktor.server.response.respond")
            appendLine("import io.ktor.server.routing.Routing")
            appendLine("import moe.lolosia.web.respondEnveloped")
            HTTP_METHODS.values.forEach { appendLine("import io.ktor.server.routing.$it") }
            appendLine("import $FQ.inject")
            appendLine("import $FQ.readForm")
            appendLine()
            appendLine("/** 批量注册全部注解式控制器的路由。 */")
            appendLine("fun Routing.generatedRoutes() {")
            controllers.forEach { c -> appendController(c) }
            appendLine("}")
        }

        // 聚合输出：生成结果依赖全部 @Controller 所在文件。
        // 必须显式声明 aggregating + 源文件，否则 KSP 增量处理时只扫「脏文件」，
        // 会把未改动文件里的控制器漏掉，产出空的路由表。
        val sourceFiles = controllers.mapNotNull { it.containingFile }.toTypedArray()
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *sourceFiles),
            packageName = GENERATED_PACKAGE,
            fileName = GENERATED_FILE,
        ).bufferedWriter().use { it.write(code) }

        generated = true
        if (controllers.isNotEmpty()) {
            logger.info("[router] 已生成 ${controllers.size} 个控制器的路由：${controllers.joinToString { it.simpleName.asString() }}")
        }
        return emptyList()
    }

    // ===== 控制器 =====

    private fun StringBuilder.appendController(controller: KSClassDeclaration) {
        val prefix = controller.getAnnotation(FQ + ".Controller")?.stringArg("prefix") ?: ""
        val name = controller.qualifiedName?.asString()
            ?: run {
                logger.error("控制器缺少全限定名", controller)
                return
            }

        controller.getDeclaredFunctions().forEach { fn ->
            val http = fn.findHttpMethod() ?: return@forEach
            val routePath = joinPath(prefix, http.second)
            appendRoute(name, fn, http.first, routePath)
        }
    }

    private fun KSFunctionDeclaration.findHttpMethod(): Pair<String, String>? {
        for ((annotation, ktorFun) in HTTP_METHODS) {
            val ann = getAnnotation("$FQ.$annotation") ?: continue
            return ktorFun to (ann.stringArg("path") ?: "")
        }
        return null
    }

    private fun joinPath(prefix: String, path: String): String {
        val p = prefix.trim()
        val s = path.trim()
        val left = if (p.isEmpty() || p == "/") "" else "/" + p.trim('/')
        val right = if (s.isEmpty()) "" else "/" + s.trim('/')
        val joined = left + right
        return joined.ifEmpty { "/" }
    }

    // ===== 单个路由 =====

    private fun StringBuilder.appendRoute(
        controllerFqn: String,
        fn: KSFunctionDeclaration,
        ktorFun: String,
        routePath: String,
    ) {
        val fnName = fn.simpleName.asString()
        val params = fn.parameters
        val args = mutableListOf<String>()
        val body = StringBuilder()
        var needsForm = false

        params.forEach { p ->
            val pName = p.name?.asString() ?: run {
                logger.error("参数缺少名称", p)
                return@forEach
            }
            val type = p.type.resolve()
            val typeText = type.render()

            when {
                p.hasAnnotation("$FQ.Body") -> {
                    body.appendLine("        val $pName: $typeText = call.receive<$typeText>()")
                }

                p.hasAnnotation("$FQ.Path") -> {
                    val n = p.annotationName(FQ + ".Path") ?: pName
                    body.appendLine("        val $pName: $typeText = ${rawAs(type, "call.parameters[\"$n\"]", n)}")
                }

                p.hasAnnotation("$FQ.Query") -> {
                    val n = p.annotationName(FQ + ".Query") ?: pName
                    body.appendLine("        val $pName: $typeText = ${rawAs(type, "call.request.queryParameters[\"$n\"]", n)}")
                }

                p.hasAnnotation("$FQ.Header") -> {
                    val n = p.annotationName(FQ + ".Header") ?: pName
                    body.appendLine("        val $pName: $typeText = ${rawAs(type, "call.request.headers[\"$n\"]", n)}")
                }

                p.hasAnnotation("$FQ.Form") -> {
                    val n = p.annotationName(FQ + ".Form") ?: pName
                    needsForm = true
                    body.appendLine("        val $pName: $typeText = ${rawAs(type, "__form.fields[\"$n\"]", n)}")
                }

                p.hasAnnotation("$FQ.FilePart") -> {
                    val n = p.annotationName(FQ + ".FilePart") ?: pName
                    needsForm = true
                    if (type.isMarkedNullable) {
                        body.appendLine("        val $pName: $typeText = __form.fileOrNull(\"$n\")")
                    } else {
                        body.appendLine("        val $pName: $typeText = $FQ.required(__form.fileOrNull(\"$n\"), \"$n\")")
                    }
                }

                type.isInjectable() -> {
                    body.appendLine("        val $pName: $typeText = call.inject<$typeText>()")
                }

                else -> {
                    logger.error(
                        "参数 `$pName: $typeText` 无法绑定：请加 @Body/@Path/@Query/@Header/@Form/@FilePart，" +
                            "或给类型 $typeText 标注 @$FQ.Injectable 以按类型自动注入",
                        p,
                    )
                }
            }
            args += pName
        }

        appendLine("    $ktorFun(\"$routePath\") {")
        if (needsForm) {
            appendLine("        val __form = call.readForm()")
        }
        append(body)
        val callExpr = "$controllerFqn.$fnName(${args.joinToString(", ")})"
        if (fn.isUnitReturning()) {
            appendLine("        $callExpr")
        } else {
            // 返回值统一走 respondEnveloped 包成 `{code,msg,data}`（见 ApiResult.kt 的说明）
            appendLine("        call.respondEnveloped($callExpr)")
        }
        appendLine("    }")
    }

    // ===== 类型处理 =====

    /** 把「原始字符串表达式」按目标类型转成最终表达式；不可空时套一层 required(...)。 */
    private fun rawAs(type: KSType, rawExpr: String, name: String): String {
        val inner = when (type.declaration.qualifiedName?.asString()) {
            "kotlin.String" -> rawExpr
            "kotlin.Int" -> "$FQ.asIntOrNull($rawExpr, \"$name\")"
            "kotlin.Long" -> "$FQ.asLongOrNull($rawExpr, \"$name\")"
            "kotlin.Double" -> "$FQ.asDoubleOrNull($rawExpr, \"$name\")"
            "kotlin.Boolean" -> "$FQ.asBooleanOrNull($rawExpr, \"$name\")"
            "kotlin.uuid.Uuid" -> "$FQ.asUuidOrNull($rawExpr, \"$name\")"
            else ->
                if (type.isEnum()) "$FQ.asEnumOrNull<${type.renderNonNull()}>($rawExpr, \"$name\")"
                else {
                    logger.error("不支持把请求参数绑定到类型 ${type.render()}，请手写路由", null as KSNode?)
                    rawExpr
                }
        }
        return if (type.isMarkedNullable) inner else "$FQ.required($inner, \"$name\")"
    }

    private fun KSType.render(): String = renderNonNull() + if (isMarkedNullable) "?" else ""

    private fun KSType.renderNonNull(): String {
        val decl = declaration
        val base = decl.qualifiedName?.asString() ?: decl.simpleName.asString()
        val args = arguments
        if (args.isEmpty()) return base
        return args.joinToString(", ", "$base<", ">") { it.type?.resolve()?.render() ?: "*" }
    }

    private fun KSType.isEnum(): Boolean = (declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS

    /** 类型自身（或任一父类型）是否标注了 @Injectable。 */
    private fun KSType.isInjectable(visited: MutableSet<String> = mutableSetOf()): Boolean {
        val decl = declaration as? KSClassDeclaration ?: return false
        val fqn = decl.qualifiedName?.asString() ?: return false
        if (!visited.add(fqn)) return false
        if (decl.hasAnnotation("$FQ.Injectable")) return true
        return decl.superTypes.any { it.resolve().isInjectable(visited) }
    }

    private fun KSFunctionDeclaration.isUnitReturning(): Boolean {
        val t = returnType?.resolve() ?: return true
        return t.declaration.qualifiedName?.asString() == "kotlin.Unit"
    }
}

// ===== KS 小工具 =====

private fun KSAnnotated.hasAnnotation(fqn: String): Boolean = getAnnotation(fqn) != null

private fun KSAnnotated.getAnnotation(fqn: String): KSAnnotation? =
    annotations.firstOrNull { it.shortName.asString() == fqn.substringAfterLast('.') }

private fun KSAnnotated.annotationName(fqn: String): String? =
    getAnnotation(fqn).stringArg("name")?.takeIf { it.isNotBlank() }

private fun KSAnnotation?.stringArg(name: String): String? =
    this?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? String

private fun KSClassDeclaration.getDeclaredFunctions(): List<KSFunctionDeclaration> =
    declarations.filterIsInstance<KSFunctionDeclaration>()
        .filter { it.isHttpRoutable() }
        .toList()

private fun KSFunctionDeclaration.isHttpRoutable(): Boolean =
    Modifier.PRIVATE !in modifiers && Modifier.PROTECTED !in modifiers

private fun KSValueParameter.hasAnnotation(fqn: String): Boolean = getAnnotation(fqn) != null

private fun KSValueParameter.getAnnotation(fqn: String): KSAnnotation? =
    annotations.firstOrNull { it.shortName.asString() == fqn.substringAfterLast('.') }
