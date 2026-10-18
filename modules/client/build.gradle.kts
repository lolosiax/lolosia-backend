plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

group = "moe.lolosia"
version = rootProject.version

// 第三方 C 库与 nativeCpp 门面都以 Gradle 根为基准定位
val thirdpartyDir = rootProject.layout.projectDirectory.dir("thirdparty")

kotlin {
    mingwX64 {
        // UI 实验（moe.lolosia.web.ui）：C ABI 门面 liblolosia.a 与 SDL3 都在 thirdparty/mingwX64 下。
        // 只给 mingwX64 建 cinterop —— linux 目标还没有交叉构建好的 SDL3。
        compilations.getByName("main").cinterops {
            create("lolosia") {
                definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/lolosia.def"))
                includeDirs.allHeaders(
                    thirdpartyDir.dir("include").asFile,
                    layout.projectDirectory.dir("src/nativeCpp/include").asFile,
                )
                extraOpts("-libraryPath", thirdpartyDir.dir("mingwX64").asFile.absolutePath)
            }
        }
    }
    linuxArm64()
    linuxX64()

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }

        commonMain.dependencies {
            api(project(":modules:protocol"))
            api(project(":modules:core"))
            api(project(":modules:codecs"))
            api(project(":modules:ui"))
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
