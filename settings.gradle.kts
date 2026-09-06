pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://maven.aliyun.com/repository/gradle-plugin"){
            mavenContent {
                excludeGroup("io.ebean")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public"){
            mavenContent {
                excludeModuleByRegex("io\\.ktor", "ktor-server-webjars.*")
                // aliyun 镜像对 cn.ktorfitx 的 jar 同步不完整（Gradle 解析不到），强制走 mavenCentral
                excludeGroupByRegex("cn\\.ktorfitx")
                // aliyun 镜像对 io.ebean 部分 artifact 的 jar 同步不完整（如 kotlin-querybean-generator），强制走 mavenCentral
                excludeGroupByRegex("io\\.ebean")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.4.2")
    }
}

rootProject.name = "ktor-template"

// Ebean querybean 模块（自定义的 io.ebean:ebean-querybean 替代实现，从 spring-project 移植）
include(":orm")
