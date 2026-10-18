package moe.lolosia.web.util.llm

import kotlinx.serialization.Serializable

/**
 * 大模型接入配置（OpenAI 兼容协议）。
 *
 * 由 [moe.lolosia.web.settings.AppSettings] 的 `llm` 字段承载，多级覆盖见 `loadAppSettings`。
 * [apiKey] 属凭据，**不入库**；用环境变量 `KTOR_LLM_API_KEY` 或本机磁盘设置文件注入。
 */
@Serializable
data class LlmSettings(
    /** 服务根地址（不含 `/chat/completions`）。 */
    val baseUrl: String = "https://api.deepseek.com",
    /** 访问密钥；留空则调用会失败。 */
    val apiKey: String = "",
    /** 模型名。 */
    val model: String = "deepseek-flash",
    /** 采样温度；`null` 表示不发送该字段。 */
    val temperature: Double? = null,
    /** 单次对话允许的工具调用轮次上限。 */
    val maxToolRounds: Int = 8,
    /** 单轮请求的整体超时（秒）。 */
    val timeoutSeconds: Int = 120,
)
