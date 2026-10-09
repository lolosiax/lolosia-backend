package moe.lolosia.web.util.classpath

/**
 * 可执行文件内的 classpath（资源访问统一入口）。
 *
 * 资源在构建期打包成 `classpath.zip` 并嵌入二进制，运行期经本接口访问；
 * 路径即 zip 内的相对路径，与打包前的资源目录结构一致。
 *
 * 路径约定：
 * - 以 zip 根为基准，如 `static/home/index.html`、`sql/sys_user.sql`；
 * - 允许前导 `/`（自动去掉），允许用 `\` 分隔；
 * - 不支持 `..` 上跳。
 *
 * @author 洛洛希雅Lolosia
 */
interface Classpath {

    /** 全部条目路径（`/` 分隔、无前导斜杠），已排序。 */
    val entries: List<String>

    /** 读取条目；不存在返回 `null`。 */
    fun read(path: String): ByteArray?

    /** 列表查询：返回以 [prefix] 开头的条目路径。 */
    fun list(prefix: String = ""): List<String>

    /** 是否存在该条目。 */
    fun exists(path: String): Boolean = read(path) != null

    /**
     * 条目的最后修改时间（epoch 毫秒）；实现给不出时返回 `null`（调用方就不下发 `Last-Modified`）。
     *
     * **内嵌条目没有各自的时间戳，用进程启动时间兜底**：重启 = 换了一份二进制，所有条目的
     * 时间戳一起前进，客户端手上的 `If-Modified-Since` / `If-None-Match` 立刻失效，拿到的一定是
     * 新二进制里的内容。否则重新部署后浏览器会拿缓存的旧 `index.html` 去请求已被删掉的 chunk
     * （前端产物名带内容哈希），页面直接白屏，手机上又没法强制刷新。
     *
     * 磁盘实现（开发期回退、`work/resources`）一律用文件真实 mtime。
     */
    fun lastModified(path: String): Long? = null

    /** 来源标识（URI scheme）：`file`（磁盘回退）/ `classpath`（内嵌 zip）/ `empty`。 */
    val scheme: String

    /** 供日志展示的来源描述；默认 `"$scheme://"`，磁盘回退实现会带上具体目录。 */
    val origin: String get() = "$scheme://"
}
