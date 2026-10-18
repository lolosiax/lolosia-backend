import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

group = "moe.lolosia"
version = rootProject.version

// 第三方 C 库：产物（thirdparty/<目标>/*.a）与公共头文件随仓库提交，见 thirdparty/build-libs.ps1
// 模块化后 thirdparty 仍在 Gradle 根，必须用 rootProject 定位。
val thirdpartyDir = rootProject.layout.projectDirectory.dir("thirdparty")
val thirdpartyLibs = listOf("zlib", "png", "webp", "jpeg", "gif", "msgpack")

kotlin {
    mingwX64()
    linuxArm64()
    linuxX64()

    // expect/actual class 尚处 Beta，不关会每次编译都刷一条警告；util/image/Image.kt 正是 expect class
    targets.withType<KotlinNativeTarget>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }

        commonMain.dependencies {
            // ExifReader 走 core 的日志；Image / ThumbnailGenerator / XhrData 的公开签名也被上层直接使用
            api(project(":modules:core"))
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    // ===== 第三方 C 库的 cinterop 绑定 =====
    // thirdparty/<目标>/*.a 是预编译好的静态库（升级用 thirdparty/build-libs.ps1），
    // 这里只让 cinterop 解析 .def + 头文件，构建期不需要任何 C 工具链；
    // .def 里声明的 staticLibraries 会被打进 klib，链接期随可执行文件静态链入。
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        compilations.getByName("main").cinterops {
            thirdpartyLibs.forEach { lib ->
                create(lib) {
                    definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/$lib.def"))
                    includeDirs.allHeaders(thirdpartyDir.dir("include").asFile)
                    extraOpts("-libraryPath", thirdpartyDir.dir(targetName).asFile.absolutePath)
                }
            }
        }
        // thirdparty/<目标>/*.a 会被 cinterop **复制**进 klib 的依赖目录，链接器用的是那份副本。
        // 而 cinterop 任务自身只把 .def 与头文件当输入 —— 不把归档也声明进来，
        // 重建静态库后 cinterop 不会重跑，副本一直停在旧版。
        val thirdpartyArchives = rootProject.layout.projectDirectory.dir("thirdparty/$targetName")
            .asFileTree.matching { include("*.a") }
        val targetSuffix = targetName.replaceFirstChar { it.uppercaseChar() }
        tasks.matching { it.name.startsWith("cinterop") && it.name.endsWith(targetSuffix) }
            .configureEach { inputs.files(thirdpartyArchives) }
    }
}
