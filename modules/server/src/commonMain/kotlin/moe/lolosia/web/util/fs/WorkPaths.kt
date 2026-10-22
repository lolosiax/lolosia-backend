package moe.lolosia.web.util.fs

import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.router.Injectable
import moe.lolosia.web.util.router.Service
import okio.Path
import okio.Path.Companion.toPath

/** 默认工作目录名；相对启动目录。 */
const val DEFAULT_WORK_DIR: String = "work"

/**
 * 运行期可写数据的统一根目录。
 *
 * 由 [AppSettings.workDir] 决定（默认 `work`）。所有未显式配置路径的缓存与文件都应从这里派生，
 * 这样把 `workDir` 改成 `/mnt/hdd1/.lolosia` 就能整体搬家，不必逐个改配置。
 *
 * 显式配置的绝对路径保持原样；显式配置的相对路径仍按启动目录解析（与旧版一致），
 * 只有“留空走默认”的场景才落到这里。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
@Service
class WorkPaths(settings: AppSettings) {

    /** 工作根目录。 */
    val root: Path = settings.workDir.ifBlank { DEFAULT_WORK_DIR }.toPath()

    /** 缓存的统一根：`<workDir>/cache`。 */
    val cacheRoot: Path = root.resolve("cache")

    /** 工件仓库默认根：`<workDir>/repo`。 */
    val artifactRoot: Path = root.resolve("repo")

    /** `<workDir>/cache/<segments…>`。 */
    fun cache(vararg segments: String): Path =
        segments.fold(cacheRoot) { path, segment -> path.resolve(segment) }
}
