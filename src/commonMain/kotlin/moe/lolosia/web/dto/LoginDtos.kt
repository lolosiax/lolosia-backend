package moe.lolosia.web.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
