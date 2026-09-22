package moe.lolosia.web.service

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import moe.lolosia.web.ApiException
import moe.lolosia.web.db.Database
import moe.lolosia.web.db.SysUserRepository
import moe.lolosia.web.dto.LoginResult
import moe.lolosia.web.util.crypto.BCrypt
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.time.toDisplayString

/**
 * 登录服务（迁移自旧 JVM 版 `LoginService`，暂不含图形验证码 / 注册，见 `doc/开发记忆.md` 待办）。
 *
 * 与旧版的差异：
 * - token 仍是会话 id（**不是 JWT**），与旧版一致：`Authorization: Bearer <sessionId>`；
 * - 旧版把「会话内容 + 用户全字段 + `Authorization` + `firstLogin`」揉成一个 Bundle 返回，
 *   这里用强类型 [LoginResult]（字段一一对应）；
 * - 登录失败回 `400`（旧版抛 `NoSuchElementException` / `IllegalAccessException`，被兜底成 `500`）——
 *   前端只读 `msg` 展示，状态码取语义更准的那个。
 *
 * 「邮箱验证流程本轮暂缓」与旧版注释一致：注册是「用户名 + 密码 + 手机号/邮箱」直接建号。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
public class LoginService(
    private val db: Database,
    private val users: SysUserRepository,
    private val sessions: SessionManager,
) {

    private val ctx: QueryExecutor get() = db.driver

    /**
     * 登录：账号可以是用户名或手机号。
     *
     * 密码语义与旧版一致：库里没密码 **且** 本次也没传密码 → `firstLogin = true` 放行；
     * 库里没密码但传了密码、或密码不匹配 → 「密码不正确」。
     */
    public suspend fun login(userName: String, password: String?): LoginResult {
        val user = users.findOneByAccount(ctx, userName).getOrThrow()
            ?: throw ApiException(400, "用户不存在！")

        val stored = user.password
        val firstLogin = stored.isNullOrEmpty() && password.isNullOrEmpty()
        if (!firstLogin && (stored.isNullOrEmpty() || password == null || !BCrypt.checkpw(password, stored))) {
            throw ApiException(400, "密码不正确")
        }

        val session = sessions.create()
        session.set(SessionMap.KEY_USER_ID, user.id)
        sessions.save(session)

        return LoginResult(
            id = user.id,
            userName = user.userName,
            realName = user.realName,
            phone = user.phone,
            email = user.email,
            avatar = user.avatar,
            isUse = if (user.isUse == true) 1 else 0,
            createdAt = user.createdAt?.toDisplayString(),
            updatedAt = user.updatedAt?.toDisplayString(),
            authorization = session.id,
            firstLogin = firstLogin,
        )
    }

    /**
     * 登出：删掉会话（内存 + 库）。没有会话 id 时与旧版一致地返回提示语，
     * 而不是报错 —— `/api/logout` 在白名单之外，正常都带会话。
     */
    public suspend fun logout(sessionId: String?): String {
        if (sessionId == null) return "SessionId哪去了？"
        sessions.remove(sessionId)
        return "success"
    }
}
