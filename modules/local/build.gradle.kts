plugins {
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
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }

        commonMain.dependencies {
            api(project(":modules:protocol"))
            api(project(":modules:core"))
            // 帧模型以 JsonObject / JsonElement 收发，故作为 api 暴露
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
