package moe.lolosia.web.config.web

import ai.koog.ktor.Koog
import io.ktor.server.application.Application
import io.ktor.server.application.install

/**
 * AI 集成（Koog，JetBrains 出品）。
 * 各 LLM 提供方按需启用，未配置密钥的请注释掉。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-08-30 12:21
 */
fun Application.configureKoog() {
    install(Koog) {
        llm {
            // openAI(apiKey = "your-openai-api-key")
            // anthropic(apiKey = "your-anthropic-api-key")
            ollama { baseUrl = "http://localhost:11434" }
            // google(apiKey = "your-google-api-key")
            // openRouter(apiKey = "your-openrouter-api-key")
            // deepSeek(apiKey = "your-deepseek-api-key")
        }
    }
}
