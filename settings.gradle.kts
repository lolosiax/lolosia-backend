pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public") {
            // 阿里云镜像对 io.ktor 的 native 产物（*-mingwx64.klib / *-linuxarm64.klib）同步不全，
            // 会导致 "Could not find ktor-server-xxx-mingwX64Main-*.klib"，强制走 mavenCentral
            // （io.github.smyrgeorge 的 sqlx4k 同理：根模块 jar 在镜像上缺）
            mavenContent {
                excludeGroupByRegex("io\\.ktor")
                excludeGroupByRegex("io\\.github\\.smyrgeorge")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.4.2")
    }
}

rootProject.name = "lolosia-backend"

// 自研路由插件的 KSP 处理器（必须跑在 JVM 上，独立模块）
include(":modules:router-ksp")
// Stage 2：按依赖方向从小到大 include，便于脚本里按路径引用
include(":modules:protocol")
include(":modules:core")
include(":modules:codecs")
include(":modules:client")
include(":modules:server")
// 唯一桌面入口：三个 native 可执行文件都在这里
include(":modules:app-desktop")
