package moe.lolosia.web.service.dav

import io.ktor.http.HttpStatusCode

/**
 * WebDAV 操作失败：把「该回哪个 HTTP 状态码」带在异常里，由路由层统一转成裸响应。
 *
 * 刻意不复用 `/api` 的 `ApiException`：WebDAV 不走 JSON 信封，客户端（Windows WebClient）
 * 期望的是干净的状态码与 207 多状态 XML，多包一层 JSON 会让它认不出来。
 *
 * @author 洛洛希雅Lolosia
 */
open class DavException(
    val status: HttpStatusCode,
    message: String = status.description,
) : RuntimeException(message) {

    class NotFound : DavException(HttpStatusCode.NotFound)

    /** 目标已存在、父目录缺失等冲突（COPY/MOVE 的 412 是另一回事，见 [PreconditionFailed]）。 */
    class Conflict(message: String = HttpStatusCode.Conflict.description) :
        DavException(HttpStatusCode.Conflict, message)

    /** `Overwrite: F` 时目标已存在。 */
    class PreconditionFailed : DavException(HttpStatusCode.PreconditionFailed)

    class Forbidden(message: String = HttpStatusCode.Forbidden.description) :
        DavException(HttpStatusCode.Forbidden, message)
}
