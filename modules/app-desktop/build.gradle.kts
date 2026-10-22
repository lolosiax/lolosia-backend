import java.time.Year

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("moe.lolosia")
}

group = "moe.lolosia"
version = rootProject.version

// UI 实验那套图形栈（SDL3 → FreeType → RmlUi → liblolosia）静态链接所需的额外参数。
// 内容是 SDL3 的 INTERFACE_LINK_LIBRARIES 清单 —— 它自己不带 Windows 系统库。
// ⚠️ 不要在这里补 -lstdc++：Kotlin/Native 自己的 runtime 就是 C++ 写的、也已经链了 libstdc++。
val uiStackLinkerOpts = listOf(
    "-lm", "-lkernel32", "-luser32", "-lgdi32", "-lwinmm", "-limm32",
    "-lole32", "-loleaut32", "-lversion", "-luuid", "-ladvapi32",
    "-lsetupapi", "-lshell32", "-ldinput8",
)

// core 解出的 libcrypto.a 路径；由 :modules:core 的 unpackCurlStaticLibs<Target> 产出。
val coreCurlLibDir = rootProject.layout.projectDirectory.dir("modules/core/build/curl-static-libs")
fun curlCryptoLib(target: String): String =
    coreCurlLibDir.dir(target).file("libcrypto.a").asFile.absolutePath

kotlin {
    mingwX64 {
        binaries {
            executable {
                baseName = rootProject.name
                entryPoint = "moe.lolosia.web.main"
                linkerOpts(uiStackLinkerOpts)
            }
        }
    }
    linuxArm64 {
        binaries {
            executable {
                baseName = rootProject.name
                entryPoint = "moe.lolosia.web.main"
                linkerOpts(curlCryptoLib("linuxArm64"))
            }
        }
    }
    linuxX64 {
        binaries {
            executable {
                baseName = rootProject.name
                entryPoint = "moe.lolosia.web.main"
                linkerOpts(curlCryptoLib("linuxX64"))
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":modules:server"))
            api(project(":modules:client"))
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// linux 链接前先让 core 解出 libcrypto.a（跨项目任务依赖用任务路径，避免提前求值 core）
listOf("linuxArm64", "linuxX64").forEach { target ->
    val suffix = target.replaceFirstChar { it.uppercase() }
    tasks.matching { it.name.startsWith("link") && it.name.endsWith(suffix) }
        .configureEach { dependsOn(":modules:core:unpackCurlStaticLibs$suffix") }
}

// ===== Lolosia 构建配置 =====
// classpath.zip 的合并与嵌入（windres / objcopy / 链接参数）由 buildSrc 的 LolosiaPlugin 实现。
// 依赖模块（server / client / core / codecs）的源集资源由插件自动合并。
@Suppress("UNCHECKED_CAST")
val platformConfig = rootProject.extra["lolosia.platforms"] as? Map<String, Any?> ?: emptyMap()

lolosia {
    packageName = "moe.lolosia.web"
    projectName = "洛洛希雅的小网站"

    viteJar {
        val webPath = platformConfig["webPath"] as? String
        val contextPath = platformConfig["webContextPath"] as? String ?: "home"
        if (!webPath.isNullOrBlank()) {
            platform("default") {
                this.contextPath = contextPath
                dir = webPath
            }
        }
    }

    classpath {
        compressed = true
    }

    // Windows exe 的 PE 资源：版本号取工程 version（不用手改），图标复用前端的 favicon.ico
    windows {
        val webPath = platformConfig["webPath"] as? String
        if (!webPath.isNullOrBlank()) {
            icon = File(webPath, "public/favicon.ico")
        }
        companyName = "洛洛希雅"
        copyright = "© ${Year.now().value} 洛洛希雅Lolosia"
    }

    // VITE_DEFAULT 由 :modules:server 生成，这里不能重复生成
    constants {
        enabled = false
    }
}
