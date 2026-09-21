package moe.lolosia.web.controller

import kotlinx.serialization.Serializable
import moe.lolosia.web.util.classpath.classpath
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.GET
import moe.lolosia.web.util.router.Query

/**
 * classpath 访问演示：验证「像 Java 打资源文件那样」读嵌入式资源。
 *
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class ClasspathList(val prefix: String, val count: Int, val entries: List<String>)

@Serializable
data class ClasspathFile(val path: String, val exists: Boolean, val size: Int, val text: String?)

@Controller("/api")
object ClasspathController {

    /** 列出资源条目（可用 prefix 过滤） */
    @GET("/classpath/list")
    fun list(@Query prefix: String?): ClasspathList {
        val items = classpath.list(prefix ?: "")
        return ClasspathList(prefix = prefix ?: "", count = items.size, entries = items)
    }

    /** 读取一个资源：`/api/classpath/read?path=hello.txt` */
    @GET("/classpath/read")
    fun read(@Query path: String): ClasspathFile {
        val bytes = classpath.read(path)
        return ClasspathFile(
            path = path,
            exists = bytes != null,
            size = bytes?.size ?: 0,
            text = bytes?.decodeToString()?.take(500),
        )
    }
}
