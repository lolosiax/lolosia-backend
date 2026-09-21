package moe.lolosia.web.config.web

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.ktor.server.application.Application
import io.ktor.server.application.install
import moe.lolosia.web.generated.diServices
import moe.lolosia.web.util.event.EventBus
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 依赖注入配置（Koin），数据库见 [databaseModule]。
 *
 * 业务组件无需手动注册：给类加 `@Service`（或 `@Component`）注解，
 * 由 Gradle 插件任务 [moe.lolosia.gradle.task.GenerateDiModuleTask] 扫描生成
 * 批量注册函数 [diServices]，只需在 [appModule] 内调用一次。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 12:21
 */
fun Application.configureDi() {
    install(Koin) {
        slf4jLogger()
        modules(appModule, databaseModule())
    }
}

/** 全局模块注册表。 */
val appModule = module {
    // 基础设施（非 @Service/@Component 的泛型/特殊构造，需手动声明）
    single<JsonMapper> {
        // Bundle 常含跨实体的扩展字段（如 roleId 属于 SysUserRolesEntity），忽略未知属性
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()
    }
    single<EventBus> { EventBus() }
    single<PasswordEncoder> { BCryptPasswordEncoder(10) }

    // @Service/@Component 自动注册（由 GenerateDiModuleTask 扫描生成）
    diServices()
}
