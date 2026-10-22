@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.lolosia.web

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import moe.lolosia.web.util.log.Log
import moe.lolosia.web.util.platform.awaitSignalTermination
import platform.windows.DWORD
import platform.windows.GetLastError
import platform.windows.LPVOID
import platform.windows.LPWSTRVar
import platform.windows.RegisterServiceCtrlHandlerExW
import platform.windows.SERVICE_ACCEPT_STOP
import platform.windows.SERVICE_CONTROL_STOP
import platform.windows.SERVICE_RUNNING
import platform.windows.SERVICE_STATUS
import platform.windows.SERVICE_STATUS_HANDLE
import platform.windows.SERVICE_STOPPED
import platform.windows.SERVICE_TABLE_ENTRYW
import platform.windows.SERVICE_WIN32_OWN_PROCESS
import platform.windows.SetServiceStatus
import platform.windows.StartServiceCtrlDispatcherW
import kotlin.concurrent.Volatile

/** SCM 服务名；与控制台 / systemd 共用一个入口，只是启动方式不同。 */
private const val SERVICE_NAME = "LolosiaService"

/** 非 SCM 启动时 `StartServiceCtrlDispatcherW` 返回的错误码。 */
private const val ERROR_FAILED_SERVICE_CONTROLLER_CONNECT = 1063u

/** 由 [runServiceProcess] 写入、SCM 服务主函数读取。 */
@Volatile
private var serviceBlock: (suspend (suspend () -> Unit) -> Unit)? = null

/** SCM 控制句柄；只在服务主线程写入，控制回调读取。 */
@Volatile
private var serviceStatusHandle: SERVICE_STATUS_HANDLE? = null

/** STOP 控制到达时完成它，让服务主体的 `awaitStop()` 返回。 */
@Volatile
private var stopSignal: CompletableDeferred<Unit>? = null

actual fun runServiceProcess(block: suspend (awaitStop: suspend () -> Unit) -> Unit): Int {
    serviceBlock = block
    val connected = memScoped {
        val table = allocArray<SERVICE_TABLE_ENTRYW>(2)
        table.get(0).lpServiceName = SERVICE_NAME.wcstr.ptr
        table.get(0).lpServiceProc = serviceMainProc
        table.get(1).lpServiceName = null
        table.get(1).lpServiceProc = null
        StartServiceCtrlDispatcherW(table)
    }
    if (connected != 0) return 0

    val error = GetLastError()
    serviceBlock = null
    if (error != ERROR_FAILED_SERVICE_CONTROLLER_CONNECT) {
        Log.of("Service").error { "连接 SCM 失败：$error" }
        return 1
    }

    // 不是由 SCM 拉起（命令行 / 调试）：直接在当前进程跑，Ctrl+C 由默认信号处理结束进程。
    runBlocking { block { awaitSignalTermination() } }
    return 0
}

/** SCM 服务主函数：注册控制回调、上报状态、跑服务主体。 */
private fun serviceMain() {
    val log = Log.of("Service")
    serviceStatusHandle = RegisterServiceCtrlHandlerExW(SERVICE_NAME, serviceControlHandler, null)
    if (serviceStatusHandle == null) {
        log.error { "注册服务控制回调失败：${GetLastError()}" }
        return
    }
    reportServiceStatus(SERVICE_RUNNING.toUInt())

    val stop = CompletableDeferred<Unit>()
    stopSignal = stop
    val block = serviceBlock
    if (block == null) {
        log.error { "服务主体未初始化" }
        reportServiceStatus(SERVICE_STOPPED.toUInt())
        return
    }

    try {
        runBlocking { block { stop.await() } }
    } catch (error: Throwable) {
        log.error(error) { "服务运行异常" }
    } finally {
        stopSignal = null
        reportServiceStatus(SERVICE_STOPPED.toUInt())
        log.info { "服务已停止" }
    }
}

private fun reportServiceStatus(state: UInt) {
    val handle = serviceStatusHandle ?: return
    memScoped {
        val status = alloc<SERVICE_STATUS>()
        status.dwServiceType = SERVICE_WIN32_OWN_PROCESS.toUInt()
        status.dwCurrentState = state
        status.dwControlsAccepted = if (state == SERVICE_RUNNING.toUInt()) {
            SERVICE_ACCEPT_STOP.toUInt()
        } else {
            0u
        }
        status.dwWin32ExitCode = 0u
        status.dwServiceSpecificExitCode = 0u
        status.dwCheckPoint = 0u
        status.dwWaitHint = 0u
        SetServiceStatus(handle, status.ptr)
    }
}

/** SCM 控制回调：收到 STOP 时让服务主体的 `awaitStop()` 返回。 */
private val serviceControlHandler = staticCFunction { control: DWORD, _: DWORD, _: LPVOID?, _: LPVOID? ->
    if (control == SERVICE_CONTROL_STOP.toUInt()) {
        Log.of("Service").info { "收到 SCM 停止请求" }
        stopSignal?.complete(Unit)
    }
    0u
}

/** SCM 服务主函数指针。 */
private val serviceMainProc = staticCFunction { _: DWORD, _: CPointer<LPWSTRVar>? ->
    serviceMain()
}
