package moe.lolosia.web.util.classpath

import kotlinx.cinterop.COpaquePointer

/** 各目标实现：取出可执行文件内嵌的 `classpath.zip`；未嵌入时返回 `null`。 */
internal expect fun embeddedClasspathImage(): EmbeddedClasspathImage?

/**
 * 两个映像符号之间的字节数（Linux 侧 `_binary_..._start/_end` 差值）。
 *
 * `CPointer.rawValue` 的类型随 Kotlin 版本变化，统一经 [rawAddress] 转成 `Long` 再计算。
 */
internal fun classpathImageSize(start: COpaquePointer, end: COpaquePointer): Int =
    (rawAddress(end) - rawAddress(start)).toInt()

/** 取指针的数值地址。 */
@Suppress("USELESS_CAST")
internal fun rawAddress(pointer: COpaquePointer): Long = pointer.rawValue.toLong()
