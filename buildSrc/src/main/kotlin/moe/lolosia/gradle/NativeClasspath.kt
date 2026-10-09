package moe.lolosia.gradle

import moe.lolosia.gradle.extension.ClasspathExtension
import moe.lolosia.gradle.extension.LolosiaExtension
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.File

/**
 * classpath.zip 资源嵌入流水线（Kotlin/Native）。
 *
 * 目标效果与 Java 打资源文件一致：源集下的 `resources` 目录就是 classpath 根，
 * 运行时用 `classpath.read("static/home/index.html")` 读取（路径与打包前一致）。
 *
 * 合并优先级（靠前者优先，重名覆盖后者）：
 *   vite 前端产物 > 目标源集（mingwX64Main / linuxArm64Main） > 中间源集（nativeMain 等） > commonMain
 * 也就是说 commonMain 是所有 target 共享的公共资源，目标源集可覆盖同名文件。
 * 其它 task 产生的资源目录可通过 `lolosia.classpath.resources(...)` 注入，
 * vite 前端产物则在 `lolosia.classpath.includeViteJar` 打开时自动并入。
 *
 * **按构建类型分两条链路**（[NativeBuildType]，与 mingw / linux 那对目标是两回事）：
 * - `release`：并入 vite 产物，线上跑的就是它；
 * - `debug`：不建到 `viteJar` 的依赖、也不并 vite 产物 —— 本地调试时前端走 vite dev server
 *   （见 `.env.serve-dev`），省掉每次重启都要重跑一遍 `vite build` 的十几秒。
 *   其余资源（`permissions.yaml` 等）照常嵌入，调试时的权限判定才有依据。
 *
 * 嵌入方式按目标族区分：
 * - mingw* ：windres 编译成 PE 资源（.res），作为链接输入；
 * - linux* ：objcopy 把 zip 转成目标文件（.o），靠 `_binary_classpath_zip_start/_end` 符号暴露地址。
 *
 * @author 洛洛希雅Lolosia
 */
internal fun Project.configureNativeClasspath(lolosia: LolosiaExtension) {
    val extension = lolosia.classpath
    val kotlinExt = extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return

    val targets = kotlinExt.targets.withType<KotlinNativeTarget>()
        .filter { extension.targets.isEmpty() || it.name in extension.targets }
    if (targets.isEmpty()) return

    targets.forEach { configureTargetClasspath(it, extension) }
}

private fun Project.configureTargetClasspath(target: KotlinNativeTarget, extension: ClasspathExtension) {
    val suffix = target.name.replaceFirstChar { it.uppercaseChar() }

    // 任务名沿用旧的无后缀命名（release），debug 那条补「Debug」后缀以示区分
    val release = registerClasspath(target, suffix, "", extension, includeVite = true)
    val debug = registerClasspath(target, suffix, "Debug", extension, includeVite = false)

    target.binaries.withType<Executable>().configureEach {
        val embed = if (buildType == NativeBuildType.DEBUG) debug else release
        linkTaskProvider.configure {
            dependsOn(embed.steps)
            // 资源是经 linkerOpts 以路径形式交给链接器的，Gradle 看不见它的内容变化 ——
            // 不显式声明成输入，只改资源（如 permissions.yaml）不会触发重新链接，跑的还是旧二进制。
            // 用 inputs.files 而非 inputs.file：文件尚未产出时不至于判定输入校验失败。
            inputs.files(embed.artifact)
        }
        linkerOpts(*embed.linkerArgs.toTypedArray())
    }
}

/** 一条 classpath 嵌入链路：链接前要跑完的任务、交给链接器的输入文件、追加的链接参数。 */
private class ClasspathEmbed(
    val steps: TaskProvider<*>,
    val artifact: File,
    val linkerArgs: List<String>,
)

/**
 * 建出某条链路（[includeVite] 区分 release / debug）的 classpath.zip 与嵌入任务。
 *
 * 两条链路的产物分开放（debug 落在 `<目标>/debug/`）：zip 同名，混在一个目录里会互相覆盖。
 */
private fun Project.registerClasspath(
    target: KotlinNativeTarget,
    suffix: String,
    flavorSuffix: String,
    extension: ClasspathExtension,
    includeVite: Boolean,
): ClasspathEmbed {
    val embedDir = layout.buildDirectory.dir(
        "generated/classpath/${target.name}" + if (includeVite) "" else "/debug"
    )

    val zipTask = tasks.register("classpathZip$suffix$flavorSuffix", Zip::class.java) {
        group = "classpath"
        description = "把 ${target.name} 各源集的 resources 合并成 classpath.zip"

        archiveFileName.set(CLASSPATH_ZIP_NAME)
        destinationDirectory.set(embedDir)

        // 先入者优先：同名资源由先加入者决定（vite 产物 > 具体源集 > 通用源集 > 注入资源）
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        entryCompression =
            if (extension.compressed) ZipEntryCompression.DEFLATED else ZipEntryCompression.STORED
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

        // 只有 release 这条才并 vite 产物：debug 链连依赖都不建，`runDebugExecutable*` 就不会去跑 vite build
        if (includeVite) includeViteJar(extension)
        resourceSourceSetsOf(target).forEach { sourceSet -> from(sourceSet.resources) }
        extension.additionalResources.forEach { from(it) }
    }

    return when {
        target.name.startsWith("mingw") ->
            embedAsWindowsResource(suffix, flavorSuffix, zipTask, embedDir)

        target.name.startsWith("linux") ->
            embedAsLinuxObject(target, suffix, flavorSuffix, zipTask, embedDir, extension)

        else -> {
            logger.warn("classpath 资源嵌入暂不支持目标 ${target.name}：其 resources 不会进入可执行文件")
            val zip = embedDir.get().asFile.resolve(CLASSPATH_ZIP_NAME)
            ClasspathEmbed(zipTask, zip, emptyList())
        }
    }
}

/** 按「越具体越优先」的次序取出目标 main 编译单元涉及的全部源集。 */
private fun resourceSourceSetsOf(target: KotlinNativeTarget): List<KotlinSourceSet> {
    val ordered = mutableListOf<KotlinSourceSet>()

    fun visit(sourceSet: KotlinSourceSet) {
        if (ordered.any { it.name == sourceSet.name }) return
        ordered += sourceSet
        sourceSet.dependsOn.forEach { visit(it) }
    }

    visit(target.compilations.getByName("main").defaultSourceSet)
    return ordered
}

/** vite 前端产物并入 classpath（zip 内前缀由 viteJar 的 contextPath 决定）。 */
private fun Zip.includeViteJar(extension: ClasspathExtension) {
    if (!extension.includeViteJar) return
    val viteJar = project.tasks.findByName("viteJar") as? AbstractArchiveTask ?: return
    dependsOn(viteJar)
    // 注意：必须用 matching{} 过滤，不能用 from(x){} 闭包形式 ——
    // 后者会新建子 CopySpec，其遍历顺序固定排在根 from() 之后，会破坏「先入者优先」。
    from(
        project.zipTree(viteJar.archiveFile).matching {
            // 只取前端产物，不要 jar 自带的清单（Gradle 的 Jar 会额外落一份到根目录）
            exclude("MANIFEST.MF", "META-INF/MANIFEST.MF")
        }
    )
}

// ===== Windows：windres 把 .rc 编译成 .res，再交给链接器 =====

private fun Project.embedAsWindowsResource(
    suffix: String,
    flavorSuffix: String,
    zipTask: TaskProvider<Zip>,
    embedDir: Provider<Directory>,
): ClasspathEmbed {
    val prepareRc = tasks.register("prepareClasspathRc$suffix$flavorSuffix", Copy::class.java) {
        group = "classpath"
        description = "把 classpath.rc 与 app.manifest 放到 classpath.zip 同级目录，供 windres 使用"
        from(layout.projectDirectory.dir("classpath/mingw"))
        into(embedDir)
    }

    val windres = tasks.register("windresClasspath$suffix$flavorSuffix", Exec::class.java) {
        group = "classpath"
        description = "把 classpath.zip 编译成 PE 资源对象"
        dependsOn(zipTask, prepareRc)

        val dir = embedDir.get().asFile
        workingDir = dir
        inputs.file(dir.resolve("classpath.rc"))
        // app.manifest 由 classpath.rc 引用（RT_MANIFEST），内容变化也要重新编译
        inputs.file(dir.resolve(APP_MANIFEST))
        inputs.file(dir.resolve(CLASSPATH_ZIP_NAME))
        outputs.file(dir.resolve(CLASSPATH_RES))

        val binDir = konanMingwBinDir()
        commandLine(
            File(binDir, "windres").absolutePath,
            "classpath.rc", "-O", "coff", "-o", CLASSPATH_RES,
        )
        environment("PATH", "${binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}")
    }

    val resource = embedDir.get().asFile.resolve(CLASSPATH_RES)
    return ClasspathEmbed(windres, resource, listOf(resource.absolutePath))
}

/** Kotlin/Native 自带的 mingw 工具链目录（windres 在其中，无需另装 MinGW）。 */
private fun konanMingwBinDir(): File {
    val konanDataDir = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    val binDir = File(konanDataDir, "dependencies/msys2-mingw-w64-x86_64-2/bin")
    check(binDir.isDirectory) {
        "未找到 Kotlin/Native 自带的 mingw 工具链：$binDir\n" +
            "请先执行一次 mingwX64 链接（会自动下载工具链），或检查 KONAN_DATA_DIR。"
    }
    return binDir
}

// ===== Linux：objcopy 把 zip 转成目标文件，链接进程序并由符号表暴露起止地址 =====

private fun Project.embedAsLinuxObject(
    target: KotlinNativeTarget,
    suffix: String,
    flavorSuffix: String,
    zipTask: TaskProvider<Zip>,
    embedDir: Provider<Directory>,
    extension: ClasspathExtension,
): ClasspathEmbed {
    val embedPath = embedDir.get().asFile
    val objcopy = objcopyConfigFor(target.name)
    if (objcopy == null) {
        logger.warn("未配置 ${target.name} 的 objcopy 工具链：其 resources 不会进入可执行文件")
        return ClasspathEmbed(zipTask, embedPath.resolve(CLASSPATH_ZIP_NAME), emptyList())
    }

    // Windows 宿主上没有任何 objcopy 能产出 aarch64 ELF（konan 自带的那个是 ELF 二进制、
    // mingw 的只支持 x86-64），只把这一步借道 WSL；编译与链接仍然在 Windows 上跑。
    val useWsl = Os.isFamily(Os.FAMILY_WINDOWS)

    val objcopyTask = tasks.register("objcopyClasspath$suffix$flavorSuffix", Exec::class.java) {
        group = "classpath"
        description = "把 classpath.zip 转成可链接的目标文件（需 ${objcopy.tool}）"
        dependsOn(zipTask)

        val dir = embedDir.get().asFile
        inputs.file(dir.resolve(CLASSPATH_ZIP_NAME))
        outputs.file(dir.resolve(CLASSPATH_OBJECT))

        val objcopyArgs = listOf(
            objcopy.tool,
            "-I", "binary", "-O", objcopy.outputFormat, "-B", objcopy.architecture,
            CLASSPATH_ZIP_NAME, CLASSPATH_OBJECT,
        )
        if (useWsl) {
            // 不用工作目录传递路径：从 Windows 进程拉起的 wsl 未必能正确翻译 cwd，一律用绝对路径
            commandLine(
                listOf("wsl") +
                    wslOptions(extension) +
                    listOf("--", "bash", "-c", "cd ${toWslPath(dir)} && ${objcopyArgs.joinToString(" ")}")
            )
        } else {
            // 输入文件名决定符号名：_binary_classpath_zip_start / _end / _size
            workingDir = dir
            commandLine(objcopyArgs)
        }
    }

    val obj = embedPath.resolve(CLASSPATH_OBJECT)
    return ClasspathEmbed(
        objcopyTask,
        obj,
        // 让 objcopy 产生的符号进入动态符号表，平台侧才能用 dlsym 查到
        listOf("-Wl,--export-dynamic", obj.absolutePath),
    )
}

private fun wslOptions(extension: ClasspathExtension): List<String> = buildList {
    extension.wslDistro?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-d", it)) }
    extension.wslUser?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-u", it)) }
}

/** Windows 绝对路径（`F:\a\b`）→ WSL 下的 `/mnt/f/a/b`。 */
private fun toWslPath(file: File): String {
    val path = file.absolutePath
    val drive = path.substring(0, 1).lowercase()
    val rest = path.substring(2).replace('\\', '/')
    return "/mnt/$drive$rest"
}

private data class ObjcopyConfig(val tool: String, val outputFormat: String, val architecture: String)

private fun objcopyConfigFor(targetName: String): ObjcopyConfig? = when (targetName) {
    "linuxArm64" -> ObjcopyConfig("aarch64-linux-gnu-objcopy", "elf64-littleaarch64", "aarch64")
    "linuxX64" -> ObjcopyConfig("x86_64-linux-gnu-objcopy", "elf64-x86-64", "i386:x86-64")
    else -> null
}

private const val CLASSPATH_ZIP_NAME = "classpath.zip"
private const val CLASSPATH_RES = "classpath.res"

/** Windows 应用程序清单文件名（classpath.rc 里以 RT_MANIFEST 引用）。 */
private const val APP_MANIFEST = "app.manifest"
private const val CLASSPATH_OBJECT = "classpath.o"
