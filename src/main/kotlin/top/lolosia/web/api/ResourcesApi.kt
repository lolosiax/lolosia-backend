package top.lolosia.web.api

import top.lolosia.web.util.bundle.Bundle
import top.lolosia.web.util.bundle.bundleOf
import top.lolosia.web.util.property.MutableProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlow
import org.springframework.web.reactive.function.client.exchangeToFlow
import java.io.File

/**
 * ResourcesApi
 * @author 洛洛希雅Lolosia
 * @since 2024-11-09 16:33
 */
object ResourcesApi {

    suspend fun checkFiles(dir: String): List<Bundle>? {
        return post(
            baseUrl,
            "/resources/checkFiles",
            body = bundleOf("dir" to dir),
            raw = true,
        )
    }

    suspend fun downloadFiles(
        file: String,
        target: File,
        byteProcessed : MutableProperty<Long> = MutableProperty(0L),
        byteCount: MutableProperty<Long> = MutableProperty(0L)
    ) {
        val response = WebClient.create(rootUrl)
            .get()
            .uri("/res/$file")
            .accept(MediaType.APPLICATION_OCTET_STREAM)
            .exchangeToFlow {
                if (it.statusCode() != HttpStatus.OK) {
                    throw IllegalStateException("文件下载失败：${it.statusCode()}")
                }
                byteCount.value = it.headers().contentLength().orElse(-1L)
                it.bodyToFlow<ByteArray>()
            }

        withContext(Dispatchers.IO) {
            try {
                target.parentFile.mkdirs()
                target.outputStream().use { fos ->
                    response.collect {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        fos.write(it)
                        byteProcessed.value += it.size
                    }
                }
            } catch (e: Throwable) {
                if (target.exists()) {
                    target.delete()
                }
                throw e
            }
        }
    }
}