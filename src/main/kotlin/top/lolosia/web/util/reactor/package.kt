package top.lolosia.web.util.reactor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono


fun Mono<*>.start(onError: ((Throwable) -> Unit) = { }) {
    CoroutineScope(Dispatchers.IO).launch {
        this@start.subscribe(null, onError)
    }
}

fun Flux<*>.start(onError: ((Throwable) -> Unit) = { }) {
    CoroutineScope(Dispatchers.IO).launch {
        this@start.subscribe(null, onError)
    }
}