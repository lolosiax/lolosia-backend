package moe.lolosia.web.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * 登录相关 DTO。
 *
 * @author 洛洛希雅Lolosia
 */

/** 登录入参（前端 `login(subForm)` 传的是整个表单，多余字段被 `ignoreUnknownKeys` 忽略）。 */
@Serializable
public data class LoginReq(
    val userName: String,
    val password: String? = null,
)

/**
 * 登录结果：**用户信息 + 会话 id**（前端 `login()` 只在 `data.Authorization` 里取 token，
 * 之后按 `Authorization: Bearer <token>` 带回来）。
 *
 * `isUse` 是线上的 `1` / `0`，见 [UserRow]。
 */
@Serializable
public data class LoginResult(
    val id: String,
    val userName: String,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    @SerialName("Authorization") val authorization: String,
    /** 库里没密码且本次也没传密码（首次登录，旧版行为）。 */
    val firstLogin: Boolean = false,
)

/** 图形验证码出参（前端 `captcha()` 用 `image` 拼 data URI、`id` 原样回传）。 */
@Serializable
public data class CaptchaResult(
    /** base64 的 PNG（**不含** `data:image/png;base64,` 前缀，前端自己拼）。 */
    val image: String,
    /** 随机 token，答案按它落在 `sys_cache` 里。 */
    val id: String,
)

/**
 * 人机校验入参（前端 `verify(captchaId, captchaValue, userName, email)`）。
 *
 * `userName` / `email` 是注册表单第一步收集的，本轮只校验图形验证码，故不参与判断
 * （见 `doc/开发记忆.md` 决策记录）。
 */
@Serializable
public data class VerifyReq(
    @Serializable(with = CaptchaIdSerializer::class) val id: String,
    val code: String,
    val userName: String? = null,
    val email: String? = null,
)

/**
 * 注册入参（前端 `register(userName, email, code, password)`）。
 *
 * `code` 是**邮箱验证码**（前端把上一步的 `emailVerify` 传过来），本轮不做邮箱验证，忽略之。
 * 前端不传 `phone`，但保留字段以便非前端调用方用手机号注册。
 */
@Serializable
public data class RegisterReq(
    val userName: String,
    val password: String,
    val email: String? = null,
    val phone: String? = null,
    val code: String? = null,
)

/**
 * 验证码 id 的反序列化：前端 TS 里把它标注成 `number`（未加载验证码时是 `0`），
 * 实际是服务端发的随机 token → 数字、字符串都收下，统一当字符串用。
 */
public object CaptchaIdSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("moe.lolosia.web.dto.CaptchaId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return json.decodeJsonElement().jsonPrimitive.content
    }
}
