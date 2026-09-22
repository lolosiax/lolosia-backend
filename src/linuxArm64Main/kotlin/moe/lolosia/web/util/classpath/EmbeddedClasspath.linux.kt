package moe.lolosia.web.util.classpath

import platform.posix.dlsym

/**
 * Linux：从可执行文件自身的符号表取 `classpath.zip` 的起止地址。
 *
 * 构建期用 `objcopy -I binary` 把 zip 转成目标文件后链接进程序，产生三个全局符号
 * （名字由输入文件名推导）：`_binary_classpath_zip_start` / `_end` / `_size`；
 * 需要链接参数 `-Wl,--export-dynamic` 才会进入动态符号表，`dlsym` 才能查到。
 *
 * 与 Windows 的 `LockResource` 一样，这里拿到的是映像内的地址（零拷贝）。
 */
internal actual fun embeddedClasspathImage(): EmbeddedClasspathImage? {
    val start = dlsym(null, SYMBOL_START) ?: return null
    val end = dlsym(null, SYMBOL_END) ?: return null
    val size = classpathImageSize(start, end)
    if (size <= 0) return null
    return EmbeddedClasspathImage(start, size)
}

private const val SYMBOL_START = "_binary_classpath_zip_start"
private const val SYMBOL_END = "_binary_classpath_zip_end"
