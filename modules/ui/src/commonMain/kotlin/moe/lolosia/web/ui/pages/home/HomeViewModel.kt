package moe.lolosia.web.ui.pages.home

import moe.lolosia.web.dto.AnimeSeriesDto
import moe.lolosia.web.ui.state.ListState
import moe.lolosia.web.ui.state.State

/**
 * 番组表页状态。
 *
 * 本期只读；列表绑定在 M2 接 C ABI 的列表能力。
 *
 * @author 洛洛希雅Lolosia
 */
class HomeViewModel {

    val items = ListState<AnimeSeriesDto>()
    val loading = State(false)
    val hasError = State(false)
    val error = State("")
    val empty = State(false)
    val status = State("正在加载番组表…")

    fun apply(next: List<AnimeSeriesDto>) {
        items.replaceAll(next)
        loading.value = false
        hasError.value = false
        error.value = ""
        empty.value = next.isEmpty()
        status.value = if (next.isEmpty()) "本周还没有番组数据" else "共 ${next.size} 部番"
    }

    fun fail(message: String) {
        loading.value = false
        hasError.value = true
        error.value = message
        empty.value = items.size == 0
        status.value = "加载失败"
    }

    fun startLoading() {
        loading.value = true
        hasError.value = false
        error.value = ""
        status.value = "正在加载番组表…"
    }
}
