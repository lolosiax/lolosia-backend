package moe.lolosia.web.ui.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.web.ui.session.ClientSettings
import moe.lolosia.web.ui.session.SettingsStore
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * JSON 文件形式的客户端设置。
 *
 * UI 与服务进程共用同一份文件：UI 登录后写入，服务进程按固定间隔重新读取，
 * 因此服务不需要单独配置 token。文件里含会话 id，不写日志；读写失败退回默认值或静默忽略。
 *
 * @param path 设置文件的绝对路径，由各平台的 [sharedSettingsPath] 给出。
 * @author 洛洛希雅Lolosia
 */
class FileSettingsStore(
    private val path: String,
) : SettingsStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override fun load(): ClientSettings = runCatching {
        val file = path.toPath()
        if (!FileSystem.SYSTEM.exists(file)) return@runCatching ClientSettings()
        json.decodeFromString<ClientSettings>(FileSystem.SYSTEM.read(file) { readUtf8() })
    }.getOrDefault(ClientSettings())

    override fun save(settings: ClientSettings) {
        runCatching {
            val file = path.toPath()
            file.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(file) { writeUtf8(json.encodeToString(settings)) }
        }
    }
}
