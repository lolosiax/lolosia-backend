import moe.lolosia.gradle.thirdparty.ThirdpartyExtension
import org.gradle.api.tasks.Sync

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

group = "moe.lolosia"
version = rootProject.version

// 第三方原生库（SDL3 / FreeType / RmlUi）由根工程的 thirdparty 插件解析；
// 自研门面 liblolosia.a 仍从 thirdparty/mingwX64 取。
val thirdparty = rootProject.extensions.getByType<ThirdpartyExtension>()

val lolosiaLibDir = layout.buildDirectory.dir("thirdparty/lolosia/mingwX64/lib")

// 仓库地址与账号不入库；Gradle 从 local.properties 读入后经环境变量交给脚本
fun Exec.passThirdpartyRepo() {
    if (thirdparty.repoUrl.isNotBlank()) {
        environment("LOLOSIA_THIRDPARTY_REPO", thirdparty.repoUrl)
        environment("LOLOSIA_THIRDPARTY_USERNAME", thirdparty.repoUsername)
        environment("LOLOSIA_THIRDPARTY_PASSWORD", thirdparty.repoPassword)
    }
}

// RmlUi 的后端胶水源码只在源码包里，不在静态库工件中；编译 liblolosia 前先把它放到
// build/thirdparty/tree/RmlUi-6.3，优先从自建仓库 sources/ 获取。
val prepareRmlUiSource = tasks.register<Exec>("prepareRmlUiSource") {
    group = "thirdparty"
    description = "准备 RmlUi 源码树（供 liblolosia 的 SDL_GPU 后端胶水使用）"
    workingDir = rootProject.projectDir
    commandLine(
        "powershell", "-ExecutionPolicy", "Bypass",
        "-File", "modules/client/src/nativeCpp/fetch-rmlui.ps1",
    )
    passThirdpartyRepo()
    inputs.file(layout.projectDirectory.file("src/nativeCpp/fetch-rmlui.ps1"))
    outputs.dir(rootProject.layout.buildDirectory.dir("thirdparty/tree/RmlUi-6.3"))
}

val buildLolosia = tasks.register<Exec>("buildLolosia") {
    group = "build"
    description = "从源码编译自研门面 liblolosia.a（mingwX64）"
    workingDir = rootProject.projectDir
    dependsOn(prepareRmlUiSource)
    dependsOn(thirdparty.syncTask("sdl3", "mingwX64"))
    dependsOn(thirdparty.syncTask("freetype", "mingwX64"))
    dependsOn(thirdparty.syncTask("rmlui", "mingwX64"))
    commandLine(
        "powershell", "-ExecutionPolicy", "Bypass",
        "-File", "modules/client/src/nativeCpp/build.ps1",
        "-Target", "mingwX64",
    )
    inputs.dir(layout.projectDirectory.dir("src/nativeCpp/src"))
    inputs.dir(layout.projectDirectory.dir("src/nativeCpp/include"))
    inputs.file(layout.projectDirectory.file("src/nativeCpp/build.ps1"))
    inputs.dir(rootProject.layout.projectDirectory.dir("thirdparty/include"))
    outputs.file(rootProject.layout.projectDirectory.file("thirdparty/mingwX64/liblolosia.a"))
}

val copyLolosiaLib = tasks.register<Sync>("copyLolosiaStaticLib") {
    dependsOn(buildLolosia)
    from(rootProject.layout.projectDirectory.file("thirdparty/mingwX64/liblolosia.a"))
    into(lolosiaLibDir)
}

kotlin {
    mingwX64 {
        // UI 实验（moe.lolosia.web.ui）：C ABI 门面 liblolosia.a + RmlUi/SDL3/FreeType。
        // 只给 mingwX64 建 cinterop —— linux 目标还没有交叉构建好的 SDL3。
        compilations.getByName("main").cinterops {
            create("lolosia") {
                definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/lolosia.def"))
                includeDirs.allHeaders(layout.projectDirectory.dir("src/nativeCpp/include").asFile)
                val sdl3 = thirdparty.dir("sdl3", "mingwX64").get().dir("lib").asFile.absolutePath
                val freetype = thirdparty.dir("freetype", "mingwX64").get().dir("lib").asFile.absolutePath
                val rmlui = thirdparty.dir("rmlui", "mingwX64").get().dir("lib").asFile.absolutePath
                val lolosia = lolosiaLibDir.get().asFile.absolutePath
                extraOpts(
                    "-libraryPath", sdl3,
                    "-libraryPath", freetype,
                    "-libraryPath", rmlui,
                    "-libraryPath", lolosia,
                )
            }
        }
        tasks.matching { it.name == "cinteropLolosiaMingwX64" }.configureEach {
            dependsOn(copyLolosiaLib)
            dependsOn(thirdparty.syncTask("sdl3", "mingwX64"))
            dependsOn(thirdparty.syncTask("freetype", "mingwX64"))
            dependsOn(thirdparty.syncTask("rmlui", "mingwX64"))
            inputs.dir(lolosiaLibDir)
            inputs.dir(thirdparty.dir("sdl3", "mingwX64"))
            inputs.dir(thirdparty.dir("freetype", "mingwX64"))
            inputs.dir(thirdparty.dir("rmlui", "mingwX64"))
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
