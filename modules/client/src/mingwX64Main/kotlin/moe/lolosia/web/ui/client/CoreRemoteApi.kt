package moe.lolosia.web.ui.client

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*
import moe.lolosia.web.api.get
import moe.lolosia.web.api.post
import moe.lolosia.web.api.requestBytes
import moe.lolosia.web.dto.AnimeSeriesDto
import moe.lolosia.web.dto.LoginResult
import moe.lolosia.web.dto.UserInfoRow
import moe.lolosia.web.ui.api.RemoteApi

/**
 * 远程 API 的 Windows 实现：复用 `:modules:core` 的 Ktor 客户端与信封解包。
 *
 * @param baseUrl 服务端根地址，末尾不带 `/`。
 * @author 洛洛希雅Lolosia
 */
class CoreRemoteApi(private val baseUrl: String) : RemoteApi {

    override suspend fun login(userName: String, password: String): LoginResult =
        post(baseUrl, "/api/login", buildJsonObject {
            put("userName", userName)
            put("password", password)
        })

    override suspend fun myInfo(token: String): UserInfoRow =
        post(baseUrl, "/api/user/myInfo", sessionId = token)

    override suspend fun avatar(userId: String, version: Long?): ByteArray? {
        val suffix = version?.let { "?t=$it" }.orEmpty()
        val (status, bytes) = requestBytes(
            HttpMethod.Get,
            baseUrl,
            "/api/user/avatar/get/$userId$suffix",
        )
        return if (status in 200..299) bytes else null
    }

    override suspend fun animeTimetable(token: String, weekday: Int?): List<AnimeSeriesDto> =
        get(
            baseUrl,
            "/api/anime/timetable",
            query = weekday?.let { mapOf("weekday" to listOf(it.toString())) },
            sessionId = token,
        )

    override suspend fun logout(token: String) {
        post<Unit>(baseUrl, "/api/logout", sessionId = token)
    }
}
