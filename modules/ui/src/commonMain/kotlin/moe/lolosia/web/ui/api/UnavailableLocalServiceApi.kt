package moe.lolosia.web.ui.api

/**
 * 本地服务不在线时的降级实现：所有调用都返回空。
 *
 * @author 洛洛希雅Lolosia
 */
class UnavailableLocalServiceApi : LocalServiceApi {

    override suspend fun health(): LocalHealth? = null

    override suspend fun capabilities(): List<String> = emptyList()

    override suspend fun metrics(): LocalMetrics? = null
}
