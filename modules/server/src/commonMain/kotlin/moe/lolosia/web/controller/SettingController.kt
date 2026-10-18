package moe.lolosia.web.controller

import moe.lolosia.web.dto.AppSettingDto
import moe.lolosia.web.dto.WebdavSettingDto
import moe.lolosia.web.service.AppSettingService
import moe.lolosia.web.util.router.Body
import moe.lolosia.web.util.router.Controller
import moe.lolosia.web.util.router.POST
import moe.lolosia.web.util.router.Permission

/**
 * 运行时设置接口（设置面板读写）。
 *
 * 值存在 `sys_cache`，改完立刻生效且重启后仍在（见 [AppSettingService]）；配置文件名下的同名项只作初值。
 * 读写都要 `lolosia.setting.*` 权限 —— 这些开关影响的是**整台服务**，不是某个用户自己的数据。
 *
 * @author 洛洛希雅Lolosia
 */
@Controller("/api")
object SettingController {

    /** 当前运行时设置。 */
    @POST("/setting/get")
    @Permission("lolosia.setting.read")
    suspend fun get(settings: AppSettingService): AppSettingDto = settings.snapshot()

    /** 改 WebDAV 设置，返回改完之后的完整设置。 */
    @POST("/setting/webdav")
    @Permission("lolosia.setting.write")
    suspend fun updateWebdav(settings: AppSettingService, @Body req: WebdavSettingDto): AppSettingDto =
        settings.setAllowHiddenFiles(req.allowHidden)
}
