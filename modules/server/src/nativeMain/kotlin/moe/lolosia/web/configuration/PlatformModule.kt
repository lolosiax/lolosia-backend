package moe.lolosia.web.configuration

import moe.lolosia.web.settings.AppSettings
import moe.lolosia.web.util.fs.DEFAULT_WORK_DIR
import moe.lolosia.web.util.fs.FileStore
import moe.lolosia.web.util.fs.PosixFileStore
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * native 目标特有的绑定。
 *
 * 运行期文件存储要走平台 posix 调用，实现只在 nativeMain 可见，
 * 所以本模块以 [platformModule] 的 `actual` 形式留在 nativeMain（`expect` 在 commonMain）。
 */
actual fun platformModule(config: AppSettings): Module = module {
    single<FileStore> { PosixFileStore(config.workDir.ifBlank { DEFAULT_WORK_DIR }) }
}
