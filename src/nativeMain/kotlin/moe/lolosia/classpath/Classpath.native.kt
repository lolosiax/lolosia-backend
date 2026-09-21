package moe.lolosia.classpath

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.rewind

/**
 * 平台相关的 classpath 装载。
 *
 * - 若设置了环境变量 [ENV_CLASSPATH_DIR]，用 [DiskClasspath] 读磁盘目录（开发期回退：改资源不必重编二进制）；
 * - 否则读取**构建期嵌入到可执行文件**的 `classpath.zip`（各目标取指针方式不同，见 [embeddedClasspathImage]）。
 *
 * @author 洛洛希雅Lolosia
 */

/** 嵌入资源的映像：指向可执行文件内的数据 + 字节数。 */
internal class EmbeddedClasspathImage(val pointer: COpaquePointer, val size: Int)

/** 各目标实现：取出可执行文件内嵌的 `classpath.zip`；未嵌入时返回 `null`。 */
internal expect fun embeddedClasspathImage(): EmbeddedClasspathImage?

/**
 * 两个映像符号之间的字节数（Linux 侧 `_binary_..._start/_end` 差值）。
 *
 * `CPointer.rawValue` 的类型在不同 Kotlin 版本间有变化（`NativePtr` 可能是 value class），
 * 统一走 [rawAddress] 转成 `Long` 再计算。
 */
internal fun classpathImageSize(start: COpaquePointer, end: COpaquePointer): Int =
    (rawAddress(end) - rawAddress(start)).toInt()

/** 取指针的数值地址。 */
@Suppress("USELESS_CAST")
internal fun rawAddress(pointer: COpaquePointer): Long = pointer.rawValue.toLong()

/** 空实现（既没嵌入也没配回退目录时使用，保证接口始终可用）。 */
internal object EmptyClasspath : Classpath {
    override val entries: List<String> = emptyList()
    override fun read(path: String): ByteArray? = null
    override fun list(prefix: String): List<String> = emptyList()
    override fun exists(path: String): Boolean = false
}

/** 开发期回退目录的环境变量名。 */
public const val ENV_CLASSPATH_DIR: String = "KTOR_CLASSPATH_DIR"

/**
 * 磁盘目录实现：直接读 [root] 下的文件。供开发期使用，不参与生产。
 *
 * 注意：`list/entries` 不做目录遍历（恒为空），只支持按路径 [read]；需要完整列举时用嵌入的 zip 形态。
 */
public class DiskClasspath(private val root: String) : Classpath {

    override val entries: List<String> = emptyList()

    override fun read(path: String): ByteArray? {
        val rel = normalizeClasspathPath(path)
        if (rel.isEmpty() || rel.contains("..")) return null
        return readFileBytes("${root.trimEnd('/')}/$rel")
    }

    override fun list(prefix: String): List<String> = emptyList()
}

/**
 * 按平台装载 classpath 并安装到 [ClasspathHolder]。由 `startServer` 启动时调用一次。
 */
public fun installPlatformClasspath(): Classpath {
    val diskRoot = getenv(ENV_CLASSPATH_DIR)?.toKString()
    val installed: Classpath = if (!diskRoot.isNullOrBlank()) {
        DiskClasspath(diskRoot)
    } else {
        embeddedClasspathImage()?.let { ZipClasspath.open(it.pointer, it.size) } ?: EmptyClasspath
    }
    ClasspathHolder.install(installed)
    return installed
}

// ===== 文件读取（posix，两个 native 目标都可用）=====

/**
 * 读取整个文件（posix）。
 *
 * 注意：mingw 上 C 的 `long` 是 32 位，`ftell`/`fseek` 的签名与 Linux 不同，
 * 这里统一转成 `Long` 以屏蔽差异。
 */
@OptIn(ExperimentalForeignApi::class)
private fun readFileBytes(absPath: String): ByteArray? {
    val file = fopen(absPath, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val length = ftell(file).toLong()
        if (length <= 0L) return null
        rewind(file)
        val out = ByteArray(length.toInt())
        val readCount = out.usePinned { pinned ->
            fread(pinned.addressOf(0), 1uL, length.toULong(), file).toLong()
        }
        return if (readCount == length) out else null
    } finally {
        fclose(file)
    }
}
