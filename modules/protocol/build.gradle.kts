plugins {
    // KGP 由 buildSrc 提供（classpath 上已存在），故不写版本，避免重复加载
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

group = "moe.lolosia"
version = rootProject.version

kotlin {
    mingwX64()
    linuxArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            // 跨端契约：DTO / SSE 事件接口
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
