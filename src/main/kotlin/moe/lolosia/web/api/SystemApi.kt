package moe.lolosia.web.api

import moe.lolosia.web.model.system.SysUserEntity
import moe.lolosia.web.util.bundle.Bundle
import moe.lolosia.web.util.bundle.bundleOf
import moe.lolosia.web.util.session.Context
import java.util.*

/**
 * SystemApi
 * @author 洛洛希雅Lolosia
 * @since 2024-10-27 15:03
 */
object SystemApi {

    suspend fun mySession(sessionId: UUID): Bundle {
        return post(baseUrl, "/user/mySession", raw = true, sessionId = sessionId)
    }

    suspend fun myRole(ctx: Context): Bundle {
        return post(baseUrl, "/user/myRole", raw = true, ctx = ctx)
    }

    suspend fun myUserInfo(ctx: Context): SysUserEntity {
        return post(baseUrl, "/user/myInfo", raw = true, ctx = ctx)
    }

    suspend fun sse(): SSEResult {
        return sse(baseUrl, "/sse")
    }

    suspend fun registerSseUser(ctx: Context, sseId: UUID) {
        return post(baseUrl, "/sse/registry", body = bundleOf("sseId" to sseId), raw = true, ctx = ctx)
    }
}