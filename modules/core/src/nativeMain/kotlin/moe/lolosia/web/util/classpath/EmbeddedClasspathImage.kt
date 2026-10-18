package moe.lolosia.web.util.classpath

import kotlinx.cinterop.COpaquePointer

/** 嵌入资源的映像：指向可执行文件内的数据 + 字节数。 */
internal class EmbeddedClasspathImage(val pointer: COpaquePointer, val size: Int)
