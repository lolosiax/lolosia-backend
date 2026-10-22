package moe.lolosia.local

import kotlinx.coroutines.channels.Channel
import moe.lolosia.web.dto.LocalFrame

/**
 * 进程内传输：两条 Channel 充当双向管道，不建端口、不写发现文件。
 *
 * 两端仍按「收帧 → 分发 → 回帧」的流程工作，和 loopback 传输走同一套 dispatcher，
 * 因此 `--embed` 与分离部署的行为一致。
 */
class InProcessTransport private constructor(
    private val incoming: Channel<LocalFrame>,
    private val outgoing: Channel<LocalFrame>,
) : LocalTransport {

    override val authorized: Boolean = true

    override suspend fun receive(): LocalFrame? = incoming.receiveCatching().getOrNull()

    override suspend fun send(frame: LocalFrame) {
        outgoing.send(frame)
    }

    override fun close() {
        incoming.close()
        outgoing.close()
    }

    companion object {
        /**
         * 创建一对相连的传输端。
         *
         * 返回值的第一个用于服务端，第二个用于客户端。
         */
        fun create(): Pair<LocalTransport, LocalTransport> {
            val toServer = Channel<LocalFrame>(Channel.BUFFERED)
            val toClient = Channel<LocalFrame>(Channel.BUFFERED)
            return InProcessTransport(toServer, toClient) to InProcessTransport(toClient, toServer)
        }
    }
}
