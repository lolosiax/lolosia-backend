package moe.lolosia.web.ui.api

import moe.lolosia.web.dto.AnimeSeriesDto
import moe.lolosia.web.dto.LoginResult
import moe.lolosia.web.dto.UserInfoRow

/**
 * 远程服务端能力。
 *
 * 只定义 UI 需要的接口；实现按平台注入（Windows 复用 `:modules:core` 的 Ktor 客户端）。
 * 所有 JSON 接口都按 `{code,msg,data}` 信封解包，失败抛 `ApiException`。
 *
 * @author 洛洛希雅Lolosia
 */
interface RemoteApi {

    /** 用户名 + 密码登录；返回会话 id 与用户信息。 */
    suspend fun login(userName: String, password: String): LoginResult

    /** 当前登录用户详情。 */
    suspend fun myInfo(token: String): UserInfoRow

    /** 取头像字节；没有头像或取件失败返回 `null`。 */
    suspend fun avatar(userId: String, version: Long? = null): ByteArray?

    /** 番组表；[weekday] 为 1..7，`null` 表示不过滤。 */
    suspend fun animeTimetable(token: String, weekday: Int? = null): List<AnimeSeriesDto>

    /** 番剧封面缩略图；没有封面或取件失败返回 `null`。 */
    suspend fun animeCover(item: AnimeSeriesDto, size: Int = 128): ByteArray?

    /** 登出；失败不抛给页面。 */
    suspend fun logout(token: String)
}
