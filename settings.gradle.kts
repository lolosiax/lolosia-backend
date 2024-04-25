rootProject.name = "lolosia"
include(":static")

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        // maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}