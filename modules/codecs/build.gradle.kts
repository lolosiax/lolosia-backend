import moe.lolosia.gradle.thirdparty.ThirdpartyExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

group = "moe.lolosia"
version = rootProject.version

// 第三方 C 库：头文件、静态库与 .def 都由 :thirdparty 的工件提供；
// 未配置工件仓库时，插件回退到主工程的 build/thirdparty/out/。
val thirdparty = rootProject.extensions.getByType<ThirdpartyExtension>()
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
    // 每个库的 .def、头文件与静态库都来自独立的工件目录（<lib>/<target>），
    // 由根工程的 thirdparty 插件负责从仓库拉取或从 build/thirdparty/out/ 回退。
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        compilations.getByName("main").cinterops {
            thirdpartyLibs.forEach { lib ->
                create(lib) {
                    definitionFile.set(rootProject.layout.projectDirectory.file("thirdparty/$lib/$lib.def"))
                    val resolved = thirdparty.dir(lib, targetName).get()
                    includeDirs.allHeaders(resolved.dir("include").asFile)
                    extraOpts("-libraryPath", resolved.dir("lib").asFile.absolutePath)
                }
            }
        }
        // 工件目录变化时让 cinterop 重跑；并确保先执行对应的准备任务。
        val targetSuffix = targetName.replaceFirstChar { it.uppercaseChar() }
        tasks.matching { it.name.startsWith("cinterop") && it.name.endsWith(targetSuffix) }
            .configureEach {
                thirdpartyLibs.forEach { lib ->
                    dependsOn(thirdparty.syncTask(lib, targetName))
                    inputs.dir(thirdparty.dir(lib, targetName))
                }
            }
    }
}
