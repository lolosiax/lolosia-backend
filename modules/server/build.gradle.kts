plugins {
    // KGP 由 buildSrc 提供（classpath 上已存在），故不写版本，避免重复加载
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("moe.lolosia")
}

group = "moe.lolosia"
version = rootProject.version

kotlin {
    mingwX64()
    linuxArm64()
    linuxX64()

    sourceSets {
        // cinterop/指针相关 API 全项目放开（与 installer 等 native 项目一致）
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }

        commonMain.dependencies {
            api(project(":modules:protocol"))
            api(project(":modules:core"))
            api(project(":modules:codecs"))

            // DI：Koin（native 产物已核实齐全）；日志与配置为自研，无额外依赖
            implementation(libs.koin.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kaml)
            implementation(libs.okio)
            implementation(libs.sqlx4k.mysql)

            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.cors)
            implementation(ktorLibs.server.statusPages)
            implementation(ktorLibs.server.conditionalHeaders)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)

            // 裸 TCP：数据库连通性/鉴权预检（见 Database 的 MySqlAuthProbe）
            implementation(ktorLibs.network)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(ktorLibs.server.testHost)
        }
    }
}

// ===== 自研路由插件：KSP 需按目标声明处理器 =====
// 注意：KSP2 没有 common/metadata 编译单元，生成代码落在各目标源集；
// 而 Kotlin 编译模型下 common 代码看不到 platform 声明，故入口 main() 必须放各目标源集。
// sqlx4k 的 @Repository/@Query 代码生成也走 KSP（处理器是 JVM 件，产出 klib 侧的 native 代码）。
dependencies {
    listOf("MingwX64", "LinuxArm64", "LinuxX64").forEach { target ->
        add("ksp$target", project(":modules:router-ksp"))
        add("ksp$target", libs.sqlx4k.codegen)
    }
}

ksp {
    // sqlx4k codegen：生成物包名与方言
    arg("output-package", "moe.lolosia.web.database.generated")
    arg("dialect", "mysql")
}

// ===== Lolosia 构建配置 =====
// 本模块只产出 klib；资源由 :modules:app-desktop 的 classpath 流水线自动合并。
@Suppress("UNCHECKED_CAST")
val platformConfig = rootProject.extra["lolosia.platforms"] as? Map<String, Any?> ?: emptyMap()

lolosia {
    packageName = "moe.lolosia.web"
    projectName = "洛洛希雅的小网站"

    // 服务端只负责生成 VITE_DEFAULT；vite 产物与 classpath 嵌入都在 app-desktop
    constants {
        platform("default", platformConfig["webContextPath"] as? String ?: "home")
    }

    classpath {
        enabled = false
    }
}
