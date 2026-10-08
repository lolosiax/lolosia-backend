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

/** 运行时里挂在 Ktor 类型上的扩展（`ApplicationCall.inject` / `readForm` / `requirePermission`）所在包。 */
private const val KTOR_FQ = "moe.lolosia.web.util.ktor"

/** 生成物要引用的 DTO（权限节点目录的行类型）所在包。 */
private const val DTO_FQ = "moe.lolosia.web.dto"

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

    /** `@Permission` 节点目录：同一节点只留首次出现的（标题/位置取第一个用到它的接口）。 */
    private val catalog = linkedMapOf<String, CatalogEntry>()

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
            HTTP_METHODS.values.forEach { appendLine("import io.ktor.server.routing.$it") }
            appendLine("import $KTOR_FQ.inject")
            appendLine("import $KTOR_FQ.readForm")
            appendLine("import $KTOR_FQ.requirePermission")
            appendLine("import $DTO_FQ.PermissionCatalogEntry")
            appendLine()
            appendLine("/** 批量注册全部注解式控制器的路由。 */")
            appendLine("actual fun Routing.generatedRoutes() {")
            controllers.forEach { c -> appendController(c) }
            appendLine("}")
            appendLine()
            appendLine("/** 全部 @Permission 节点的目录（供管理界面做权限选择器）。 */")
            appendLine("actual fun generatedPermissions(): List<PermissionCatalogEntry> = listOf(")
            catalog.values.sortedBy { it.node }.forEach { e ->
                val title = e.title?.let { "\"${esc(it)}\"" } ?: "null"
                appendLine("    PermissionCatalogEntry(\"${esc(e.node)}\", $title, \"${esc(e.controller)}\", \"${esc(e.function)}\"),")
            }
            appendLine(")")
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
            if (catalog.isNotEmpty()) {
                logger.info("[router] 已生成 ${catalog.size} 个权限节点：${catalog.keys.sorted().joinToString(", ")}")
            }
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
        // object 上的 @Permission 是该控制器全部路由的默认值
        val classPermissions = controller.permissionNodes()

        controller.getDeclaredFunctions().forEach { fn ->
            val http = fn.findHttpMethod() ?: return@forEach
            val routePath = joinPath(prefix, http.second)
            // 函数上的 @Permission 覆盖 object 上的默认值
            val permissions = fn.permissionNodes().ifEmpty { classPermissions }
            appendRoute(name, fn, http.first, routePath, permissions)
            permissions.forEach { node ->
                catalog.getOrPut(node) {
                    CatalogEntry(node, fn.docTitle(), controller.simpleName.asString(), fn.simpleName.asString())
                }
            }
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
        permissions: List<String>,
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
                    if (type.isStringList()) {
                        body.appendLine("        val $pName: $typeText = __form.fieldValues(\"$n\")")
                    } else {
                        body.appendLine("        val $pName: $typeText = ${rawAs(type, "__form.fields[\"$n\"]", n)}")
                    }
                }

                p.hasAnnotation("$FQ.FilePart") -> {
                    val n = p.annotationName(FQ + ".FilePart") ?: pName
                    needsForm = true
                    when {
                        type.isUploadedFileList() ->
                            body.appendLine("        val $pName: $typeText = __form.fileValues(\"$n\")")

                        type.isMarkedNullable ->
                            body.appendLine("        val $pName: $typeText = __form.fileOrNull(\"$n\")")

                        else ->
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
        if (permissions.isNotEmpty()) {
            // 校验放最前：未通过时代码不会读到请求体（readForm/receive 都会消费 body）
            val args = permissions.joinToString(", ") { "\"${esc(it)}\"" }
            appendLine("        call.requirePermission($args)")
        }
        if (needsForm) {
            appendLine("        val __form = call.readForm()")
        }
        append(body)
        val callExpr = "$controllerFqn.$fnName(${args.joinToString(", ")})"
        if (fn.isUnitReturning()) {
            appendLine("        $callExpr")
        } else {
            // 返回值原样 respond：信封由 EnvelopeInterceptor 按响应 MIME 统一包（见该类 KDoc）
            appendLine("        call.respond($callExpr)")
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

    /** `List<String>`：绑定同名表单字段的全部取值（multipart / urlencoded）。 */
    private fun KSType.isStringList(): Boolean = isListOf("kotlin.String")

    /** `List<UploadedFile>`：绑定同名文件项的全部文件。 */
    private fun KSType.isUploadedFileList(): Boolean = isListOf("$FQ.UploadedFile")

    private fun KSType.isListOf(elementFqn: String): Boolean =
        declaration.qualifiedName?.asString() == "kotlin.collections.List" &&
            arguments.size == 1 &&
            arguments[0].type?.resolve()?.declaration?.qualifiedName?.asString() == elementFqn

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

// ===== 权限（@Permission）=====

/** 权限节点目录的一行（仅 KSP 处理期使用）。 */
private data class CatalogEntry(
    val node: String,
    val title: String?,
    val controller: String,
    val function: String,
)

/** 取 `@Permission` 的节点列表（未标注 / 全为空串则为空）。 */
private fun KSAnnotated.permissionNodes(): List<String> =
    getAnnotation("$FQ.Permission").stringListArg("value")

/** 注解的 `vararg value` 实参（KSP 把 vararg 收成 `List<String>`）。 */
private fun KSAnnotation?.stringListArg(name: String): List<String> =
    (this?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? List<*>)
        ?.filterIsInstance<String>()
        ?.filter { it.isNotBlank() }
        ?: emptyList()

/** KDoc 的首个非空行，当权限节点的可读标题。 */
private fun KSFunctionDeclaration.docTitle(): String? = docString
    ?.lineSequence()
    ?.map { it.trim().removePrefix("*").trim() }
    ?.firstOrNull { it.isNotEmpty() }

/** 把文本转义成 Kotlin 字符串字面量的内容。 */
private fun esc(text: String): String =
    text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")
