package moe.lolosia.local

import moe.lolosia.web.dto.LocalFrame

/**
 * 本地服务的传输层：只负责搬运帧，不解释方法。
 *
 * 进程内与 loopback WebSocket 各实现一份；dispatcher、鉴权、错误码都在传输之上。
 */
interface LocalTransport {

    /** 本连接是否已通过鉴权；进程内两端同属一个可信进程，构造时即视为已授权。 */
    val authorized: Boolean

    /** 读取下一帧；返回 `null` 表示连接已关闭。 */
    suspend fun receive(): LocalFrame?

    /** 发送一帧。 */
    suspend fun send(frame: LocalFrame)

    /** 关闭连接；重复调用无副作用。 */
    fun close()
}
