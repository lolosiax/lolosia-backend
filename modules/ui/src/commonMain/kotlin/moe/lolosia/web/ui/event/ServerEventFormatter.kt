package moe.lolosia.web.ui.event

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一条准备展示给用户的服务器推送（平台只负责用通知栏/横幅呈现）。 */
data class ServerPushMessage(val title: String, val text: String)

/**
 * 把服务端事件翻译成用户可读的标题/正文。
 *
 * 业务格式在这里统一，平台侧只做渲染：
 * - `anime.release` → 「番名 更新」/「第 N 话 · 发布标题」；
 * - 其它事件 → 事件名 + 原始数据（截断）。
 * - `status`（建流首帧）与 `close` 不展示。
 *
 * @author 洛洛希雅Lolosia
 */
object ServerEventFormatter {

    private val json = Json { ignoreUnknownKeys = true }

    fun format(event: String, data: String): ServerPushMessage? {
        if (event == "status" || event == "close") return null
        if (event != "anime.release") {
            return ServerPushMessage(event, data.take(MAX_RAW_LENGTH))
        }

        val payload = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
        val series = payload?.get("seriesTitle")?.jsonPrimitive?.contentOrNull.orEmpty()
        val episode = payload?.get("episodeNo")?.jsonPrimitive?.intOrNull
        val itemTitle = payload?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
        val title = if (series.isBlank()) "番组更新" else "$series 更新"
        val text = buildString {
            if (episode != null) append("第 ").append(episode).append(" 话 · ")
            append(itemTitle.ifBlank { "有新发布" })
        }
        return ServerPushMessage(title, text)
    }

    private const val MAX_RAW_LENGTH = 180
}
