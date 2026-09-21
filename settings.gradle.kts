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
            mavenContent {
                excludeGroupByRegex("io\\.ktor")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.4.2")
    }
}

rootProject.name = "ktor-template"

// 自研路由插件的 KSP 处理器（必须跑在 JVM 上，独立模块）
include(":router-ksp")
