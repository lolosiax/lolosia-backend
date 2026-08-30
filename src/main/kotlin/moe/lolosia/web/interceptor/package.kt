package moe.lolosia.web.interceptor

import io.ktor.server.application.Application

/**
 * 洋葱模型最外层中间件的注册处：**注册顺序 = 由外到内**（Ktor 无 @Order，代码顺序即层次顺序）。
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-12-02 09:51
 */
fun Application.configureInterceptors() {
    installTrafficInterceptor() // 第1层：流量统计
    installCtxInterceptor()     // 第2层：创建并绑定请求级 ctx（业务传 ctx 而非 call）
    installSseInterceptor()     // 第3层：SSE 特殊协议（x-upgrade 换 ID / eventSourceId 建流）
    installAuthInterceptor()    // 第4层：会话认证（/api/** 白名单除外）
}
