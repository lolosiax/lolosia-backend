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
            // 本地服务的帧模型与进程内传输
            api(project(":modules:local"))
            implementation(libs.okio)
        }
        // EventBus 的 SSE 需要增量读取响应体；WinHTTP 引擎会先读完整个响应，
        // 长连接因此拿不到任何一帧。libcurl 的写回调是流式的。
        nativeMain.dependencies {
            implementation(ktorLibs.client.curl)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
