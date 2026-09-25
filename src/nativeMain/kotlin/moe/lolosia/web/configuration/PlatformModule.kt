package moe.lolosia.web.configuration

import moe.lolosia.web.util.fs.FileStore
import moe.lolosia.web.util.fs.PosixFileStore
import org.koin.core.module.Module
import org.koin.dsl.module

/** 运行期可写文件的根目录（相对启动目录；与旧版一致，`.gitignore` 已含 `/work/`）。 */
public const val WORK_DIR: String = "work"

/**
 * native 目标特有的绑定。
 *
 * 运行期文件存储要走平台 posix 调用，实现只在 nativeMain 可见，所以绑定与 common 的
 * [moe.lolosia.web.configuration.appModule] / [serviceModule] 分开，由各目标 `main()` 传入。
 */
public fun platformModule(): Module = module {
    single<FileStore> { PosixFileStore(WORK_DIR) }
}
