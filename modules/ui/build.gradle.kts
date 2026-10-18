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
        commonMain.dependencies {
            api(project(":modules:protocol"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
