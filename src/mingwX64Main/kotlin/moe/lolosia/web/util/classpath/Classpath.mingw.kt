package moe.lolosia.web.util.classpath

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.windows.FindResourceW
import platform.windows.LoadResource
import platform.windows.LockResource
import platform.windows.SizeofResource

/**
 * Windows：从 PE 资源段取 `classpath.zip`。
 *
 * 资源在 `.rc` 中声明为自定义类型（见 `classpath/mingw/classpath.rc`）：
 * ```
 * IDR_CLASSPATH  CLASSPATH  "classpath.zip"
 * ```
 * `LockResource` 返回的指针直接指向映像内的数据（**零拷贝**），无需释放。
 *
 * @author 洛洛希雅Lolosia
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun embeddedClasspathImage(): EmbeddedClasspathImage? {
    val hRes = FindResourceW(null, RESOURCE_NAME, RESOURCE_TYPE) ?: return null
    val size = SizeofResource(null, hRes)
    if (size == 0u) return null
    val hGlobal = LoadResource(null, hRes) ?: return null
    val pointer = LockResource(hGlobal) ?: return null
    return EmbeddedClasspathImage(pointer, size.toInt())
}

private const val RESOURCE_NAME = "IDR_CLASSPATH"
private const val RESOURCE_TYPE = "CLASSPATH"
