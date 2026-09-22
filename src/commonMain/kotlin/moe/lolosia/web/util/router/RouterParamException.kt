package moe.lolosia.web.util.router

/** 请求参数不合法或缺席（由 StatusPages 统一转成 400）。 */
public class RouterParamException(message: String) : RuntimeException(message)
