package top.lolosia.web.util.asm

private val classLoader by lazy { ASMClassloader() }

fun defineClass(name: String?, clazz: ByteArray): Class<*> {
    return classLoader.defineClass(name, clazz)
}

private class ASMClassloader : ClassLoader(ASMClassloader::class.java.classLoader) {
    fun defineClass(name: String?, data: ByteArray): Class<*> {
        return defineClass(name, data, 0, data.size)
    }
}