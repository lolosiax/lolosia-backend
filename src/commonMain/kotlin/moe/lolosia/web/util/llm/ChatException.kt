package moe.lolosia.web.util.llm

import moe.lolosia.web.dto.ApiException

/**
 * 大模型调用失败。
 *
 * 继承 [ApiException]，因此可直接经 `StatusPages` 转成统一错误信封（`{code,msg,data}`）。
 */
class ChatException(status: Int, message: String) : ApiException(status, message)
