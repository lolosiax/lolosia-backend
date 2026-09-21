import com.fasterxml.jackson.databind.json.JsonMapper

plugins {
    // KGP 由 buildSrc 提供（classpath 上已存在），故不写版本，避免重复加载
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("moe.lolosia")
}

group = "moe.lolosia.web"
version = "1.0.0-SNAPSHOT"

kotlin {
    // Windows：本地开发调试用
    mingwX64 {
        binaries {
            executable {
                entryPoint = "moe.lolosia.web.main"
            }
        }
    }

    // Linux ARM64：部署到开发板（lckfb / RK3566 级）；需在 WSL 中构建
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "moe.lolosia.web.main"
            }
        }
    }

    sourceSets {
        // cinterop/指针相关 API 全项目放开（与 installer 等 native 项目一致）
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }

        commonMain.dependencies {
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.statusPages)
            implementation(ktorLibs.server.conditionalHeaders)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(ktorLibs.server.testHost)
        }
    }
}

// ===== 自研路由插件：KSP 需按目标声明处理器 =====
// 注意：KSP2 没有 common/metadata 编译单元，生成代码落在各目标源集；
// 而 Kotlin 编译模型下 common 代码看不到 platform 声明，故入口 main() 必须放各目标源集。
dependencies {
    add("kspMingwX64", project(":router-ksp"))
    add("kspLinuxArm64", project(":router-ksp"))
}

// ===== Lolosia 构建配置 =====
// classpath.zip 的合并与嵌入（windres / objcopy / 链接参数）由 buildSrc 的 LolosiaPlugin 实现。
lolosia {
    packageName = "moe.lolosia.web"
    projectName = "洛洛希雅的Ktor后端"

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
                // docker（默认）需要根目录有 Dockerfile/docker_shell.sh；systemd 需要 <项目名>.service
                mode = server["mode"] as? String ?: "docker"
            }
        }

        // 部署原生可执行文件（不写这段就按 JVM 的 bootJar 部署）。
        // 单元名默认 <rootProject.name>-native.service，与现网 JVM 版（7129）并行验证；
        // 验证通过后把 args 改成 7129、退役 JVM 服务即可。
        binary {
            target = "linuxArm64"
            buildType = "release"
            args = mutableListOf("7130")
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
    }
}
