import com.fasterxml.jackson.databind.json.JsonMapper
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    // KGP 由 buildSrc 提供（classpath 上已存在），故不写版本，避免重复加载
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("moe.lolosia")
}

group = "moe.lolosia"
version = "1.0.0-SNAPSHOT"

// Curl 引擎（Linux 目标的 HTTP 客户端底层）以静态库形式链入 libcurl / libssl / libcrypto，
// 而链接器扫描归档的顺序里 libcrypto 排在依赖它的 libssl 之前，OpenSSL 符号因此解析不到。
// 这里把 klib 内的 libcrypto.a 解到构建目录，在链接参数末尾再追加一次。
//
// 每个 linux 目标解自己那一份（`build/curl-static-libs/<目标>/`）：libcrypto.a 是分架构的，
// 共用一个文件会让后构建的目标覆盖掉先构建的那份。
val curlStaticLibDir = layout.buildDirectory.dir("curl-static-libs")

/** linux 目标的 libcrypto.a 路径；由下文的 `unpackCurlStaticLibs<Target>` 产出。 */
fun curlCryptoLib(target: String): String =
    curlStaticLibDir.get().dir(target).file("libcrypto.a").asFile.absolutePath

// 第三方 C 库：产物（thirdparty/<目标>/*.a）与公共头文件随仓库提交，见 thirdparty/build-libs.ps1
val thirdpartyDir = layout.projectDirectory.dir("thirdparty")
val thirdpartyLibs = listOf("zlib", "png", "webp", "jpeg", "gif")

kotlin {
    // Windows：本地开发调试用
    mingwX64 {
        binaries {
            executable {
                entryPoint = "moe.lolosia.web.main"
            }
        }
    }

    // Linux ARM64：部署到开发板（lckfb / RK3566 级）
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "moe.lolosia.web.main"
                linkerOpts(curlCryptoLib("linuxArm64"))
            }
        }
    }

    // Linux X64：部署到 x64 云主机；与 linuxArm64 共享 src/linuxMain 下的平台实现
    linuxX64 {
        binaries {
            executable {
                entryPoint = "moe.lolosia.web.main"
                linkerOpts(curlCryptoLib("linuxX64"))
            }
        }
    }

    // ===== 第三方 C 库的 cinterop 绑定 =====
    // thirdparty/<目标>/*.a 是预编译好的静态库（升级用 thirdparty/build-libs.ps1），
    // 这里只让 cinterop 解析 .def + 头文件，构建期不需要任何 C 工具链；
    // .def 里声明的 staticLibraries 会被打进 klib，链接期随可执行文件静态链入。
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        // expect/actual class 尚处 Beta，不关会每次编译都刷一条警告；util/image/Image.kt 正是 expect class
        compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
        compilations.getByName("main").cinterops {
            thirdpartyLibs.forEach { lib ->
                create(lib) {
                    definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/$lib.def"))
                    includeDirs.allHeaders(thirdpartyDir.dir("include").asFile)
                    extraOpts("-libraryPath", thirdpartyDir.dir(targetName).asFile.absolutePath)
                }
            }
        }
    }

    sourceSets {
        // cinterop/指针相关 API 全项目放开（与 installer 等 native 项目一致）
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }

        commonMain.dependencies {
            // DI：Koin（native 产物已核实齐全）；日志与配置为自研，无额外依赖
            implementation(libs.koin.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kaml)
            implementation(libs.okio)
            implementation(libs.sqlx4k.mysql)

            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.cors)
            implementation(ktorLibs.server.statusPages)
            implementation(ktorLibs.server.conditionalHeaders)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)

            // 对外请求（api 包）：引擎按目标平台选择，见各平台源集
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.websockets)
            // 裸 TCP：数据库连通性/鉴权预检（见 Database 的 MySqlAuthProbe）
            implementation(ktorLibs.network)
        }
        // 两个 linux 目标共享 src/linuxMain 下的平台实现
        linuxMain.dependencies {
            implementation(ktorLibs.client.curl)
        }
        mingwX64Main.dependencies {
            implementation(ktorLibs.client.winhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(ktorLibs.server.testHost)
        }
    }
}

// ===== Curl 引擎的 libcrypto 静态库补齐（见文件顶部说明）=====
val curlTargets = listOf("linuxArm64", "linuxX64")

val unpackCurlStaticLibs = curlTargets.associateWith { target ->
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

curlTargets.forEach { target ->
    val suffix = target.replaceFirstChar { it.uppercase() }
    tasks.matching { it.name.startsWith("link") && it.name.endsWith(suffix) }
        .configureEach { dependsOn(unpackCurlStaticLibs.getValue(target)) }
}

// ===== 自研路由插件：KSP 需按目标声明处理器 =====
// 注意：KSP2 没有 common/metadata 编译单元，生成代码落在各目标源集；
// 而 Kotlin 编译模型下 common 代码看不到 platform 声明，故入口 main() 必须放各目标源集。
// sqlx4k 的 @Repository/@Query 代码生成也走 KSP（处理器是 JVM 件，产出 klib 侧的 native 代码）。
dependencies {
    // ksp<目标名> 是 KSP 为每个编译单元建的配置，处理器按目标各挂一份
    listOf("MingwX64", "LinuxArm64", "LinuxX64").forEach { target ->
        add("ksp$target", project(":router-ksp"))
        add("ksp$target", libs.sqlx4k.codegen)
    }
}

ksp {
    // sqlx4k codegen：生成物包名与方言
    arg("output-package", "moe.lolosia.web.database.generated")
    arg("dialect", "mysql")
}

// ===== Lolosia 构建配置 =====
// classpath.zip 的合并与嵌入（windres / objcopy / 链接参数）由 buildSrc 的 LolosiaPlugin 实现。
lolosia {
    packageName = "moe.lolosia.web"
    projectName = "洛洛希雅的小网站"

    // 从根目录 platforms.json 读取前端工程路径（webPath）与部署服务器列表（deploy）
    val platformsFile = rootDir.resolve("platforms.json")
    @Suppress("UNCHECKED_CAST")
    val platformConfig = if (!platformsFile.exists()) emptyMap<String, Any?>()
    else JsonMapper().readValue(platformsFile, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    deploy {
        val deployConfig = platformConfig["deploy"] as? Map<String, Any?>

        activate = deployConfig?.get("active") as? String
        val servers = deployConfig?.get("servers") as? List<Map<String, Any?>>

        servers?.forEach { server ->
            server(server["name"] as String) {
                host = server["host"] as String
                port = (server["port"] as? Number)?.toInt() ?: 22
                user = server["user"] as String
                password = server["password"] as String
                // docker（默认）：JVM 需要根目录有 Dockerfile/docker_shell.sh，原生可执行文件则直接挂载进基础镜像
                mode = server["mode"] as? String ?: "docker"
                // 目标机架构：配了 arm64 / x64 就部署原生可执行文件，不配则部署 JVM 的 bootJar
                abi = server["abi"] as? String
                // 可执行文件的启动参数（main() 取 argv[0] 作端口）
                args = (server["args"] as? List<*>)?.map { it.toString() }?.toMutableList() ?: mutableListOf()
            }
        }

        // 原生部署的共享设置（可选，不写即为默认值）。
        // 具体哪台机器部署原生、部署哪个架构，由各 server 的 abi 决定。
        binary {
            buildType = "release"
            // docker 形态的容器入口：可执行文件与配置以挂载方式注入，这个镜像只当运行环境
            dockerImage = "ubuntu:24.04"
        }
    }

    // 镜像补齐（国内直连 docker.io 基本拉不动，这里用华为云 SWR 的 docker.io 镜像代理）：
    // 缺失时先拉 mirror 再打回原名，远端运行用的始终是 imageName 本身
    docker {
        image("ubuntu:24.04") {
            mirror = "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/ubuntu:24.04"
        }
    }

    viteJar {
        val webPath = platformConfig["webPath"] as? String
        if (!webPath.isNullOrBlank()) {
            platform("default") {
                contextPath = "home"
                dir = webPath
            }
        }
    }

    classpath {
        // 留空 targets = 自动覆盖工程内全部 native 目标；
        // includeViteJar 默认 true：vite 产物以 static/<contextPath>/ 前缀并入
        // 额外资源可继续用 resources("build/xxx") 注入
        // Windows 宿主构建 linux 目标时，objcopy 会自动借道 WSL（可用 wslDistro / wslUser 指定发行版与用户）
        // compressed = true：zip 条目用 DEFLATED（读取端 ZipClasspath 已用 platform.zlib 支持 inflate）
        compressed = true
    }
}
