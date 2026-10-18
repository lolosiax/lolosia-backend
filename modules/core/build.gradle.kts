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
            // 出站传输的公开签名里出现 HttpMethod / Flow，故作为 api 暴露
            api(ktorLibs.client.core)
            api(ktorLibs.client.websockets)
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        nativeMain.dependencies {
            implementation(libs.okio)
        }
        linuxMain.dependencies {
            implementation(ktorLibs.client.curl)
        }
        mingwX64Main.dependencies {
            implementation(ktorLibs.client.winhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// ===== Curl 引擎的 libcrypto 静态库补齐 =====
// ktor-client-curl 以静态库形式链入 libcurl / libssl / libcrypto，而链接器扫描归档的顺序里
// libcrypto 排在依赖它的 libssl 之前，OpenSSL 符号因此解析不到。这里把 klib 内的 libcrypto.a
// 解到 core 的构建目录；最终可执行文件（:modules:app-desktop）通过 linkerOpts 再追加一次。
//
// 每个 linux 目标解自己那一份（`build/curl-static-libs/<目标>/`）：libcrypto.a 是分架构的。
val curlStaticLibDir = layout.buildDirectory.dir("curl-static-libs")
val curlTargets = listOf("linuxArm64", "linuxX64")

curlTargets.forEach { target ->
    val suffix = target.replaceFirstChar { it.uppercase() }
    tasks.register("unpackCurlStaticLibs$suffix") {
        val klibs = configurations.named("${target}CompileKlibraries")
        val outDir = curlStaticLibDir.map { it.dir(target) }
        inputs.files(klibs)
        outputs.dir(outDir)
        doLast {
            val out = outDir.get().asFile
            out.mkdirs()
            klibs.get()
                .filter { it.name.startsWith("ktor-client-curl") && it.name.endsWith(".klib") }
                .forEach { klib ->
                    project.zipTree(klib)
                        .matching { include("**/libcrypto.a") }
                        .forEach { it.copyTo(out.resolve("libcrypto.a"), overwrite = true) }
                }
        }
    }
}
