plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

group = "moe.lolosia.web"
version = "1.0.0-SNAPSHOT"

kotlin {
    // Windows：本地开发调试用
    mingwX64 {
        binaries {
            executable {
                entryPoint = "moe.lolosia.web.main"
            }
        }
    }

    // Linux ARM64：部署到开发板（lckfb / RK3566 级）；需在 WSL 中构建
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "moe.lolosia.web.main"
            }
        }
    }

    sourceSets {
        // KSP2 在 KMP 下不会自动把生成目录挂进 native 编译单元，这里显式声明
        getByName("mingwX64Main").kotlin.srcDir(
            layout.buildDirectory.dir("generated/ksp/mingwX64/mingwX64Main/kotlin")
        )
        getByName("linuxArm64Main").kotlin.srcDir(
            layout.buildDirectory.dir("generated/ksp/linuxArm64/linuxArm64Main/kotlin")
        )

        commonMain.dependencies {
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.statusPages)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(ktorLibs.server.testHost)
        }
    }
}

// ===== 自研路由插件：KSP 需按目标声明处理器（KSP2 已废弃 catch-all 的 ksp(...)）=====
// 注意：KSP2 没有 common/metadata 编译单元，生成代码落在各目标源集；
// 而 Kotlin 编译模型下 common 代码看不到 platform 声明，故入口 main() 必须放各目标源集。
dependencies {
    add("kspMingwX64", project(":router-ksp"))
    add("kspLinuxArm64", project(":router-ksp"))
}
