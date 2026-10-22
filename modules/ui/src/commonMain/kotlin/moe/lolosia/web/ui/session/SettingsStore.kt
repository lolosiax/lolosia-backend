package moe.lolosia.web.ui.session

/**
 * 本地设置的持久化出口。
 *
 * Windows 落工作目录下的文件，Android 落应用私有目录；实现不得把 token 写进日志。
 *
 * @author 洛洛希雅Lolosia
 */
interface SettingsStore {

    /** 读取设置；文件不存在或损坏时返回默认值。 */
    fun load(): ClientSettings

    /** 覆盖保存；失败由实现自行记录日志，不向 UI 抛异常。 */
    fun save(settings: ClientSettings)
}
