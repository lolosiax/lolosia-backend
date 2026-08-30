import com.fasterxml.jackson.databind.json.JsonMapper

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.allopen)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ebean)
    alias(libs.plugins.spring.boot)
    id("moe.lolosia")
}

group = "moe.lolosia.web"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "moe.lolosia.web.KtorApplication"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict") // Ebean 生成的代码带 JSR305 注解
    }
}

dependencies {
    // 默认序列化：kotlinx.serialization（与 ktorfitx 的 @Serializable 编译期校验一致）
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    // Jackson 保留备用（需要时手动注册或直接用 ObjectMapper），默认不参与请求序列化
    implementation(ktorLibs.serialization.jackson)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.compression)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.conditionalHeaders)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.webjars)
    implementation(libs.koin.ktor)
    implementation(libs.koin.loggerSlf4j)
    implementation(libs.koog.ktor)
    implementation(libs.logback.classic)
    implementation(libs.webjars.jquery)

    // ktorfitx：注解式控制器（@Controller/@GET/@POST...），KSP 编译期生成路由注册代码
    implementation(libs.ktorfitx.server.core)
    implementation(libs.ktorfitx.server.annotation)
    ksp(libs.ktorfitx.server.ksp)
    // kotlinx.serialization 编译插件会为 @Serializable 类生成序列化器（ktorfitx 编译期校验需要）
    implementation(libs.kotlinx.serialization.core)

    // ===== Ebean ORM（从 spring-project 移植，版本 19.4.0）=====
    implementation(project(":orm"))
    implementation(libs.ebean) {
        exclude("io.ebean", "ebean-platform-all")
        exclude("io.ebean", "ebean-querybean") // 用项目内的 :orm 模块替代
    }
    implementation(libs.ebean.platform.mariadb)
    implementation(libs.mariadb.java.client)
    kapt(libs.ebean.kotlin.querybean.generator)
    implementation(kotlin("reflect"))
    // 通用工具（配置系统等使用）
    implementation(libs.commons.collections4)
    implementation(libs.snakeyaml)
    // 账户系统：BCrypt 密码加密 + 图形验证码
    implementation(libs.spring.security.crypto)
    implementation(libs.commons.logging)
    implementation(libs.hutool.captcha)
    // 资源服务：WebP 图片转换（磁盘缓存，供 /res/?webp=true 使用）
    implementation(libs.webp.imageio)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

// Ebean 实体增强配置
ebean {
    debugLevel = 1
}

// ktorfitx KSP 配置：生成的路由注册文件与函数
ksp {
    arg("ktorfitx.type", "KTOR_SERVER")
    arg("ktorfitx.generate.packageName", "moe.lolosia.web.generated")
    arg("ktorfitx.generate.fileName", "GenerateRoutes")
    arg("ktorfitx.generate.funName", "generateRoutes")
    arg("ktorfitx.language", "CHINESE")
}

// ===== 单文件部署产物：bootJar（Spring Boot 插件只用于打包，不引入 Spring 运行时）=====
// bootJar 用 LaunchedURLClassLoader 逐个加载 BOOT-INF/lib 嵌套 jar，Java 9+ 无模块系统问题，
// 避免 flat fat jar 的 module-info / META-INF-versions 冲突。产物为单个可执行 jar，便于开发板部署。
// vite 前端静态资源由 LolosiaPlugin 并入 BOOT-INF/classes（static/home/）。
springBoot {
    mainClass = "moe.lolosia.web.KtorApplication"
}

tasks.bootJar {
    dependsOn(tasks.jar)
    archiveClassifier = "fat"
}


// ===== Lolosia 构建配置：vite 前端打包 / 常量生成 =====
lolosia {
    packageName = "moe.lolosia.web"
    projectName = "洛洛希雅的Ktor后端"

    // 从根目录 platforms.json 读取 vite 前端工程路径（webPath）
    val platformsFile = rootDir.resolve("platforms.json")
    @Suppress("UNCHECKED_CAST")
    val platformConfig = if (!platformsFile.exists()) emptyMap<String, Any?>()
    else JsonMapper().readValue(platformsFile, Map::class.java) as Map<String, Any?>

    viteJar {
        val webPath = platformConfig["webPath"] as? String
        if (!webPath.isNullOrBlank()) {
            platform("default") {
                contextPath = "home"
                dir = webPath
            }
        }
    }
}
