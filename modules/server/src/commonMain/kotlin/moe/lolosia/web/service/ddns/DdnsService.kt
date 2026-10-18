package moe.lolosia.web.service.ddns

import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import moe.lolosia.web.api.get
import moe.lolosia.web.api.post
import moe.lolosia.web.api.put
import moe.lolosia.web.api.requireSuccess
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Service
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Cloudflare DDNS：把 [AppSettings.DdnsConfig.interfaceName] 上的全局 IPv6 同步到
 * [AppSettings.DdnsConfig.recordName] 的 AAAA 记录。
 *
 * eth0 直连光猫时，光猫不响应 Router Solicitation，IPv6 要等它周期性的 RA 才出现，且前缀会随
 * 上游变化，因此按 [AppSettings.DdnsConfig.intervalSeconds] 轮询，仅在本机地址与线上记录
 * 不一致时才写入。地址取自 `/proc/net/if_inet6`，请求经 [moe.lolosia.web.api.request] 发出。
 */
@Service
class DdnsService(setting: AppSettings) {

    val config = setting.ddns

    private val log = Log.of("DDNS")

    /** 挂到应用作用域上按间隔轮询；配置不全或未启用时只记一条日志。 */
    fun start(scope: CoroutineScope) {
        if (!config.enabled) {
            log.info { "未启用（ddns.enabled=false）" }
            return
        }
        if (config.zoneId.isBlank() || config.apiToken.isBlank() || config.recordName.isBlank()) {
            log.warn { "已启用但 zoneId / apiToken / recordName 存在空值，已跳过" }
            return
        }
        val interval = config.intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS)
        log.info {
            "已启动：record=${config.recordName}, iface=${config.interfaceName}, " +
                "interval=${interval}s, proxied=${config.proxied}"
        }
        scope.launch {
            while (isActive) {
                runCatching { syncOnce() }.onFailure { log.error(it) { "本轮同步失败" } }
                delay(interval * 1000L)
            }
        }
    }

    /** 一轮：取本机地址 → 查 CF 现存记录 → 不同则更新，没有则创建。 */
    private suspend fun syncOnce() {
        val ip = currentIpv6()
        if (ip == null) {
            log.warn { "${config.interfaceName} 暂无全局 IPv6（可能在等光猫 RA），跳过本轮" }
            return
        }

        val record = fetchRecord()
        if (record == null) {
            cloudflare(HttpMethod.Post, recordsPath(), recordBody(ip))
            log.info { "已创建 AAAA ${config.recordName} -> $ip" }
            return
        }

        val id = (record["id"] as? JsonPrimitive)?.contentOrNull
        if (id.isNullOrBlank()) throw IllegalStateException("CF 返回的记录缺少 id：$record")
        val old = (record["content"] as? JsonPrimitive)?.contentOrNull
        val proxied = (record["proxied"] as? JsonPrimitive)?.booleanOrNull
        if (canonicalIpv6(old.orEmpty()) == ip && proxied == config.proxied) {
            log.debug { "${config.recordName} 已是 $ip，无需更新" }
            return
        }

        cloudflare(HttpMethod.Put, "${recordsPath()}/$id", recordBody(ip))
        log.info { "已更新 AAAA ${config.recordName}：$old -> $ip（proxied=$proxied）" }
    }

    /** 取网卡上第一个可用的全局 IPv6；没有则返回 `null`。 */
    private fun currentIpv6(): String? {
        val text = runCatching { FileSystem.SYSTEM.read(IF_INET6.toPath()) { readUtf8() } }.getOrNull()
            ?: return null
        for (line in text.lineSequence()) {
            val parts = line.trim().split(WHITESPACE)
            if (parts.size < 6 || parts[5] != config.interfaceName) continue
            // 字段：地址(32 hex) 网卡序号 前缀长 scope flags 网卡名
            if ((parts[3].toIntOrNull(16) ?: continue) != SCOPE_GLOBAL) continue
            val flags = parts[4].toIntOrNull(16) ?: 0
            if (flags and EXCLUDED_FLAGS != 0) continue
            val hex = parts[0]
            if (hex.length != 32) continue
            return hexGroupsToText(hex)
        }
        return null
    }

    /** 把 32 位 hex 拼成 8 组、去掉每组前导零的形式（`044a` -> `44a`）。 */
    private fun hexGroupsToText(hex: String): String =
        (0 until 8).joinToString(":") { hex.substring(it * 4, it * 4 + 4).trimStart('0').ifEmpty { "0" } }

    /**
     * 把任意写法的 IPv6（含 `::` 缩写、大小写）规范成 [hexGroupsToText] 那种形式。
     *
     * Cloudflare 会把地址规范化后再存（例如 `044a` 存成 `44a`、零串缩成 `::`），
     * 直接拿字符串和本地读到的值比较会永远不相等，于是每轮都白白 PUT 一次。
     */
    private fun canonicalIpv6(text: String): String? {
        val halves = text.split("::")
        if (halves.size > 2) return null
        val head = parseGroups(halves[0]) ?: return null
        if (halves.size == 1) return if (head.size == 8) head.joinToString(":") else null
        val tail = parseGroups(halves[1]) ?: return null
        val gap = 8 - head.size - tail.size
        if (gap < 1) return null
        return (head + List(gap) { "0" } + tail).joinToString(":")
    }

    /** 解析一段冒号分隔的组；空串（`::` 的一侧）视为 0 组，非法返回 `null`。 */
    private fun parseGroups(part: String): List<String>? =
        if (part.isEmpty()) emptyList()
        else part.split(":").map { it.toIntOrNull(16)?.toString(16) ?: return null }

    /** 查 AAAA 记录；查不到记录（`result` 为空）返回 `null`，请求失败则抛异常。 */
    private suspend fun fetchRecord(): JsonObject? {
        val path = "${recordsPath()}?type=AAAA&name=${config.recordName}"
        val result = cloudflare(HttpMethod.Get, path)["result"] as? JsonArray ?: return null
        return result.firstOrNull() as? JsonObject
    }

    /** AAAA 记录的请求体。 */
    private fun recordBody(ip: String): JsonObject = buildJsonObject {
        put("type", "AAAA")
        put("name", config.recordName)
        put("content", ip)
        put("ttl", config.ttl)
        put("proxied", config.proxied)
    }

    /**
     * 调一次 Cloudflare API，并校验其 `success` 信封。
     *
     * CF 的响应不是本项目 [moe.lolosia.web.dto.ApiResult] 那一套，故一律以 `rawResponse = true` 取原始 JSON。
     */
    private suspend fun cloudflare(method: HttpMethod, path: String, body: JsonElement? = null): JsonObject {
        val headers = authHeaders()
        return when (method) {
            HttpMethod.Get -> get<JsonElement>(API_BASE, path, rawResponse = true, headers = headers)
            HttpMethod.Post -> post<JsonElement>(API_BASE, path, body, rawResponse = true, headers = headers)
            HttpMethod.Put -> put<JsonElement>(API_BASE, path, body, rawResponse = true, headers = headers)
            else -> throw IllegalArgumentException("Cloudflare 接口不支持 $method")
        }.requireSuccess()
    }

    private fun recordsPath(): String = "/zones/${config.zoneId}/dns_records"

    /** 鉴权请求头。 */
    private fun authHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer ${config.apiToken}")

    private companion object {
        const val API_BASE = "https://api.cloudflare.com/client/v4"
        const val IF_INET6 = "/proc/net/if_inet6"
        const val MIN_INTERVAL_SECONDS = 30

        /** `/proc` scope 字段：0 = 全局。 */
        const val SCOPE_GLOBAL = 0

        /** 临时地址(RFC4941) / tentative / DAD 失败 / 已弃用，这些都不要。 */
        const val EXCLUDED_FLAGS = 0x01 or 0x40 or 0x08 or 0x20

        val WHITESPACE = Regex("\\s+")
    }
}
