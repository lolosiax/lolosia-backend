package moe.lolosia.android.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.lolosia.web.dto.AnimeSeriesDto
import moe.lolosia.web.dto.ApiException
import moe.lolosia.web.dto.LoginResult
import moe.lolosia.web.dto.UserInfoRow
import moe.lolosia.web.ui.api.RemoteApi
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程 API 的 Android 实现。
 *
 * Android 侧没有 Ktor 的 native 产物，这里直接用 `HttpURLConnection` 还原 `:modules:core`
 * 的同一套契约：`{code,msg,data}` 信封、`Authorization: Bearer <sessionId>`、401/403 原样抛出。
 *
 * @param baseUrl 服务端根地址，末尾不带 `/`。
 * @author 洛洛希雅Lolosia
 */
internal class AndroidRemoteApi(private val baseUrl: String) : RemoteApi {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    override suspend fun login(userName: String, password: String): LoginResult {
        val body = buildJsonObject {
            put("userName", userName)
            put("password", password)
        }
        return decodeEnvelope(request("POST", "/api/login", null, body.toString().encodeToByteArray()))
    }

    override suspend fun myInfo(token: String): UserInfoRow =
        decodeEnvelope(request("POST", "/api/user/myInfo", token, null))

    override suspend fun avatar(userId: String, version: Long?): ByteArray? {
        val suffix = version?.let { "?t=$it" }.orEmpty()
        val response = request("GET", "/api/user/avatar/get/$userId$suffix", null, null)
        return response.bytes.takeIf { response.status in 200..299 && it.isNotEmpty() }
    }

    override suspend fun animeTimetable(token: String, weekday: Int?): List<AnimeSeriesDto> {
        val suffix = weekday?.let { "?weekday=$it" }.orEmpty()
        return decodeEnvelope(request("GET", "/api/anime/timetable$suffix", token, null))
    }

    override suspend fun animeCover(item: AnimeSeriesDto, size: Int): ByteArray? {
        val absolute = item.cover?.takeIf { it.startsWith("http") }
        val target = when {
            item.bangumiId != null -> "$baseUrl/res/anime-cover?id=${item.bangumiId}&size=$size"
            absolute != null -> absolute
            else -> return null
        }
        val response = request("GET", target, null, null)
        return response.bytes.takeIf { response.status in 200..299 && it.isNotEmpty() }
    }

    override suspend fun logout(token: String) {
        runCatching { request("POST", "/api/logout", token, null) }
    }

    private suspend fun request(
        method: String,
        pathOrUrl: String,
        token: String?,
        body: ByteArray?,
    ): Response = withContext(Dispatchers.IO) {
        val url = URL(if (pathOrUrl.startsWith("http")) pathOrUrl else baseUrl + pathOrUrl)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            Response(status, bytes)
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <reified T> decodeEnvelope(response: Response): T {
        if (response.status !in 200..299) {
            throw ApiException(response.status, envelopeMessage(response.bytes) ?: "请求失败")
        }
        val element = runCatching { json.parseToJsonElement(response.bytes.decodeToString()) }.getOrNull()
            ?: throw ApiException(502, "响应不是 JSON")
        val obj = element as? JsonObject ?: throw ApiException(502, "响应不是 JSON 对象")
        val code = obj["code"]?.jsonPrimitive?.intOrNull ?: 0
        val message = obj["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (code != 200) {
            throw ApiException(if (code in 400..599) code else 502, message.ifBlank { "请求失败" })
        }
        val data = obj["data"] ?: throw ApiException(502, "响应缺少 data")
        return json.decodeFromJsonElement(data)
    }

    private fun envelopeMessage(bytes: ByteArray): String? = runCatching {
        val obj = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: return@runCatching null
        obj["msg"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private class Response(val status: Int, val bytes: ByteArray)
}
