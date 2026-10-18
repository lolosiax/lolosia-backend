plugins {
    // AGP 9 自带 Kotlin 支持，不再需要 org.jetbrains.kotlin.android
    id("com.android.application") version "9.0.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
}

android {
    namespace = "moe.lolosia.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "moe.lolosia.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-SNAPSHOT"
        ndk {
            // 模拟器用 x86_64，真机用 arm64-v8a；两个 ABI 的 .so 由 build-android.ps1 分别产出
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }

    sourceSets["main"].apply {
        // UI 资源直接复用 :modules:client 的 commonMain resources（RML / RCSS）
        assets.directories.add("../client/src/commonMain/resources")
        // 共享源码目录：页面状态、路由、DTO 与 Windows 完全同一份
        kotlin.srcDirs(
            "../ui/src/commonMain/kotlin",
            "../protocol/src/commonMain/kotlin",
        )
        // thirdparty/build-android.ps1 产出 <ABI>/libSDL3.so 与 <ABI>/liblolosia.so
        jniLibs.directories.add("../../thirdparty/androidJniLibs")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

// 下游单独构建时先拉起上游的原生库任务；上游 buildBinaries 调过来时会带这个 property，
// 此时不再回调根构建 —— 否则根项目锁会互相等待（也避免 androidApp → app → androidApp 的循环）。
if (!providers.gradleProperty("lolosiaAndroidFromRoot").isPresent) {
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val backendRoot = rootDir.parentFile.parentFile
    val gradlew = File(backendRoot, if (isWindows) "gradlew.bat" else "gradlew")

    val buildUpstreamAndroidNative = tasks.register<Exec>("buildUpstreamAndroidNative") {
        group = "build"
        description = "调用 backend 根构建的 :androidNative（叶子任务，不会回到 androidApp）"
        workingDir = backendRoot
        if (isWindows) {
            commandLine("cmd", "/c", gradlew.absolutePath, "-p", backendRoot.absolutePath, ":androidNative", "--console=plain")
        } else {
            commandLine(gradlew.absolutePath, "-p", backendRoot.absolutePath, ":androidNative", "--console=plain")
        }
    }

    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(buildUpstreamAndroidNative)
    }
}
