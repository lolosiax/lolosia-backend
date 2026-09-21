plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
