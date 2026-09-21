import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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

// =====================================================================================
// classpath.zip：把各源集的 resources 合并打包，再嵌入可执行文件
//
// 目标效果与 Java 打资源文件一致：`src/<sourceSet>/resources/**` 就是 classpath 根，
// 运行时用 `classpath.read("static/home/index.html")` 读取（路径与打包前一致）。
//
// 合并优先级（靠前者优先，重名覆盖后者）：
//   目标源集（mingwX64Main / linuxArm64Main） > 中间源集（nativeMain 等） > commonMain
// 也就是说 commonMain 是所有 target 共享的公共资源，目标源集可覆盖同名文件。
// 其它 task 产生的资源目录通过 `additionalClasspathResources` 注入，打包阶段一并合并。
// =====================================================================================

/** 其它 task 注入的额外资源目录（可来自 task 输出，Gradle 会自动建立依赖）。 */
val additionalClasspathResources: ConfigurableFileCollection = objects.fileCollection()

/** 按「越具体越优先」的次序取出目标 main 编译单元涉及的全部源集。 */
fun resourceSourceSetsOf(target: KotlinTarget): List<KotlinSourceSet> {
    val ordered = mutableListOf<KotlinSourceSet>()
    fun visit(sourceSet: KotlinSourceSet) {
        if (ordered.any { it.name == sourceSet.name }) return
        ordered += sourceSet
        sourceSet.dependsOn.forEach { visit(it) }
    }
    visit(target.compilations.getByName("main").defaultSourceSet)
    return ordered
}

fun classpathDirOf(targetName: String) =
    layout.buildDirectory.dir("generated/classpath/$targetName")

fun registerClasspathZip(targetName: String, target: KotlinTarget) =
    tasks.register<Zip>("classpathZip${targetName.replaceFirstChar { it.uppercaseChar() }}") {
        group = "classpath"
        description = "把 $targetName 各源集的 resources 合并成 classpath.zip"
        archiveFileName.set("classpath.zip")
        destinationDirectory.set(classpathDirOf(targetName))

        // 先入者优先：越具体的源集先入包，同名资源由它决定
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // 说明：读取器当前只支持 STORED；若改回 DEFLATED，需要在 ZipClasspath 中实现 inflate
        // （可用 Kotlin/Native 自带的 platform.zlib）。
        entryCompression = ZipEntryCompression.STORED
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

        resourceSourceSetsOf(target).forEach { sourceSet -> from(sourceSet.resources) }
        from(additionalClasspathResources)
    }

val mingwX64Target = kotlin.targets.getByName("mingwX64") as KotlinNativeTarget
val linuxArm64Target = kotlin.targets.getByName("linuxArm64") as KotlinNativeTarget

val classpathZipMingwX64 = registerClasspathZip("mingwX64", mingwX64Target)
val classpathZipLinuxArm64 = registerClasspathZip("linuxArm64", linuxArm64Target)

// ===== Windows：windres 把 .rc 编译成 .res，再交给链接器 =====

/** Kotlin/Native 自带的 mingw 工具链目录（windres 在其中，无需另装 MinGW）。 */
fun konanMingwBinDir(): File {
    val konanDataDir = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    val binDir = File(konanDataDir, "dependencies/msys2-mingw-w64-x86_64-2/bin")
    check(binDir.isDirectory) {
        "未找到 Kotlin/Native 自带的 mingw 工具链：$binDir\n" +
            "请先执行一次 mingwX64 链接（会自动下载工具链），或检查 KONAN_DATA_DIR。"
    }
    return binDir
}

val prepareClasspathRcMingwX64 = tasks.register<Copy>("prepareClasspathRcMingwX64") {
    group = "classpath"
    description = "把 classpath.rc 放到 classpath.zip 同级目录，供 windres 使用"
    from(layout.projectDirectory.file("classpath/mingw/classpath.rc"))
    into(classpathDirOf("mingwX64"))
}

val windresClasspathMingwX64 = tasks.register<Exec>("windresClasspathMingwX64") {
    group = "classpath"
    description = "把 classpath.zip 编译成 PE 资源对象"
    dependsOn(classpathZipMingwX64, prepareClasspathRcMingwX64)
    val dir = classpathDirOf("mingwX64").get().asFile
    workingDir = dir
    inputs.file(dir.resolve("classpath.rc"))
    inputs.file(dir.resolve("classpath.zip"))
    outputs.file(dir.resolve("classpath.res"))
    val binDir = konanMingwBinDir()
    commandLine(
        File(binDir, "windres").absolutePath,
        "classpath.rc", "-O", "coff", "-o", "classpath.res",
    )
    environment("PATH", "${binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}")
}

mingwX64Target.binaries.withType<Executable>().configureEach {
    linkTaskProvider.configure { dependsOn(windresClasspathMingwX64) }
    linkerOpts(classpathDirOf("mingwX64").get().asFile.resolve("classpath.res").absolutePath)
}

// ===== Linux：objcopy 把 zip 转成目标文件，链接进程序并由符号表暴露起止地址 =====

val linuxClasspathEmbedDir = classpathDirOf("linuxArm64")

val objcopyClasspathLinuxArm64 = tasks.register<Exec>("objcopyClasspathLinuxArm64") {
    group = "classpath"
    description = "把 classpath.zip 转成可链接的目标文件（需 aarch64-linux-gnu-objcopy）"
    dependsOn(classpathZipLinuxArm64)
    val dir = linuxClasspathEmbedDir.get().asFile
    workingDir = dir
    inputs.file(dir.resolve("classpath.zip"))
    outputs.file(dir.resolve("classpath.o"))
    // 输入文件名决定符号名：_binary_classpath_zip_start / _end / _size
    commandLine(
        "aarch64-linux-gnu-objcopy",
        "-I", "binary", "-O", "elf64-littleaarch64", "-B", "aarch64",
        "classpath.zip", "classpath.o",
    )
}

linuxArm64Target.binaries.withType<Executable>().configureEach {
    linkTaskProvider.configure { dependsOn(objcopyClasspathLinuxArm64) }
    linkerOpts(
        // 让 objcopy 产生的符号进入动态符号表，平台侧才能用 dlsym 查到
        "-Wl,--export-dynamic",
        linuxClasspathEmbedDir.get().asFile.resolve("classpath.o").absolutePath,
    )
}
