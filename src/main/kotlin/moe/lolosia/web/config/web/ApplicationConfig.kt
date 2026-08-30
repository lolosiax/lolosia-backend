package moe.lolosia.web.config.web


import io.ktor.server.application.Application
import moe.lolosia.web.interceptor.configureInterceptors

/**
 * 应用入口模块（application.yaml 中只注册这一个模块）。
 *
 * 所有插件/中间件按「洋葱模型」由外到内依次安装，**注册顺序即执行顺序**。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 12:21
 */
fun Application.configureApplication() {
    // ===== 依赖注入（最先，供后续所有层使用）=====
    configureDi()

    // ===== 最外层：中间件（流量统计 → 日志）=====
    configureInterceptors()

    // ===== 外层：HTTP 基础设施 =====
    configureHttp()
    configureSecurity()
    configureSerialization()
    configureErrors()

    // ===== 内层：业务能力 =====
    configureKoog()
    configureWebjars()

    // ===== 最内层：路由（ktorfitx 注解控制器）=====
    configureRouting()
}
