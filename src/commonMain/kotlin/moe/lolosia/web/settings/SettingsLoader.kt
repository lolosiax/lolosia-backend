package moe.lolosia.web.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.classpath.readText
import moe.lolosia.web.util.json.overlay
import moe.lolosia.web.util.log.Log
import okio.FileSystem
import okio.Path.Companion.toPath

/** 磁盘设置文件名（相对启动目录）。 */
public const val SETTINGS_FILE: String = "application.json"

/** 覆盖磁盘设置文件路径的环境变量。 */
public const val ENV_SETTINGS_FILE: String = "KTOR_CONFIG"

/** classpath 内嵌的默认设置文件名。 */
private const val SETTINGS_RESOURCE: String = "application.json"

private const val ENV_HOST: String = "KTOR_HOST"
private const val ENV_PORT: String = "KTOR_PORT"
private const val ENV_LOG_LEVEL: String = "KTOR_LOG_LEVEL"
private const val ENV_DB_URL: String = "KTOR_DB_URL"
private const val ENV_DB_USERNAME: String = "KTOR_DB_USERNAME"
private const val ENV_DB_PASSWORD: String = "KTOR_DB_PASSWORD"

private val settingsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 加载设置，优先级由低到高：
 *
 * 1. [AppSettings] 字段默认值
 * 2. classpath 内嵌的 `application.json`
 * 3. 磁盘上的 `application.json`（[diskPath]）
 * 4. 环境变量（未设置的不参与合并）
 *
 * 每一层都是 JSON 对象，按字段深合并（对象递归合并，标量整体替换）。
 * 命令行参数只覆盖端口，由 `startServer` 处理。
 *
 * @param classpath 资源来源
 * @param diskPath 磁盘设置文件路径；为 `null` 时跳过该层
 * @param env 环境变量读取函数，未设置须返回 `null`
 */
public fun loadAppSettings(classpath: Classpath, diskPath: String?, env: (String) -> String?): AppSettings {
    val defaults: JsonObject = settingsJson.encodeToJsonElement(AppSettings.serializer(), AppSettings()).jsonObject
    val embedded = classpath.readText(SETTINGS_RESOURCE)?.let { parseJsonObject(it, "classpath 内嵌 $SETTINGS_RESOURCE") }
    val disk = diskPath?.let(::readSettingsFile)?.let { parseJsonObject(it, diskPath) }
    val merged = defaults.overlay(embedded).overlay(disk).overlay(envOverrides(env))
    return settingsJson.decodeFromJsonElement(AppSettings.serializer(), merged)
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

/** 读磁盘设置文件；文件不存在或读不出来时返回 `null`，不阻断启动。 */
private fun readSettingsFile(path: String): String? =
    runCatching { FileSystem.SYSTEM.read(path.toPath()) { readUtf8() } }.getOrNull()

/**
 * 解析一层设置。
 *
 * 会剥掉 BOM（Windows 上 PowerShell / 记事本保存的 JSON 常带 BOM）；
 * 解析失败记 WARN 后忽略该层。
 */
private fun parseJsonObject(text: String, source: String): JsonObject? =
    try {
        settingsJson.parseToJsonElement(text.trimStart('\uFEFF')) as? JsonObject
            ?: throw IllegalArgumentException("顶层必须是 JSON 对象")
    } catch (e: Exception) {
        Log.of("Settings").warn { "$source 解析失败，该层设置已忽略：${e.message}" }
        null
    }
