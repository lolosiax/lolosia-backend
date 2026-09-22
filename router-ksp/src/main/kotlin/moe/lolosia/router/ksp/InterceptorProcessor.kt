package moe.lolosia.router.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate

private const val INTERCEPTOR_FQ = "moe.lolosia.web.interceptor"
private const val GENERATED_PACKAGE = "moe.lolosia.web.generated"
private const val GENERATED_INTERCEPTORS_FILE = "GeneratedInterceptors"

/** 中间件必须是 `Application` 的扩展，否则生成代码里挂不上去。 */
private const val APPLICATION_FQ = "io.ktor.server.application.Application"

class InterceptorProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        InterceptorProcessor(environment.codeGenerator, environment.logger)
}

/**
 * 自研中间件的 KSP 处理器。
 *
 * 扫描 `@Interceptor(order)` 标注的 `Application` 扩展函数，按 order 升序生成注册表
 * `Application.configureInterceptors(koin)`；拦截器形参即依赖，生成时从容器取（`koin.get(...)`）。
 *
 * 编译期校验：非 `Application` 扩展、缺少 order、order 重复、形参类型不是可解析的具名类型。
 *
 * @author 洛洛希雅Lolosia
 */
class InterceptorProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val annotated = resolver.getSymbolsWithAnnotation("$INTERCEPTOR_FQ.Interceptor")
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()

        val deferred = annotated.filterNot { it.validate() }
        if (deferred.isNotEmpty()) return deferred

        val interceptors = annotated.mapNotNull { it.toRef() }
        interceptors.groupBy { it.order }.forEach { (order, same) ->
            if (same.size > 1) {
                val names = same.joinToString { it.name }
                same.forEach { logger.error("中间件 order=$order 重复（$names）：层次顺序必须唯一", it.declaration) }
            }
        }

        val sorted = interceptors.sortedBy { it.order }
        // 与路由生成同理：必须声明 aggregating + 源文件，否则增量处理会漏掉未改动文件里的 @Interceptor
        val sourceFiles = sorted.mapNotNull { it.declaration.containingFile }.toTypedArray()
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *sourceFiles),
            packageName = GENERATED_PACKAGE,
            fileName = GENERATED_INTERCEPTORS_FILE,
        ).bufferedWriter().use { it.write(buildContent(sorted)) }

        generated = true
        if (sorted.isNotEmpty()) {
            logger.info("[router] 已生成 ${sorted.size} 个中间件的注册表：${sorted.joinToString { "${it.name}(${it.order})" }}")
        }
        return emptyList()
    }

    private data class InterceptorRef(
        val packageName: String,
        val name: String,
        val order: Int,
        val declaration: KSFunctionDeclaration,
    )

    private fun KSFunctionDeclaration.toRef(): InterceptorRef? {
        val receiver = extensionReceiver?.resolve()?.declaration?.qualifiedName?.asString()
        if (receiver != APPLICATION_FQ) {
            logger.error("@Interceptor 只能标注在 Application 的扩展函数上（形如 `fun Application.installXxx()`）", this)
            return null
        }
        val order = getAnnotation("$INTERCEPTOR_FQ.Interceptor")?.intArg("order")
        if (order == null) {
            logger.error("@Interceptor 需要显式的 order 参数", this)
            return null
        }
        return InterceptorRef(packageName.asString(), simpleName.asString(), order, this)
    }

    private fun buildContent(interceptors: List<InterceptorRef>): String = buildString {
        appendLine("// 由自研路由插件（router-ksp）生成，请勿手动修改")
        appendLine("// 生成内容：@Interceptor(order) 中间件按 order 升序的注册表（顺序 = 洋葱由外到内）")
        appendLine("@file:Suppress(\"UNUSED_PARAMETER\", \"unused\", \"RedundantVisibilityModifier\")")
        appendLine()
        appendLine("package $GENERATED_PACKAGE")
        appendLine()
        appendLine("import io.ktor.server.application.Application")
        interceptors.map { "${it.packageName}.${it.name}" }.distinct().sorted().forEach {
            appendLine("import $it")
        }
        appendLine("import org.koin.core.Koin")
        appendLine()
        appendLine("/** 按 @Interceptor(order) 升序注册全部中间件。 */")
        appendLine("fun Application.configureInterceptors(koin: Koin) {")
        interceptors.forEach { appendLine("    ${it.name}(${it.dependencyArgs()})") }
        appendLine("}")
    }

    /** 形参即依赖：按形参类型从容器取（`Koin.get(KClass)`，类型由形参的期望类型推断）。 */
    private fun InterceptorRef.dependencyArgs(): String =
        declaration.parameters.joinToString(", ") { p ->
            val type = p.type.resolve()
            val fqn = type.declaration.qualifiedName?.asString()
            if (fqn == null || type.arguments.isNotEmpty()) {
                logger.error(
                    "中间件形参 `${p.name?.asString()}` 的类型必须是可解析的具名非泛型类型（当前为 ${type.declaration.simpleName.asString()}）",
                    p,
                )
            }
            "koin.get($fqn::class)"
        }

    private fun KSAnnotated.getAnnotation(fqn: String): KSAnnotation? =
        annotations.firstOrNull { it.shortName.asString() == fqn.substringAfterLast('.') }

    private fun KSAnnotation.intArg(name: String): Int? =
        arguments.firstOrNull { it.name?.asString() == name }?.value as? Int
}
