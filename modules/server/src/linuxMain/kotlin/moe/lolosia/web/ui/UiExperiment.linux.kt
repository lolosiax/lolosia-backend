package moe.lolosia.web.ui

import moe.lolosia.web.util.log.Log

actual fun runUiExperiment(): Int {
    // SDL3 暂未为 linux 目标交叉构建（thirdparty/linux*/ 下没有 libSDL3.a），
    // 这里的 actual 只为让两个 linux 目标编得过；补齐 SDL3 后与本文件一起替换。
    Log.of("UiExperiment").warn { "UI 实验当前只支持 mingwX64：linux 目标尚未交叉构建 SDL3" }
    return 1
}
