import com.fasterxml.jackson.databind.json.JsonMapper
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    // 根只做聚合与发布：deploy / runDebug / runRelease / buildBinaries
    id("moe.lolosia")
}

group = "moe.lolosia"
version = "1.0.0-SNAPSHOT"

// platforms.json 只在根解析一次；子模块通过 extra 读取，避免多处解析漂移
val platformsFile = rootProject.file("platforms.json")
@Suppress("UNCHECKED_CAST")
val platformConfig: Map<String, Any?> =
    if (platformsFile.exists()) {
        JsonMapper().readValue(platformsFile, Map::class.java) as Map<String, Any?>
    } else {
        emptyMap()
    }

extra["lolosia.platforms"] = platformConfig

// 产物所在模块。Stage 1 先指向单体 server；Stage 2 拆出 app-desktop 后改这里。
val desktopProjectPath = ":modules:server"

// 根任务要读子模块的 link 任务输出，必须先让该模块完成配置。
// ⚠️ 必须在 extra 赋值之后：强制求值子模块时，它的 build.gradle.kts 会立刻读取 extra。
evaluationDependsOn(desktopProjectPath)
val desktopProject = project(desktopProjectPath)

// ===== 部署：只在根注册 =====
@Suppress("UNCHECKED_CAST")
val deployConfig = platformConfig["deploy"] as? Map<String, Any?>

lolosia {
    deploy {
        artifactProject = desktopProjectPath

        activate = deployConfig?.get("active") as? String
        @Suppress("UNCHECKED_CAST")
        val servers = deployConfig?.get("servers") as? List<Map<String, Any?>>

        servers?.forEach { server ->
            server(server["name"] as String) {
                host = server["host"] as String
                port = (server["port"] as? Number)?.toInt() ?: 22
                user = server["user"] as String
                password = server["password"] as String
                mode = server["mode"] as String ?: "docker"
                abi = server["abi"] as String
                args = (server["args"] as? List<*>)?.map { it.toString() }?.toMutableList()
                    ?: mutableListOf()
            }
        }

        binary {
            buildType = "release"
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
}

// ===== runDebug / runRelease =====
val hostTarget = when {
    Os.isFamily(Os.FAMILY_WINDOWS) -> "MingwX64"
    System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "LinuxArm64"
    else -> "LinuxX64"
}

val appArgs = (findProperty("appArgs") as String?)
    ?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

fun registerRun(name: String, buildType: String) {
    val link = desktopProject.tasks.named(
        "link${buildType}Executable$hostTarget",
        KotlinNativeLink::class.java,
    )
    tasks.register<Exec>(name) {
        group = "application"
        dependsOn(link)
        workingDir = rootDir
        doFirst {
            executable = link.get().outputFile.get().absolutePath
            args.clear()
            args.addAll(appArgs)
        }
    }
}

registerRun("runDebug", "Debug")
registerRun("runRelease", "Release")

// ===== buildBinaries =====
val binDir = layout.buildDirectory.dir("bin")
val buildType = (findProperty("buildType") as String? ?: "release").lowercase()
val cap = buildType.replaceFirstChar { it.uppercaseChar() }

val desktopTargets = buildList {
    // mingwX64 只在 Windows 宿主上构建；Linux 宿主跳过，避免整个任务失败
    if (Os.isFamily(Os.FAMILY_WINDOWS)) add(Triple("MingwX64", "windows-x64", "exe"))
    add(Triple("LinuxArm64", "linux-arm64", "kexe"))
    add(Triple("LinuxX64", "linux-x64", "kexe"))
}

tasks.register<Sync>("buildBinaries") {
    group = "build"
    description = "构建全部桌面二进制并汇总到 build/bin"

    into(binDir)
    desktopTargets.forEach { (target, platform, ext) ->
        val link = desktopProject.tasks.named(
            "link${cap}Executable$target",
            KotlinNativeLink::class.java,
        )
        dependsOn(link)
        from(link.flatMap { it.outputFile }) {
            rename {
                "${rootProject.name}-$platform-$buildType-${rootProject.version}.$ext"
            }
        }
    }
}
