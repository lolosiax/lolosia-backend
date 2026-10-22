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
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter

/** 自研 LLM 工具标记注解。 */
private const val TOOL_FQ = "moe.lolosia.web.util.llm.Tool"

private const val GENERATED_PACKAGE = "moe.lolosia.web.generated"
private const val GENERATED_FILE = "GeneratedTools"

class ToolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ToolProcessor(environment.codeGenerator, environment.logger)
}

/**
 * `@Tool` 方法的 KSP 处理器。
 *
 * 为每个标了 `@Tool` 的 `object` 方法生成一个 [moe.lolosia.web.util.llm.ChatTool] 包装，
 * 汇总成 `generatedTools()`（各目标生成 `actual`，签名见 commonMain 的 `GeneratedRegistries.kt`）。
 * 生成的 `call` 是 `suspend` 的，直接调用原方法 —— 原生实现不需要 Java 侧的阻塞桥接。
 *
 * 支持标量参数（String / Int / Long / Double / Float / Boolean / 枚举）与可空类型；
 * 复杂参数类型（集合、嵌套对象）暂不支持，遇到会编译报错。
 *
 * @author 洛洛希雅Lolosia
 */
class ToolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var written = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (written) return emptyList()
        written = true

        val functions = resolver.getSymbolsWithAnnotation(TOOL_FQ)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()
        val models = functions.mapNotNull { buildModel(it) }
        val sourceFiles = functions.mapNotNull { it.containingFile }.toTypedArray()

        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *sourceFiles),
            packageName = GENERATED_PACKAGE,
            fileName = GENERATED_FILE,
        ).bufferedWriter().use { it.write(render(models)) }

        logger.info("[router] 已生成 ${models.size} 个 @Tool 工具 → generatedTools()")
        return emptyList()
    }

    private fun buildModel(function: KSFunctionDeclaration): ToolModel? {
        if (function.extensionReceiver != null) {
            logger.error("@Tool 方法不支持扩展接收者：${function.simpleName.asString()}", function)
            return null
        }
        val owner = function.parentDeclaration as? KSClassDeclaration
        if (owner == null || owner.classKind != ClassKind.OBJECT) {
            logger.error("@Tool 只能标在 object 的方法上：${function.simpleName.asString()}", function)
            return null
        }
        val ownerFqn = owner.qualifiedName?.asString()
        if (ownerFqn == null) {
            logger.error("@Tool 所在 object 没有全限定名：${function.simpleName.asString()}", function)
            return null
        }

        val annotation = function.annotations.firstOrNull { it.shortName.asString() == "Tool" }
        val name = annotation?.stringArg("name")?.ifBlank { null } ?: function.simpleName.asString()
        val description = annotation?.stringArg("description")?.ifBlank { null }
            ?: function.docString?.firstMeaningfulLine().orEmpty()

        val params = function.parameters.mapNotNull { it.toModel() }.toList()
        if (params.size != function.parameters.size) return null

        val returnType = function.returnType?.resolve()
        val returnsUnit = returnType == null || returnType.declaration.qualifiedName?.asString() == "kotlin.Unit"

        return ToolModel(ownerFqn, function.simpleName.asString(), name, description, params, returnsUnit)
    }

    private fun KSValueParameter.toModel(): ParamModel? {
        val paramName = name?.asString() ?: return null
        val type = type.resolve()
        val declaration = type.declaration
        val typeFqn = declaration.qualifiedName?.asString()
        val nullable = type.isMarkedNullable

        val scalar = Scalar.of(typeFqn, declaration)
        if (scalar == null) {
            logger.error(
                "@Tool 参数 `${paramName}` 的类型 `${typeFqn}` 不支持（仅支持 String/Int/Long/Double/Float/Boolean/枚举）",
                this,
            )
            return null
        }

        val annotation = annotations.firstOrNull { it.shortName.asString() == "ToolParam" }
        val description = annotation?.stringArg("description").orEmpty()
        val required = annotation?.booleanArg("required") ?: true

        return ParamModel(paramName, scalar, typeFqn, nullable, required, description)
    }

    private fun render(models: List<ToolModel>): String = buildString {
        appendLine("// 由自研路由插件（router-ksp）生成，请勿手动修改")
        appendLine("// 生成内容：@Tool 方法 → ChatTool 包装表")
        appendLine("@file:Suppress(\"unused\", \"RedundantVisibilityModifier\")")
        appendLine()
        appendLine("package $GENERATED_PACKAGE")
        appendLine()
        appendLine("import kotlinx.serialization.json.*")
        appendLine("import moe.lolosia.web.util.llm.ChatTool")
        appendLine()
        appendLine("/** 全部 `@Tool` 方法生成的工具表。 */")
        appendLine("actual fun generatedTools(): List<ChatTool> = listOf(")
        models.forEach { appendModel(it) }
        appendLine(")")
    }

    private fun StringBuilder.appendModel(model: ToolModel) {
        appendLine("    object : ChatTool {")
        appendLine("        override val name: String = ${model.name.quoted()}")
        appendLine("        override val description: String = ${model.description.quoted()}")
        appendLine("        override val parameters: JsonObject = buildJsonObject {")
        appendLine("            put(\"type\", \"object\")")
        appendLine("            putJsonObject(\"properties\") {")
        model.params.forEach { param ->
            appendLine("                putJsonObject(${param.name.quoted()}) {")
            appendLine("                    put(\"type\", ${param.scalar.schemaType.quoted()})")
            if (param.description.isNotBlank()) {
                appendLine("                    put(\"description\", ${param.description.quoted()})")
            }
            appendLine("                }")
        }
        appendLine("            }")
        val required = model.params.filter { it.required && !it.nullable }
        if (required.isNotEmpty()) {
            appendLine("            putJsonArray(\"required\") {")
            required.forEach { appendLine("                add(${it.name.quoted()})") }
            appendLine("            }")
        }
        appendLine("        }")
        appendLine("        override suspend fun call(arguments: JsonObject): String {")
        val args = model.params.joinToString(", ") { it.conversion }
        val invocation = "${model.ownerFqn}.${model.fnName}($args)"
        if (model.returnsUnit) {
            appendLine("            $invocation")
            appendLine("            return \"ok\"")
        } else {
            appendLine("            return $invocation.toString()")
        }
        appendLine("        }")
        appendLine("    },")
    }

    /** `@Tool` 方法：对象全限定名 + 方法名 + 工具元信息 + 参数。 */
    private data class ToolModel(
        val ownerFqn: String,
        val fnName: String,
        val name: String,
        val description: String,
        val params: List<ParamModel>,
        val returnsUnit: Boolean,
    )

    /** 单个参数：名字、标量类别、可空性、必填性、描述与取值表达式。 */
    private data class ParamModel(
        val name: String,
        val scalar: Scalar,
        val typeFqn: String?,
        val nullable: Boolean,
        val required: Boolean,
        val description: String,
    ) {
        /** 从 `arguments` 取出该参数并转成目标类型的 Kotlin 表达式。 */
        val conversion: String
            get() {
                val key = name.quoted()
                val accessor = "arguments[$key]?.jsonPrimitive"
                return when (scalar) {
                    Scalar.STRING -> read("$accessor?.contentOrNull", "\"\"")
                    Scalar.INT -> read("$accessor?.intOrNull", "0")
                    Scalar.LONG -> read("$accessor?.longOrNull", "0L")
                    Scalar.DOUBLE -> read("$accessor?.doubleOrNull", "0.0")
                    Scalar.FLOAT -> read("$accessor?.floatOrNull", "0f")
                    Scalar.BOOLEAN -> read("$accessor?.booleanOrNull", "false")
                    Scalar.ENUM -> {
                        val base = "$accessor?.contentOrNull?.let { $typeFqn.valueOf(it) }"
                        if (nullable) base else "$base ?: error(\"缺少必填参数 $name\")"
                    }
                }
            }

        private fun read(base: String, zero: String): String = when {
            nullable -> base
            required -> "$base ?: error(\"缺少必填参数 $name\")"
            else -> "$base ?: $zero"
        }
    }

    /** 支持的标量类别。 */
    private enum class Scalar(val schemaType: String) {
        STRING("string"),
        INT("integer"),
        LONG("integer"),
        DOUBLE("number"),
        FLOAT("number"),
        BOOLEAN("boolean"),
        ENUM("string"),
        ;

        companion object {
            fun of(typeFqn: String?, declaration: KSDeclaration): Scalar? =
                when (typeFqn) {
                    "kotlin.String" -> STRING
                    "kotlin.Int" -> INT
                    "kotlin.Long" -> LONG
                    "kotlin.Double" -> DOUBLE
                    "kotlin.Float" -> FLOAT
                    "kotlin.Boolean" -> BOOLEAN
                    else -> if (declaration is KSClassDeclaration && declaration.classKind == ClassKind.ENUM_CLASS) ENUM else null
                }
        }
    }
}

/** 取有内容的注解参数（跳过默认空串）。 */
private fun KSAnnotation.stringArg(name: String): String? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String

/** 取布尔注解参数。 */
private fun KSAnnotation.booleanArg(name: String): Boolean? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean

/** 取 KDoc 的第一行有内容文本（去掉前导 `*`）。 */
private fun String.firstMeaningfulLine(): String =
    lineSequence()
        .map { it.trim().removePrefix("*").trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

/** 转成 Kotlin 字符串字面量（转义反斜杠、引号、换行与 `$`）。 */
private fun String.quoted(): String = buildString {
    append('"')
    for (c in this@quoted) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(c)
        }
    }
    append('"')
}
