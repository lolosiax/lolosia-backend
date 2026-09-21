package moe.lolosia.router.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

/**
 * 自研注解式路由的 KSP 处理器入口。
 *
 * 目标：复刻 ktorfitx server 端的注解用法（@Controller/@GET/@POST/@Body/@Path/@Query/@Attribute/@Form/@File），
 * 生成 Ktor `Routing` 注册代码。生成逻辑见 [RouterProcessor]。
 *
 * @author 洛洛希雅Lolosia
 */
class RouterProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        RouterProcessor(environment.codeGenerator, environment.logger)
}

class RouterProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // 阶段 1：扫描 @Controller 并生成路由注册代码（见 P0-0.3）
        logger.info("[router] KSP processor active")
        return emptyList()
    }
}
