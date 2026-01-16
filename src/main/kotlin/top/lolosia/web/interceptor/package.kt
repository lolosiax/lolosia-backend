package top.lolosia.web.interceptor

import org.springframework.stereotype.Component

@Component
private class NetworkTraffic : NetworkTrafficFilter(-9)

@Component
private class Cors : CorsWebFilter(-8)

@Component
private class Error : ErrorWebFilter(-7)

@Component
private class EventSource : EventSourceWebFilter(-6)

@Component
private class Log : LogWebFilter(-5)

@Component
private class Jwt : JwtWebFilter(-3)

@Component
private class Return : ReturnWebFilter(-2)