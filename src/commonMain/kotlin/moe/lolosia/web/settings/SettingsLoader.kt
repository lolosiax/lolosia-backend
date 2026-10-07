package moe.lolosia.web.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import moe.lolosia.web.util.classpath.Classpath
import moe.lolosia.web.util.classpath.readText
import moe.lolosia.web.util.json.overlay
import moe.lolosia.web.util.log.Log
import okio.FileSystem
import okio.Path.Companion.toPath

/** 磁盘设置文件候选名（相对启动目录），按顺序取第一个存在的。 */
public val SETTINGS_FILES: List<String> = listOf("application.yaml", "application.yml", "application.json")

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

private val settingsYaml: Yaml = Yaml.default

/**
 * 加载设置，优先级由低到高：
 *
 * 1. [AppSettings] 字段默认值
 * 2. classpath 内嵌的 `application.json`
 * 3. 磁盘设置文件（[diskPath]；为 `null` 时按 [SETTINGS_FILES] 在启动目录里找第一个存在的）
 * 4. 环境变量（未设置的不参与合并）
 *
 * **每层内部按 JSON 对象深合并**（对象递归合并、标量整体替换）；磁盘文件允许 YAML 或 JSON，
 * 由扩展名（`.yaml` / `.yml` / `.json`）判定，解析后统一转成 JSON 对象再合并。
 * 命令行参数只覆盖端口，由 [moe.lolosia.web.main] 处理。
 *
 * @param classpath 资源来源
 * @param diskPath 磁盘设置文件路径；为 `null` 时依次尝试 [SETTINGS_FILES]
 * @param env 环境变量读取函数，未设置须返回 `null`
 */
public fun loadAppSettings(classpath: Classpath, diskPath: String?, env: (String) -> String?): AppSettings {
    val defaults: JsonObject = settingsJson.encodeToJsonElement(AppSettings.serializer(), AppSettings()).jsonObject
    val embedded = classpath.readText(SETTINGS_RESOURCE)?.let { parseSettings(it, SETTINGS_RESOURCE) }
    val disk = findDiskSettings(diskPath)?.let { (path, text) -> parseSettings(text, path) }
    val merged = defaults.overlay(embedded).overlay(disk).overlay(envOverrides(env))
    return settingsJson.decodeFromJsonElement(AppSettings.serializer(), merged)
}

/**
 * 定位磁盘设置文件。
 *
 * [explicitPath]（`KTOR_CONFIG`）配了就只认它，读了但没有/读不出来就当该层不存在；
 * 没配则按 [SETTINGS_FILES] 顺序取第一个存在的文件。
 *
 * @return 文件路径与内容；一个都没找到时返回 `null`
 */
private fun findDiskSettings(explicitPath: String?): Pair<String, String>? {
    if (explicitPath != null) return readSettingsFile(explicitPath)?.let { explicitPath to it }
    for (name in SETTINGS_FILES) {
        readSettingsFile(name)?.let { return name to it }
    }
    return null
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
 * 解析一层设置：`.yaml` / `.yml` 走 KAML，其余按 JSON。
 *
 * 会剥掉 BOM（Windows 上 PowerShell / 记事本保存的文本常带 BOM）；
 * 解析失败或顶层不是对象时记 WARN 后忽略该层。
 */
private fun parseSettings(text: String, source: String): JsonObject? =
    try {
        val clean = text.trimStart('\uFEFF')
        val element: JsonElement =
            if (source.isYaml()) settingsYaml.parseToYamlNode(clean).toJsonElement()
            else settingsJson.parseToJsonElement(clean)
        element as? JsonObject ?: throw IllegalArgumentException("顶层必须是对象")
    } catch (e: Exception) {
        Log.of("Settings").warn { "$source 解析失败，该层设置已忽略：${e.message}" }
        null
    }

private fun String.isYaml(): Boolean = endsWith(".yaml", ignoreCase = true) || endsWith(".yml", ignoreCase = true)

/**
 * 把 KAML 的 [YamlNode] 转成 kotlinx 的 [JsonElement]，以便与 JSON 层一起深合并。
 *
 * 标量一律当**字符串**处理：KAML 的 `YamlScalar` 只保留 `content`，不保留引号与解析出的类型，
 * 猜类型会把 `007` 这类数字样字符串改写掉；解成目标字段类型（Int / Boolean / String …）由
 * kotlinx 自己按内容转换。空值（`key:` 或 `key: null`）是独立的 [YamlNull]，仍转成 `null`。
 */
private fun YamlNode.toJsonElement(): JsonElement = when (this) {
    is YamlNull -> JsonNull
    is YamlScalar -> JsonPrimitive(content)
    is YamlList -> JsonArray(items.map(YamlNode::toJsonElement))
    is YamlMap -> JsonObject(entries.entries.associate { (key, value) -> key.content to value.toJsonElement() })
    is YamlTaggedNode -> innerNode.toJsonElement()
}
