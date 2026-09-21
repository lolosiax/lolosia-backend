package moe.lolosia.web.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.classpath.readText
import moe.lolosia.web.util.log.Log
import okio.FileSystem
import okio.Path.Companion.toPath

/** 磁盘配置文件名（相对启动目录；systemd 下即 `/srv/<项目>/application.json`）。 */
public const val CONFIG_FILE: String = "application.json"

/** 覆盖磁盘配置文件路径的环境变量。 */
public const val ENV_CONFIG_FILE: String = "KTOR_CONFIG"

/** classpath 内嵌的默认配置文件名（与其它资源走同一套机制，见 `util/classpath`）。 */
private const val CONFIG_RESOURCE: String = "application.json"

/** 环境变量名 → 生效配置项：`KTOR_HOST` / `KTOR_PORT` / `KTOR_LOG_LEVEL` / `KTOR_DB_URL` / `KTOR_DB_USERNAME` / `KTOR_DB_PASSWORD`。 */
private const val ENV_HOST: String = "KTOR_HOST"
private const val ENV_PORT: String = "KTOR_PORT"
private const val ENV_LOG_LEVEL: String = "KTOR_LOG_LEVEL"
private const val ENV_DB_URL: String = "KTOR_DB_URL"
private const val ENV_DB_USERNAME: String = "KTOR_DB_USERNAME"
private const val ENV_DB_PASSWORD: String = "KTOR_DB_PASSWORD"

private val configJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 加载配置，优先级由低到高：
 *
 * 1. [AppConfig] 数据类里写的默认值
 * 2. classpath 内嵌的 `application.json`（仓库里的开发默认值，随二进制分发）
 * 3. 磁盘上的 `application.json`（[diskPath]；服务器上改这里即可，不必重编二进制）
 * 4. 环境变量（见上方常量；未设置的不参与合并）
 *
 * 每一层都是 JSON 对象，按字段**深合并**（对象递归合并、标量整体替换）。
 * 命令行参数的优先级最高，由 `startServer` 处理（只覆盖端口）。
 *
 * @param env 环境变量读取函数（平台相关，由启动流程注入）；未设置须返回 `null`。
 */
public fun loadAppConfig(classpath: Classpath, diskPath: String?, env: (String) -> String?): AppConfig {
    val defaults: JsonObject = configJson.encodeToJsonElement(AppConfig.serializer(), AppConfig()).jsonObject
    val embedded = classpath.readText(CONFIG_RESOURCE)?.let { parseJsonObject(it, "classpath 内嵌 $CONFIG_RESOURCE") }
    val disk = diskPath?.let(::readConfigFile)?.let { parseJsonObject(it, diskPath) }
    val merged = defaults.overlay(embedded).overlay(disk).overlay(envOverrides(env))
    return configJson.decodeFromJsonElement(AppConfig.serializer(), merged)
}

/** 环境变量层：设了才覆盖，没设就完全不动下层。 */
private fun envOverrides(env: (String) -> String?): JsonObject {
    val server = buildJsonObject {
        env(ENV_HOST)?.let { put("host", it) }
        env(ENV_PORT)?.toIntOrNull()?.let { put("port", it) }
    }
    val datasource = buildJsonObject {
        env(ENV_DB_URL)?.let { put("url", it) }
        env(ENV_DB_USERNAME)?.let { put("username", it) }
        env(ENV_DB_PASSWORD)?.let { put("password", it) }
    }
    return buildJsonObject {
        if (server.isNotEmpty()) put("server", server)
        if (datasource.isNotEmpty()) put("datasource", datasource)
        env(ENV_LOG_LEVEL)?.let { put("logLevel", it) }
    }
}

/** 读磁盘配置文件；文件不存在或读不出来时返回 `null`（不阻断启动，继续用下层配置）。 */
private fun readConfigFile(path: String): String? =
    runCatching { FileSystem.SYSTEM.read(path.toPath()) { readUtf8() } }.getOrNull()

/**
 * 解析一层配置。
 *
 * - 容忍 BOM：Windows 上用 PowerShell `Set-Content -Encoding UTF8`/记事本存的 JSON 会带 BOM，
 *   不剥掉的话 `parseToJsonElement` 直接失败；
 * - 解析失败**记 WARN 后忽略该层**，而不是静默跳过 —— 服务器上写错配置却没提示，比启动失败更难查。
 */
private fun parseJsonObject(text: String, source: String): JsonObject? =
    try {
        configJson.parseToJsonElement(text.trimStart('\uFEFF')) as? JsonObject
            ?: throw IllegalArgumentException("顶层必须是 JSON 对象")
    } catch (e: Exception) {
        Log.of("Config").warn { "$source 解析失败，该层配置已忽略：${e.message}" }
        null
    }

/** 深合并：两边同为对象时递归合并，否则 [other] 整体覆盖。 */
internal fun JsonObject.overlay(other: JsonObject?): JsonObject {
    if (other.isNullOrEmpty()) return this
    val result = LinkedHashMap<String, JsonElement>(this)
    for ((key, value) in other) {
        val old = result[key]
        result[key] = if (old is JsonObject && value is JsonObject) old.overlay(value) else value
    }
    return JsonObject(result)
}
