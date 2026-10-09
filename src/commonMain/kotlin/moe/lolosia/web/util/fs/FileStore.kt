package moe.lolosia.web.util.fs

import moe.lolosia.web.util.router.Injectable

/**
 * 文件存储：读写**运行时数据**（头像、附件之类），根目录由实现决定。
 *
 * 与 `Classpath` 的分工：classpath 是**只读**的发布资源（打进二进制或随程序分发），
 * 这里是可写的运行期文件。接口放在 commonMain、实现放在 nativeMain，是因为文件系统访问
 * 要走平台 posix 调用（见 `PosixFileStore`）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
interface FileStore {

    /** 相对路径（以 `/` 分隔，根为存储根目录）。 */
    fun read(path: String): ByteArray?

    /** 覆盖写入；父目录不存在时逐级创建。写入失败返回 `false`。 */
    fun write(path: String, bytes: ByteArray): Boolean

    /**
     * 文件最后修改时间（epoch 毫秒）；不存在或取不到返回 `null`。
     *
     * 给 `FileStoreClasspath` 下发 `Last-Modified` 用：外部资源用**文件真实时间**，
     * 与内嵌 classpath 统一用进程启动时间不同（见 `Classpath.lastModified`）。
     */
    fun lastModified(path: String): Long?
}
