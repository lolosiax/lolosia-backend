package moe.lolosia.web.service

import cn.hutool.captcha.CaptchaUtil
import cn.hutool.captcha.generator.MathGenerator
import cn.hutool.core.math.Calculator
import com.fasterxml.jackson.databind.json.JsonMapper
import moe.lolosia.web.event.system.UserLoginEvent
import moe.lolosia.web.event.system.UserLogoutEvent
import moe.lolosia.web.annotation.Service
import moe.lolosia.web.manager.CacheManager
import moe.lolosia.web.manager.SessionManager
import moe.lolosia.web.model.system.query.QSysUserEntity
import moe.lolosia.web.service.system.UserService
import moe.lolosia.web.util.bundle.*
import moe.lolosia.web.util.ebean.or
import moe.lolosia.web.util.ebean.query
import moe.lolosia.web.util.ebean.toAsync
import moe.lolosia.web.util.event.EventBus
import moe.lolosia.web.util.session.Context
import moe.lolosia.web.util.session.query
import moe.lolosia.web.util.success
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.awt.Color
import java.util.Base64

/**
 * 登录注册服务。
 * 邮箱验证流程本轮暂缓，注册为「用户名+密码+手机号/邮箱」直接创建。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-04-25 10:01
 */
@Service
class LoginService : KoinComponent {
    companion object {
        private val encoder = BCryptPasswordEncoder(10)
        private val mapper = JsonMapper()
    }


    private val session: SessionManager by inject()
    private val publisher: EventBus by inject()
    private val cacheManager: CacheManager by inject()
    private val userService: UserService by inject()

    suspend fun login(ctx: Context, userName: String, password: String?): Bundle {
        val user = ctx.query<QSysUserEntity> {
            or {
                this.userName.eq(userName)
                this.phone.eq(userName)
            }
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("用户不存在！")
        var firstLogin = false
        val stored = user.password
        val input = password
        if (stored.isNullOrEmpty() && input.isNullOrEmpty()) {
            firstLogin = true
        }
        // 判断密码是否正确
        else if (stored.isNullOrEmpty() || input == null || !encoder.matches(input, stored)) {
            throw IllegalAccessException("密码不正确")
        }
        val obj = session.get()
        obj["id"] = user.id.toString()

        session.save(obj["sessionId"]!!.toString())
        val out = bundleScope {
            current.putAll(mapper.toBundle(user))
            current.putAll(obj)
            "Authorization" set obj["sessionId"]
            "firstLogin" set firstLogin
            "password" set null
        }

        publisher.publish(UserLoginEvent(this, out, obj["sessionId"]!!.toString()))
        return out
    }

    @Suppress("NAME_SHADOWING")
    suspend fun logout(ctx: Context, sessionId: String? = null): Bundle {
        val sessionId = try {
            sessionId ?: ctx.sessionId.toString()
        } catch (_: Throwable) {
            return success("SessionId哪去了？")
        }
        val out: Bundle = if (session.contains(sessionId)) session[sessionId].toMutableMap()
        else bundleOf()
        session.remove(sessionId)
        publisher.publish(UserLogoutEvent(this, out, sessionId))
        return success()
    }

    private val captchaCache by lazy { cacheManager["/system/login/captcha"] }

    /** 图形验证码：返回 base64 图片 + 缓存id */
    suspend fun captcha(ctx: Context): Bundle {
        val captcha = CaptchaUtil.createLineCaptcha(100, 40)
        captcha.setBackground(Color.BLACK)
        captcha.generator = MathGenerator()
        captcha.createCode()
        val result = Calculator.conversion(captcha.code).toInt()

        val cache = captchaCache.create {
            this.tag = ctx.clientAddressString
            this.value = result.toString()
        }

        val image = Base64.getEncoder().encodeToString(captcha.imageBytes)
        return bundleScope {
            "image" set image
            "id" set cache.id
        }
    }

    /** 注册（简化版：无邮箱验证） */
    suspend fun register(
        ctx: Context,
        userName: String,
        password: String,
        email: String?,
        phone: String?,
    ): Bundle {
        val entity = userService.create(ctx, bundleScope {
            "userName" set userName
            "password" set password
            "email" set email
            "phone" set phone
            "roleId" set 4
        })
        return mapper.toBundle(entity)
    }
}
