package moe.lolosia.gradle.extension

import org.gradle.api.Action
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * classpath 资源嵌入扩展（Kotlin/Native）。
 *
 * 把各源集的 `resources` 目录合并成一个 classpath.zip，并在链接阶段嵌入可执行文件，
 * 运行时由 `moe.lolosia.web.util.classpath` 统一读取，效果与 Java 打资源文件一致。
 *
 * @author 洛洛希雅Lolosia
 */
open class ClasspathExtension {

    /**
     * 参与嵌入的目标名（Kotlin/Native target，如 `mingwX64`）。
     * 留空则自动取工程内全部 native 目标。
     */
    @Input
    @Optional
    var targets: MutableList<String> = mutableListOf()

    /**
     * 是否把 [LolosiaExtension.viteJar] 的前端构建产物一并并入 classpath。
     * zip 内前缀由 viteJar 的 `contextPath` 决定（如 `static/home`）。
     */
    @Input
    @Optional
    var includeViteJar: Boolean = true

    /**
     * zip 条目是否压缩。
     * 说明：当前读取器（ZipClasspath）只实现了 STORED，改回 DEFLATED 需先补 inflate。
     */
    @Input
    @Optional
    var compressed: Boolean = false

    /** 额外资源目录；可为 task 输出，Gradle 会自动建立任务依赖。 */
    internal val additionalResources: MutableList<Any> = mutableListOf()

    /**
     * Windows 宿主上执行 linux 目标的 objcopy 时借道 WSL。
     * 留空 = 用 WSL 的默认发行版 / 默认用户。
     *
     * 为什么需要：konan 自带的 aarch64 objcopy 是 ELF 二进制（Windows 跑不了），
     * mingw 的 objcopy 又只支持 x86-64，Windows 上没有任何工具能产出 aarch64 ELF 目标文件。
     * 而编译与链接本身在 Windows 上是可以跑的，所以只把这一步丢给 WSL。
     */
    @Input
    @Optional
    var wslDistro: String? = null

    @Input
    @Optional
    var wslUser: String? = null

    /** 追加额外资源目录（可为目录、文件树或 task 输出）。 */
    fun resources(vararg paths: Any) {
        additionalResources.addAll(paths)
    }

    fun target(name: String) {
        targets.add(name)
    }

    fun targets(vararg names: String) {
        targets.addAll(names)
    }
}
