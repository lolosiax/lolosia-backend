package moe.lolosia.web.dto

import kotlinx.serialization.Serializable

/**
 * 运行时设置（面板上改、立刻生效、重启后仍在）。
 *
 * 与 `application.yaml` 的关系：配置文件里的同名项是**初值**，这里存的是改过之后的值。
 *
 * @property webdav WebDAV 相关设置
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class AppSettingDto(
    val webdav: WebdavSettingDto = WebdavSettingDto(),
)

/**
 * WebDAV 设置。
 *
 * @property allowHidden 是否允许客户端访问隐藏项（`.` 开头，以及 `lost+found`）；关掉时
 *   隐藏项在 WebDAV 里既不列举也直连不到（403）
 * @author 洛洛希雅Lolosia
 */
@Serializable
data class WebdavSettingDto(
    val allowHidden: Boolean = false,
)
