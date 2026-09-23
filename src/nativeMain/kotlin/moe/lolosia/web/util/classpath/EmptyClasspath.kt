package moe.lolosia.web.util.classpath

/** 既没嵌入资源也没配回退目录时使用的空实现。 */
internal object EmptyClasspath : Classpath {
    override val entries: List<String> = emptyList()
    override fun read(path: String): ByteArray? = null
    override fun list(prefix: String): List<String> = emptyList()
    override fun exists(path: String): Boolean = false
}
