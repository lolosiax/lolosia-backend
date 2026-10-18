package moe.lolosia.web.ui.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.web.ui.session.ClientSettings
import moe.lolosia.web.ui.session.SettingsStore
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Windows 本地设置：落在工作目录下的 JSON 文件。
 *
 * 文件里含会话 id，不写日志；读写失败一律退回默认值 / 静默忽略。
 *
 * @param path 设置文件路径，相对当前工作目录。
 * @author 洛洛希雅Lolosia
 */
class WindowsSettingsStore(
    private val path: String = "work/client-settings.json",
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
