package moe.lolosia.web.ui.client

import moe.lolosia.web.ui.image.ImageLoader

/**
 * 图片加载占位实现：M2 接入内存资源注册前先返回空。
 *
 * @author 洛洛希雅Lolosia
 */
class NoopImageLoader : ImageLoader {

    override suspend fun load(url: String, token: String?): ByteArray? = null

    override fun register(path: String, bytes: ByteArray): String? = null
}
