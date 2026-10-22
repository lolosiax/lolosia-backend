package moe.lolosia.web.util.tokenizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.lolosia.web.api.get
import moe.lolosia.web.service.files.FileBrowser
import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.fs.WorkPaths
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import okio.Buffer
import okio.FileSystem
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes

/**
 * DeepSeek 分词器门面：**运行时**拉取 `tokenizer.json`，默认缓存到 `<workDir>/cache/llm/`，
 * 之后离线用缓存；拉不到 / 解析失败则**降级**为字符启发式（[estimateTokens]），不阻断打标。
 *
 * 它是「全局统计消耗」的文本侧口径（见 `doc/vision-autotag-design.md` §6.3 / §6.4）：
 * 图片 token 以响应 `usage` 为准，文本 token 用它精确算。
 *
 * 显式配置 `cacheDir` 时仍按旧语义相对 [FileBrowser] 的浏览根解析（兼容已有配置）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class DeepSeekTokenizer(settings: AppSettings, browser: FileBrowser, workPaths: WorkPaths) {

    private val log = Log.of("Tokenizer")
    private val config = settings.files.media.vision.tokenizer
    private val fs: FileSystem = FileSystem.SYSTEM
    private val cacheFile = if (config.cacheDir.isBlank()) {
        workPaths.cache("llm").resolve(TOKENIZER_CACHE_FILE)
    } else {
        browser.rootPath.resolve(config.cacheDir.trim('/')).resolve(TOKENIZER_CACHE_FILE)
    }

    private val mutex = Mutex()
    private var tried = false

    @Volatile
    private var data: TokenizerData? = null

    /** 是否已成功加载（拉不到时恒为 `false`，估算走字符启发式）。 */
    val available: Boolean get() = data != null

    /** 精确 token 数；未就绪时回退 [estimateTokens]。 */
    suspend fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val tokenizer = load() ?: return estimateTokens(text)
        return tokenizer.encode(text).size
    }

    /** 精确编码；未就绪时返回 `null`。 */
    suspend fun encode(text: String): IntArray? = load()?.encode(text)

    private suspend fun load(): TokenizerData? {
        data?.let { return it }
        return mutex.withLock {
            data?.let { return@withLock it }
            if (tried) return@withLock null
            tried = true
            val loaded = runCatching { readOrFetch() }
                .onFailure { log.warn(it) { "分词器加载失败，改用字符启发式估算" } }
                .getOrNull()
            if (loaded != null) {
                log.info { "DeepSeek 分词器已就绪：$cacheFile" }
                data = loaded
            } else {
                log.info { "DeepSeek 分词器不可用，文本 token 走字符启发式估算" }
            }
            loaded
        }
    }

    private suspend fun readOrFetch(): TokenizerData? {
        readCache()?.let { return withContext(Dispatchers.Default) { TokenizerData.parse(it) } }
        val bytes = fetch() ?: return null
        val parsed = withContext(Dispatchers.Default) { TokenizerData.parse(bytes.decodeToString()) }
        writeCache(bytes)
        return parsed
    }

    private fun readCache(): String? {
        if (fs.metadataOrNull(cacheFile) == null) return null
        return runCatching { fs.read(cacheFile) { readUtf8() } }
            .onFailure { log.warn(it) { "读取分词器缓存失败：$cacheFile" } }
            .getOrNull()
    }

    private fun writeCache(bytes: ByteArray) {
        runCatching {
            cacheFile.parent?.let { fs.createDirectories(it) }
            fs.write(cacheFile) { write(bytes) }
        }.onFailure { log.warn(it) { "写入分词器缓存失败：$cacheFile" } }
    }

    /** 依配置的 [AppSettings.FilesConfig.MediaConfig.VisionConfig.TokenizerConfig.url] 与镜像依次尝试下载。 */
    private suspend fun fetch(): ByteArray? {
        for (url in listOfNotNull(config.url.ifBlank { null }, config.mirrorUrl.ifBlank { null })) {
            tryFetch(url)?.let {
                log.info { "分词器已下载：$url（${it.size} 字节）" }
                return it
            }
        }
        return null
    }

    private suspend fun tryFetch(url: String): ByteArray? = runCatching {
        val (base, path) = splitUrl(url)
        val buffer = Buffer()
        get<Flow<ByteArray>>(base, path, timeout = 5.minutes, noLog = true).collect { buffer.write(it) }
        buffer.readByteArray()
    }.onFailure { log.warn(it) { "下载分词器失败：$url" } }.getOrNull()

    /** 把完整 URL 拆成 `baseUrl`（到主机）与 `path`（含 query）。 */
    private fun splitUrl(url: String): Pair<String, String> {
        val schemeEnd = url.indexOf("://")
        val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val slash = url.indexOf('/', hostStart)
        return if (slash < 0) url to "/" else url.substring(0, slash) to url.substring(slash)
    }
}
