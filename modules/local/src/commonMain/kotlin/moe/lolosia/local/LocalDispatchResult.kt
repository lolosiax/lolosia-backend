package moe.lolosia.local

import moe.lolosia.web.dto.LocalFrame

/**
 * 一次请求的处理结果。
 *
 * 现在只有响应；后续 job 类方法可以在这里追加要推送给 UI 的事件。
 */
data class LocalDispatchResult(
    val responses: List<LocalFrame> = emptyList(),
)
