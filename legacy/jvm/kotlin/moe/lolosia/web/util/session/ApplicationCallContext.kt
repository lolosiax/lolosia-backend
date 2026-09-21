package moe.lolosia.web.util.session

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import moe.lolosia.web.manager.SessionManager
import moe.lolosia.web.util.ErrorResponseException
import moe.lolosia.web.util.ebean.toUuid
import org.koin.core.context.GlobalContext
import java.util.*

/**
 * 基于请求的 Context。
 * 由 ctx 绑定中间件按请求创建；session 从 Authorization 头懒解析，
 * 未登录请求访问 session 时抛 401（登录/验证码等匿名接口不会访问 session）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
class ApplicationCallContext(override val call: ApplicationCall) : Context() {

    private val sessionManager by lazy { GlobalContext.get().get<SessionManager>() }
    private val mSession: SessionMap by lazy {
        sessionManager.mySession(call.request)
            ?: throw ErrorResponseException(HttpStatusCode.Unauthorized, "请重新登录")
    }

    override val sessionId: UUID by lazy { session["sessionId"]!!.toString().toUuid() }

    override val session: SessionMap
        get() {
            mSession["session:lastAccess"] = Date().time
            return mSession
        }
}
