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
import com.google.devtools.ksp.symbol.KSClassDeclaration

/** sqlx4k 标注仓库接口的注解。 */
private const val REPOSITORY_FQ = "io.github.smyrgeorge.sqlx4k.annotation.Repository"

/** 自研路由「按类型注入」的标记注解。 */
private const val INJECTABLE_FQ = "moe.lolosia.web.util.router.Injectable"

/** 自研 DI「Koin 单例」的标记注解：据此生成 `serviceModule()` 的构造器注入绑定。 */
private const val SERVICE_FQ = "moe.lolosia.web.util.router.Service"

/**
 * sqlx4k codegen 给实现类起的后缀。
 *
 * 由 `io.github.smyrgeorge.sqlx4k.processor.RepositoryProcessor` 里的字符串拼接固定为
 * `<接口简单名>Impl`，没有配置项（`@Repository` 只有 `mapper()` 一个参数）。
 */
private const val IMPL_SUFFIX = "Impl"

/** sqlx4k codegen 的生成物包名，由 `ksp { arg("output-package", …) }` 传入。 */
private const val OUTPUT_PACKAGE_OPTION = "output-package"

private const val GENERATED_PACKAGE = "moe.lolosia.web.generated"
private const val GENERATED_FILE = "GeneratedInjection"

class InjectionProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        InjectionProcessor(environment.codeGenerator, environment.logger, environment.options)
}

/**
 * 目标源集 DI 绑定的 KSP 处理器。
 *
 * 生成三个函数供各目标 `main()` 使用（生成物落在目标源集，因为 sqlx4k 的仓库实现只在那里可见）：
 * - `repositoryModules()`：把每个 `@Repository` 接口绑到 sqlx4k 生成的 `<接口名>Impl`（无参 object）；
 * - `serviceModule()`：把标了 `@Service` 的类型绑成 Koin 单例（构造器注入，参数按类型从容器取）；
 * - `Koin.registerInjectables()`：把标了 `@Injectable` 的容器实例接进路由的按类型注入；
 *   标了 `requestScoped = true` 的**不**生成（它们由 `call.attributes` 逐请求提供，见 `Bootstrap`）。
 *
 * 实现类在本处理器落盘时尚未生成（sqlx4k 的产物要到下一轮才可见），故先按命名约定推导，
 * 等下一轮再回头校验一次 —— 直接返回 deferred 会让 KSP 重跑同一轮，把 sqlx4k 也拖着重复写文件。
 *
 * @author 洛洛希雅Lolosia
 */
class InjectionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private var written = false

    /** 首轮写下的绑定表，留到下一轮校验实现类确实存在。 */
    private var toCheck: List<RepoRef> = emptyList()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!written) return write(resolver)

        if (toCheck.isNotEmpty()) {
            val refs = toCheck
            toCheck = emptyList()
            refs.filterNot { resolver.findImpl(it.impl) != null }.forEach {
                logger.error(
                    "找不到仓库实现类 `${it.impl}`：确认 `${it.iface}` 是 sqlx4k 的 @Repository 接口" +
                        "（实现类名按其约定 `<接口名>$IMPL_SUFFIX` 推导）"
                )
            }
        }
        return emptyList()
    }

    private fun write(resolver: Resolver): List<KSAnnotated> {
        val repositories = resolver.getSymbolsWithAnnotation(REPOSITORY_FQ)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val injectables = resolver.getSymbolsWithAnnotation(INJECTABLE_FQ)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        val services = resolver.getSymbolsWithAnnotation(SERVICE_FQ)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val implPackage = options[OUTPUT_PACKAGE_OPTION]
        if (implPackage == null) {
            logger.error("缺少 KSP 参数 `$OUTPUT_PACKAGE_OPTION`：无法定位 sqlx4k 生成的仓库实现类")
            written = true
            return emptyList()
        }

        val repoRefs = repositories.mapNotNull { decl ->
            val iface = decl.qualifiedName?.asString()
            if (iface == null) {
                logger.error("仓库接口 `${decl.simpleName.asString()}` 没有全限定名，无法生成绑定")
                return@mapNotNull null
            }
            RepoRef(iface, "$implPackage.${decl.simpleName.asString()}$IMPL_SUFFIX")
        }.sortedBy { it.iface }

        val serviceRefs = services.mapNotNull { decl ->
            val fqn = decl.qualifiedName?.asString()
            val ctor = decl.primaryConstructor
            when {
                fqn == null -> {
                    logger.error("`@Service` 类型 `${decl.simpleName.asString()}` 没有全限定名，无法生成绑定")
                    null
                }

                ctor == null -> {
                    logger.error("`@Service` 类型 `$fqn` 没有主构造器，无法生成构造器注入绑定")
                    null
                }

                else -> ServiceRef(fqn, ctor.parameters.size)
            }
        }.sortedBy { it.fqn }

        val injectableFqns = injectables
            .filterNot { it.isRequestScoped() }
            .mapNotNull { it.qualifiedName?.asString() }
            .sorted()

        val sourceFiles = (repositories + injectables + services).mapNotNull { it.containingFile }.toTypedArray()
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *sourceFiles),
            packageName = GENERATED_PACKAGE,
            fileName = GENERATED_FILE,
        ).bufferedWriter().use { it.write(buildContent(repoRefs, serviceRefs, injectableFqns)) }

        written = true
        toCheck = repoRefs
        logger.info(
            "[router] 已生成 DI 绑定：${repoRefs.size} 个仓库 → repositoryModules()，" +
                "${serviceRefs.size} 个服务 → serviceModule()，" +
                "${injectableFqns.size} 个可注入类型 → Koin.registerInjectables()"
        )
        return emptyList()
    }

    private data class RepoRef(val iface: String, val impl: String)

    /** `@Service` 类型：[fqn] 为全限定名，[arity] 为主构造器参数个数（每个参数生成一个 `get()`）。 */
    private data class ServiceRef(val fqn: String, val arity: Int)

    private fun Resolver.findImpl(fqn: String): KSClassDeclaration? =
        getClassDeclarationByName(getKSNameFromString(fqn))

    /** `@Injectable(requestScoped = true)`：类型由请求上下文逐次提供，不进容器。 */
    private fun KSClassDeclaration.isRequestScoped(): Boolean = annotations.any { ann ->
        ann.shortName.asString() == INJECTABLE_FQ.substringAfterLast('.') && ann.booleanArg("requestScoped")
    }

    private fun KSAnnotation.booleanArg(name: String): Boolean =
        arguments.firstOrNull { it.name?.asString() == name }?.value == true

    private fun buildContent(
        repositories: List<RepoRef>,
        services: List<ServiceRef>,
        injectables: List<String>,
    ): String = buildString {
        appendLine("// 由自研路由插件（router-ksp）生成，请勿手动修改")
        appendLine("// 生成内容：@Repository 接口 → sqlx4k 实现的 Koin 绑定；@Service 类型 → Koin 单例绑定；@Injectable 类型 → 路由按类型注入的注册")
        appendLine("@file:Suppress(\"unused\", \"RedundantVisibilityModifier\")")
        appendLine()
        appendLine("package $GENERATED_PACKAGE")
        appendLine()
        (repositories.flatMap { listOf(it.iface, it.impl) } + services.map { it.fqn } + injectables)
            .distinct()
            .sorted()
            .forEach { appendLine("import $it") }
        appendLine("import moe.lolosia.web.util.koin.registerInjectable")
        appendLine("import org.koin.core.Koin")
        appendLine("import org.koin.core.module.Module")
        appendLine("import org.koin.dsl.module")
        appendLine()
        appendLine("/** 各 `@Repository` 接口 → sqlx4k 生成的实现（无参 object）的绑定。 */")
        appendLine("actual fun repositoryModules(): List<Module> = listOf(")
        appendLine("    module {")
        repositories.forEach {
            appendLine("        single<${it.iface.substringAfterLast('.')}> { ${it.impl.substringAfterLast('.')} }")
        }
        appendLine("    }")
        appendLine(")")
        appendLine()
        appendLine("/** 各 `@Service` 类型的 Koin 单例绑定（构造器注入，参数按类型从容器取）。 */")
        appendLine("actual fun serviceModule(): Module = module {")
        services.forEach {
            val simple = it.fqn.substringAfterLast('.')
            appendLine("    single { $simple(${List(it.arity) { "get()" }.joinToString(", ")}) }")
        }
        appendLine("}")
        appendLine()
        appendLine("/** 把容器里的实例接进路由的按类型注入（控制器形参声明该类型即自动注入）。 */")
        appendLine("actual fun Koin.registerInjectables() {")
        injectables.forEach { appendLine("    registerInjectable<${it.substringAfterLast('.')}>()") }
        appendLine("}")
    }
}
