package top.lolosia.web.config

import top.lolosia.web.util.bundle.Bundle
import org.yaml.snakeyaml.Yaml
import top.lolosia.web.LolosiaApplication
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

object SConfig : ChangeableMap() {
    public override val data by lazy { init() }

    /**
     * 服务器启动端口
     */
    val serverPort: Int by this(8002)

    private fun init(): Bundle {
        if (!Path("config.yml").exists()) {
            releaseConfigFile()
        }

        return Yaml().load(Path("config.yml").readText()) as Bundle
    }

    private fun releaseConfigFile() {
        val clazz = LolosiaApplication::class.java
        val classpath = clazz.packageName.replace(".", "/")
        val stream = clazz.classLoader.getResourceAsStream("${classpath}/config.yml")!!
        val file = File("config.yml")
        file.outputStream().use {
            stream.transferTo(it)
        }
    }
}