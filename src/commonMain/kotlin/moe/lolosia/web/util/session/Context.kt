package moe.lolosia.web.util.session

import io.ktor.server.application.ApplicationCall
import moe.lolosia.web.util.router.Injectable

/**
 * 请求上下文。
 *
 * 控制器形参声明 `ctx: Context` 即可按类型自动注入（见 [Injectable]）。
 *
 * @author 洛洛希雅Lolosia
 */
@Injectable
public interface Context {
    val call: ApplicationCall
}
