import com.fasterxml.jackson.databind.json.JsonMapper
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import java.util.Properties

plugins {
    // 根只做聚合与发布：deploy / runDebug / runRelease / buildBinaries
    id("moe.lolosia")
    // 第三方原生库按库/目标解析（仓库优先，本地回退）
    id("moe.lolosia.thirdparty")
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

// 产物所在模块：Stage 2 起由 :modules:app-desktop 装配 server + client。
val desktopProjectPath = ":modules:app-desktop"

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

// ===== Android：上游 ⇄ 下游装配（单向调用，禁止回环） =====
// 下游 modules/app-android 是独立 Gradle 构建（AGP 与 buildSrc 的 KGP 不能共享 classpath）。
//   buildBinaries → androidApp →（带 -PlolosiaAndroidFromRoot=true）→ app-android:assembleDebug
//   app-android 单独构建 → preBuild → 根 :androidNative（叶子任务，不会再回到 androidApp）
// 下游被上游拉起时靠这个 property 跳过回调，否则会在根项目锁上死等。
val androidAbis = (findProperty("androidAbis") as String? ?: "x86_64,arm64-v8a")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
require(androidAbis.isNotEmpty()) { "androidAbis 不能为空" }

fun androidTaskSuffix(abi: String): String = when (abi) {
    "x86_64" -> "X86_64"
    "arm64-v8a" -> "Arm64V8a"
    else -> abi.filter { it.isLetterOrDigit() }.replaceFirstChar { it.uppercaseChar() }
}

// ---- 环境探测：能力不足时只警告并跳过，不让 buildBinaries 整体失败 ----
fun findOnPath(vararg names: String): File? {
    val entries = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    for (entry in entries) {
        if (entry.isBlank()) continue
        for (name in names) {
            val candidate = File(entry, name)
            if (candidate.isFile) return candidate
        }
    }
    return null
}

fun resolveAndroidSdk(): File? {
    // 1) local.properties 的 sdk.dir（模块级优先，其次根）
    for (path in listOf("modules/app-android/local.properties", "local.properties")) {
        val file = rootProject.file(path)
        if (!file.isFile) continue
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        val dir = properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() } ?: continue
        val sdk = File(dir)
        if (sdk.isDirectory) return sdk
    }
    // 2) 环境变量
    for (key in listOf("ANDROID_SDK_ROOT", "ANDROID_HOME")) {
        val value = System.getenv(key)
        if (!value.isNullOrBlank() && File(value).isDirectory) return File(value)
    }
    // 3) 常见安装位置
    val fallbacks = listOfNotNull(
        File("D:\\AndroidSdk"),
        System.getenv("LOCALAPPDATA")?.let { File(it, "Android/Sdk") },
    )
    return fallbacks.firstOrNull { it.isDirectory }
}

fun resolveAndroidNdk(sdk: File?): File? {
    val fromEnv = System.getenv("ANDROID_NDK_HOME")
    if (!fromEnv.isNullOrBlank()) {
        val ndk = File(fromEnv)
        if (File(ndk, "build/cmake/android.toolchain.cmake").isFile) return ndk
    }
    sdk ?: return null
    File(sdk, "ndk-bundle").takeIf { File(it, "build/cmake/android.toolchain.cmake").isFile }?.let { return it }
    val ndkRoot = File(sdk, "ndk")
    if (!ndkRoot.isDirectory) return null
    return ndkRoot.listFiles { file -> file.isDirectory && file.name.startsWith("android-ndk-") }
        ?.sortedByDescending { it.name }
        ?.firstOrNull { File(it, "build/cmake/android.toolchain.cmake").isFile }
}

fun resolveCmake(sdk: File?): File? {
    findOnPath("cmake.exe", "cmake")?.let { return it }
    val cmakeRoot = sdk?.let { File(it, "cmake") }
    if (cmakeRoot?.isDirectory == true) {
        cmakeRoot.listFiles()?.sortedByDescending { it.name }?.forEach { dir ->
            val candidate = File(dir, "bin/cmake.exe")
            if (candidate.isFile) return candidate
        }
    }
    return null
}

fun resolveNinja(): File? {
    findOnPath("ninja.exe", "ninja")?.let { return it }
    // pip 安装的 ninja 不一定在 PATH 上（常见于 Windows 的 --user 安装）
    val roots = listOfNotNull(
        System.getenv("APPDATA")?.let { File(it, "Python") },
        System.getenv("LOCALAPPDATA")?.let { File(it, "Programs/Python") },
    )
    for (root in roots) {
        if (!root.isDirectory) continue
        root.walkTopDown().maxDepth(4).forEach { file ->
            if (file.isFile && (file.name == "ninja.exe" || file.name == "ninja")) return file
        }
    }
    return null
}

fun androidToolchainProblems(): List<String> = buildList {
    val sdk = resolveAndroidSdk()
    if (sdk == null) {
        add("找不到 Android SDK（local.properties 的 sdk.dir / ANDROID_SDK_ROOT / ANDROID_HOME）")
    }
    if (resolveAndroidNdk(sdk) == null) {
        add("找不到 Android NDK（ANDROID_NDK_HOME 或 <sdk>/ndk/android-ndk-*）")
    }
    if (resolveCmake(sdk) == null) add("找不到 cmake")
    if (resolveNinja() == null) add("找不到 ninja（python -m pip install ninja）")
}

var androidSkipWarned = false

fun androidCapable(): Boolean {
    if (findProperty("skipAndroid") != null) {
        if (!androidSkipWarned) {
            androidSkipWarned = true
            logger.warn("已通过 -PskipAndroid 跳过 Android 构建")
        }
        return false
    }
    val problems = androidToolchainProblems()
    if (problems.isEmpty()) return true
    if (!androidSkipWarned) {
        androidSkipWarned = true
        logger.warn("跳过 Android 构建：${problems.joinToString("；")}")
        logger.warn("补齐环境后重试，或用 -PskipAndroid=true 显式跳过。")
    }
    return false
}

val androidNativeTasks = androidAbis.map { abi ->
    val jniDir = layout.buildDirectory.dir("thirdparty/out/androidJniLibs/$abi")
    tasks.register<Exec>("androidNative${androidTaskSuffix(abi)}") {
        group = "build"
        description = "用 NDK 重新生成 Android 原生库（$abi）"
        onlyIf { androidCapable() }
        workingDir = rootDir
        inputs.files(fileTree("modules/client/src/nativeCpp") { exclude("**/build/**") })
        inputs.file("thirdparty/gradle/build-android.ps1")
        outputs.file(jniDir.map { it.file("libSDL3.so") })
        outputs.file(jniDir.map { it.file("liblolosia.so") })
        commandLine(
            "powershell", "-ExecutionPolicy", "Bypass",
            "-File", "thirdparty/gradle/build-android.ps1",
            "-Abi", abi,
        )
    }
}

val androidNative = tasks.register("androidNative") {
    group = "build"
    description = "生成全部 Android ABI 的原生库（${androidAbis.joinToString()}）"
    dependsOn(androidNativeTasks)
}

val androidApp = tasks.register<Exec>("androidApp") {
    group = "build"
    description = "构建 Android APK（下游独立 Gradle 构建，debug）"
    dependsOn(androidNative)
    onlyIf { androidCapable() }
    workingDir = rootDir
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine(
            "cmd", "/c", "gradlew.bat",
            "-p", "modules/app-android", "assembleDebug",
            "--console=plain", "-PlolosiaAndroidFromRoot=true",
        )
    } else {
        commandLine(
            "./gradlew",
            "-p", "modules/app-android", "assembleDebug",
            "--console=plain", "-PlolosiaAndroidFromRoot=true",
        )
    }
}

tasks.named("buildBinaries") {
    dependsOn(androidApp)
}
