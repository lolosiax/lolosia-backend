package moe.lolosia.web.util.session

import io.ktor.server.application.ApplicationCall
import moe.lolosia.web.manager.SessionManager
import org.koin.core.context.GlobalContext
import java.util.*

/**
 * SSE 连接专用 Context。
 * 用于 SSE 会话：可指定 sessionId（登录后由 registerUser 绑定用户）。
 *
 * 注意：不用 secondary constructor 创建（Kotlin 2.4 K2 对抽象父类的隐式无参构造 +
 * 子类 secondary constructor 组合会报 "Supertype initialization is impossible"），
 * 统一走伴生工厂 [of]。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
open class SSEContext : Context() {

    private val sessionManager by lazy { GlobalContext.get().get<SessionManager>() }
    private val mSession by lazy { sessionManager[this.sessionId] }
    private val emptySession = SSESessionMap(mutableMapOf())
    private var mSessionId: UUID? = null

    /** SSE 连接没有对应的 HTTP 请求，业务代码不应访问 call */
    override val call: ApplicationCall
        get() = throw IllegalStateException("SSE 上下文没有 HTTP 请求")

    val hasSessionId get() = mSessionId != null

    override var sessionId: UUID
        get() = mSessionId ?: throw IllegalStateException("sessionId is null")
        set(value) {
            mSessionId = value
        }

    override val session: SessionMap
        get() {
            val out = mSessionId?.let { mSession } ?: emptySession
            out["session:lastAccess"] = Date().time
            return out
        }

    inner class SSESessionMap(val map: MutableMap<String, Any?>) : SessionMap, MutableMap<String, Any?> by map {
        override fun set(key: String, value: Any?) {
            map[key] = value
        }
    }

    companion object {
        /** 从 HTTP 请求的 Context 创建（复制其 sessionId） */
        fun of(ctx: Context): SSEContext = SSEContext().apply {
            try {
                mSessionId = ctx.sessionId
            } catch (_: Exception) {}
        }

        /** 指定 sessionId 创建 */
        fun of(sessionId: UUID): SSEContext = SSEContext().apply {
            mSessionId = sessionId
        }
    }
}
