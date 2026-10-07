package moe.lolosia.web.service

import moe.lolosia.web.database.repository.SysUserRepository
import moe.lolosia.web.dto.*
import moe.lolosia.web.service.system.UserService
import moe.lolosia.web.util.captcha.createMathCaptcha
import moe.lolosia.web.util.crypto.BCrypt
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.time.toDisplayString
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * 登录 / 登出 / 图形验证码 / 注册。
 *
 * 与旧版的差异：
 * - token 仍是会话 id（**不是 JWT**），与旧版一致：`Authorization: Bearer <sessionId>`；
 * - 旧版把「会话内容 + 用户全字段 + `Authorization` + `firstLogin`」揉成一个 Bundle 返回，
 *   这里用强类型 [LoginResult]（字段一一对应）；
 * - 登录失败回 `400`（旧版抛 `NoSuchElementException` / `IllegalAccessException`，被兜底成 `500`）——
 *   前端只读 `msg` 展示，状态码取语义更准的那个；
 * - 验证码是**自研**的（点阵字 + 手写 PNG，见 `util/captcha/`），旧版走 hutool（依赖 JDK 的 AWT/ImageIO）；
 * - 旧版拿**客户端 IP** 当验证码的缓存 tag，这里用**随机 token**（用户拍板）：多端共用同一个出口 IP 时
 *   不会互相覆盖，也经得起反代；
 * - 旧版没有 `/api/verify`（只在前端与鉴权白名单里出现），这里补上：**只校验图形验证码**；
 * - 注册的重复用户名 / 缺手机邮箱回 `400`（旧版是 `IllegalStateException` 兜底成 `500`）。
 *
 * 「邮箱验证流程本轮暂缓」与旧版注释一致：注册是「用户名 + 密码 + 手机号/邮箱」直接建号，
 * 前端多传的 `code`（邮箱验证码）被忽略。
 *
 * [ctx] 是请求上下文，**首参透传**：语句执行句柄从它取（`ctx.db`），本层不持有连接池。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class LoginService(
    private val users: SysUserRepository,
    private val sessions: SessionManager,
    private val cache: CacheManager,
    private val userService: UserService,
) {

    /** 图形验证码的缓存分组（沿用旧版的 path，答案按随机 token 落在这里）。 */
    private val captchaCache: CacheManager.Accessor by lazy { cache[CAPTCHA_PATH] }

    /**
     * 登录：账号可以是用户名或手机号。
     *
     * 密码语义与旧版一致：库里没密码 **且** 本次也没传密码 → `firstLogin = true` 放行；
     * 库里没密码但传了密码、或密码不匹配 → 「密码不正确」。
     */
    suspend fun login(ctx: Context, userName: String, password: String?): LoginResult {
        val user = users.findOneByAccount(ctx.db, userName).getOrThrow()
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
    suspend fun logout(ctx: Context): MessageResult {
        val sessionId = ctx.sessionId ?: return MessageResult("SessionId哪去了？")
        sessions.remove(sessionId)
        return MessageResult("success")
    }

    /**
     * 生成图形验证码：算式图（PNG → base64）+ 随机 token，答案按 token 落 [CAPTCHA_PATH]。
     *
     * TTL 取 [CAPTCHA_TTL]（旧版用的是缓存组的默认值，对验证码太长；这里显式收紧）。
     */
    suspend fun captcha(ctx: Context): CaptchaResult {
        val generated = createMathCaptcha()
        val id = Uuid.random().toString()
        captchaCache.put(id, generated.answer.toString(), CAPTCHA_TTL)
        return CaptchaResult(image = Base64.encode(generated.png), id = id)
    }

    /**
     * 人机校验：**只校验图形验证码**（用户拍板；前端传的 userName / email 本轮不判断）。
     *
     * 一次性消费：无论对错都把该 token 的答案删掉，避免同一个验证码被拿去爆破。
     * 前端错一次后会拿到「已过期」，点一下图刷新即可。
     */
    suspend fun verify(ctx: Context, id: String, code: String): MessageResult {
        val expected = captchaCache.get(id) ?: throw ApiException(400, "验证码已过期，请点击图片刷新")
        captchaCache.remove(id)
        if (expected != code.trim()) throw ApiException(400, "验证码错误，请点击图片刷新")
        return MessageResult("success")
    }

    /**
     * 注册（与 master 分支的 `register` 一致）：用户名 + 密码 + 手机号/邮箱直接建号，
     * 角色固定为「学生」（[ROLE_ID_STUDENT]）。**不做邮箱验证**。
     *
     * 建号本身由 [UserService.create] 完成（密码在其中 bcrypt(cost=10) 入库）；
     * 它的 `IllegalArgumentException` / `IllegalStateException` 都是用户输入问题（缺手机邮箱、同名用户已存在），
     * 转成 `400`（旧版是兜底 `500`）。
     */
    suspend fun register(
        ctx: Context,
        userName: String,
        password: String,
        email: String?,
        phone: String?,
    ): UserListRow {
        val req = UserCreateReq(
            userName = userName,
            password = password,
            email = email,
            phone = phone,
            roleId = ROLE_ID_STUDENT,
        )
        return try {
            userService.create(ctx, req)
        } catch (e: IllegalArgumentException) {
            throw ApiException(400, e.message ?: "注册信息不完整")
        } catch (e: IllegalStateException) {
            throw ApiException(400, e.message ?: "注册失败")
        }
    }

    companion object {
        /** 图形验证码的缓存分组（与旧版一致）。 */
        const val CAPTCHA_PATH: String = "/system/login/captcha"

        /** 验证码有效期。 */
        val CAPTCHA_TTL: Duration = 5.minutes
    }
}
