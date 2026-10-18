package moe.lolosia.android.ui

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.web.ui.session.ClientSettings
import moe.lolosia.web.ui.session.SettingsStore

/**
 * Android 本地设置：落在应用私有的 SharedPreferences，不写日志。
 *
 * 服务端地址由登录页维护；首次安装时是 [ClientSettings] 的本机默认值。
 *
 * @author 洛洛希雅Lolosia
 */
internal class AndroidSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): ClientSettings = runCatching {
        prefs.getString(KEY_SETTINGS, null)
            ?.let { json.decodeFromString<ClientSettings>(it) }
            ?: ClientSettings()
    }.getOrDefault(ClientSettings())

    override fun save(settings: ClientSettings) {
        runCatching {
            prefs.edit().putString(KEY_SETTINGS, json.encodeToString(settings)).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "client-settings"
        const val KEY_SETTINGS = "json"
    }
}
