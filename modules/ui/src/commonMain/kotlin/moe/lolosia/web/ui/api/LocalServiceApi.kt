package moe.lolosia.web.ui.api

/**
 * 本地系统服务能力。
 *
 * 服务不在线时返回 `null`，由 UI 显示降级状态；不向页面抛异常。
 * 本地服务不代理远程 API。
 *
 * @author 洛洛希雅Lolosia
 */
interface LocalServiceApi {

    suspend fun health(): LocalHealth?

    suspend fun capabilities(): List<String>

    suspend fun metrics(): LocalMetrics?
}
