package moe.lolosia.web.ui.pages.home

import moe.lolosia.web.dto.AnimeSeriesDto
import moe.lolosia.web.ui.state.ListState
import moe.lolosia.web.ui.state.State

/**
 * 番组表页状态。
 *
 * 列表结构在 Kotlin 侧生成 RML 后整页加载：当前 RmlUi 的 C ABI 还没有 `data-for` 列表绑定，
 * 而番组表是只读一页，整页生成比先补一套通用列表绑定更稳。后续需要增量刷新时再下沉到 C ABI。
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

    /** 生成的整页 RML；空串表示还没生成过，宿主回退到静态占位页。 */
    val document = State("")

    /** 每次重建文档递增，宿主用它判断是否需要重新加载。 */
    val version = State(0)

    fun apply(next: List<AnimeSeriesDto>, covers: Set<Long> = emptySet()) {
        items.replaceAll(next)
        loading.value = false
        hasError.value = false
        error.value = ""
        empty.value = next.isEmpty()
        status.value = if (next.isEmpty()) "本周还没有番组数据" else "共 ${next.size} 部番"
        publish(buildListPage(next, covers))
    }

    fun fail(message: String) {
        loading.value = false
        hasError.value = true
        error.value = message
        empty.value = items.size == 0
        status.value = "加载失败"
        publish(buildMessagePage("加载失败", message, showRetry = true))
    }

    fun startLoading() {
        loading.value = true
        hasError.value = false
        error.value = ""
        status.value = "正在加载番组表…"
        publish(buildMessagePage("番组表", "正在加载…", showRetry = false))
    }

    private fun publish(rml: String) {
        document.value = rml
        version.value += 1
    }
}

private const val PAGE_HEADER = """<rml>
<head>
	<link type="text/css" href="/ui/theme/theme.rcss"/>
</head>
<body data-model="main">
"""

private const val PAGE_FOOTER = """	<div class="tabbar">
		<div class="tab" data-class-active="tabHome" data-event-click="action('tab-home')">番组</div>
		<div class="tab" data-class-active="tabProfile" data-event-click="action('tab-profile')">我的</div>
	</div>
</body>
</rml>
"""

private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 生成列表页；标题等文本来自服务端，必须转义后再拼进 RML。 */
private fun buildListPage(items: List<AnimeSeriesDto>, covers: Set<Long>): String = buildString {
    append(PAGE_HEADER)
    append("""	<div class="screen scroll tabbed">
		<div class="title">番组表</div>
		<div class="subtitle">共 ${items.size} 部番</div>
""")
    if (items.isEmpty()) {
        append("""		<div class="card"><p class="muted">本周还没有番组数据。</p></div>
""")
    } else {
        append("""		<table class="list-table">
""")
        items.forEach { item ->
            val cover = if (item.id in covers) {
                """<img class="list-cover-img" src="${coverPath(item.id)}"/>"""
            } else {
                """<span class="list-cover">${escape(initialOf(item.title))}</span>"""
            }
            append("""			<tr class="list-row">
				<td class="list-cover-cell">$cover</td>
				<td class="list-main">
					<div class="list-title">${escape(item.title)}</div>
					<div class="list-meta">${escape(metaOf(item))}</div>
				</td>
			</tr>
""")
        }
        append("""		</table>
""")
    }
    append("""	</div>
""")
    append(PAGE_FOOTER)
}

/** 封面在视图层资源表里的路径；与 `ClientApp` 登记的路径一致。 */
fun coverPath(seriesId: Long): String = "/ui/images/anime/$seriesId.img"

/** 加载中 / 失败等简单页面。 */
private fun buildMessagePage(title: String, message: String, showRetry: Boolean): String = buildString {
    append(PAGE_HEADER)
    append("""	<div class="screen scroll tabbed">
		<div class="title">${escape(title)}</div>
		<div class="subtitle">${escape(message)}</div>
""")
    if (showRetry) {
        append("""		<button class="block" data-event-click="action('refresh')">重试</button>
""")
    }
    append("""	</div>
""")
    append(PAGE_FOOTER)
}

/** 放送信息：星期 + 时间 + 关注状态。 */
private fun metaOf(item: AnimeSeriesDto): String {
    val weekday = item.airWeekday?.let { WEEKDAYS.getOrNull(it - 1) }.orEmpty()
    val time = item.airTime.orEmpty()
    val followed = if (item.followed) "已关注" else ""
    return listOf(weekday, time, followed).filter { it.isNotBlank() }.joinToString(" ")
}

/** 没有封面时的占位字。 */
private fun initialOf(title: String): String = title.trim().take(1).ifEmpty { "?" }

/** RML 文本转义；只处理会破坏结构的字符。 */
private fun escape(text: String): String = buildString(text.length) {
    text.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}
