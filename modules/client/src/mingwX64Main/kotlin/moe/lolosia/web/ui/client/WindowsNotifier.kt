package moe.lolosia.web.ui.client

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import moe.lolosia.web.ui.event.ServerPushMessage
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.windows.CreateWindowExW
import platform.windows.DestroyWindow
import platform.windows.HWND
import platform.windows.IDI_APPLICATION
import platform.windows.LPARAM
import platform.windows.LoadIconW
import platform.windows.NIF_ICON
import platform.windows.NIF_INFO
import platform.windows.NIF_MESSAGE
import platform.windows.NIF_TIP
import platform.windows.NIIF_INFO
import platform.windows.NIM_ADD
import platform.windows.NIM_DELETE
import platform.windows.NIM_MODIFY
import platform.windows.NOTIFYICONDATAW
import platform.windows.RegisterClassW
import platform.windows.Shell_NotifyIconW
import platform.windows.UINT
import platform.windows.WM_APP
import platform.windows.WNDCLASSW
import platform.windows.WPARAM
import platform.windows.WNDPROC
import platform.windows.WCHARVar
import platform.windows.DefWindowProcW
import platform.windows.GetModuleHandleW
import kotlin.concurrent.Volatile

/**
 * Windows 系统通知：隐藏窗口 + 托盘图标 + 气泡通知。
 *
 * 托盘图标必须挂在窗口上，因此这里创建一个不可见窗口；气泡用
 * `Shell_NotifyIconW(NIM_MODIFY, NIF_INFO)` 显示，Windows 10/11 会转成系统通知。
 *
 * 由 UI 进程调用（服务进程在 session 0，弹不出用户通知），任意线程都可以调用 [show]。
 *
 * @author 洛洛希雅Lolosia
 */
internal class WindowsNotifier {

    private var hwnd: HWND? = null

    @Volatile
    private var iconAdded = false

    /** 创建隐藏窗口并注册托盘图标；失败返回 `false`，不影响主界面。 */
    fun init(): Boolean {
        if (hwnd != null) return iconAdded
        val instance = GetModuleHandleW(null)
        memScoped {
            val wc = alloc<WNDCLASSW>()
            wc.style = 0u
            wc.lpfnWndProc = windowProc
            wc.cbClsExtra = 0
            wc.cbWndExtra = 0
            wc.hInstance = instance
            wc.hIcon = null
            wc.hCursor = null
            wc.hbrBackground = null
            wc.lpszMenuName = null
            wc.lpszClassName = WINDOW_CLASS.wcstr.ptr
            RegisterClassW(wc.ptr)
        }
        val window = CreateWindowExW(
            dwExStyle = 0u,
            lpClassName = WINDOW_CLASS,
            lpWindowName = "Lolosia Notify",
            dwStyle = 0u,
            X = 0,
            Y = 0,
            nWidth = 0,
            nHeight = 0,
            hWndParent = null,
            hMenu = null,
            hInstance = instance,
            lpParam = null,
        ) ?: return false
        hwnd = window
        iconAdded = addIcon(window)
        return iconAdded
    }

    /** 弹一条系统通知；窗口/托盘未初始化时静默忽略。 */
    fun show(title: String, text: String) {
        val window = hwnd ?: return
        if (!iconAdded) return
        memScoped {
            val data = alloc<NOTIFYICONDATAW>()
            data.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
            data.hWnd = window
            data.uID = ICON_ID
            data.uFlags = NIF_INFO.toUInt()
            data.dwInfoFlags = NIIF_INFO.toUInt()
            data.szInfoTitle.writeWide(title, 64)
            data.szInfo.writeWide(text, 256)
            Shell_NotifyIconW(NIM_MODIFY.toUInt(), data.ptr)
        }
    }

    /** 移除托盘图标并销毁隐藏窗口。 */
    fun dispose() {
        val window = hwnd ?: return
        if (iconAdded) {
            memScoped {
                val data = alloc<NOTIFYICONDATAW>()
                data.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
                data.hWnd = window
                data.uID = ICON_ID
                Shell_NotifyIconW(NIM_DELETE.toUInt(), data.ptr)
            }
            iconAdded = false
        }
        DestroyWindow(window)
        hwnd = null
    }

    private fun addIcon(window: HWND): Boolean = memScoped {
        val data = alloc<NOTIFYICONDATAW>()
        data.cbSize = sizeOf<NOTIFYICONDATAW>().toUInt()
        data.hWnd = window
        data.uID = ICON_ID
        data.uFlags = (NIF_ICON or NIF_TIP or NIF_MESSAGE).toUInt()
        data.uCallbackMessage = (WM_APP + 1).toUInt()
        data.hIcon = LoadIconW(null, IDI_APPLICATION)
        data.szTip.writeWide("洛洛希雅", 128)
        Shell_NotifyIconW(NIM_ADD.toUInt(), data.ptr) != 0
    }

    private companion object {
        const val WINDOW_CLASS = "LolosiaNotifyWindow"
        const val ICON_ID = 1u
    }
}

/** 隐藏窗口的消息过程：只做默认处理，气泡不需要回调。 */
@OptIn(ExperimentalForeignApi::class)
private val windowProc: WNDPROC = staticCFunction { hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM ->
    DefWindowProcW(hwnd, msg, wParam, lParam)
}

/**
 * 轮询服务写出的 spool 文件，把新事件交给通知层。
 *
 * 首次读到已有内容时从文件末尾开始（不补弹历史通知）；文件被截断时自动重置。
 * 只处理以换行结尾的完整行，避免读到服务正在写入的半行。
 *
 * @author 洛洛希雅Lolosia
 */
internal class WindowsEventSpoolWatcher(
    private val path: String = WindowsEventSpool.path,
    private val intervalMs: Long = 500,
    private val onMessage: (ServerPushMessage) -> Unit,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 已处理的行数；`-1` 表示尚未完成第一次扫描。 */
    private var handled = -1

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            poll()
            delay(intervalMs)
        }
    }

    private fun poll() {
        val file = path.toPath()
        if (!FileSystem.SYSTEM.exists(file)) {
            if (handled < 0) handled = 0
            return
        }
        val text = runCatching { FileSystem.SYSTEM.read(file) { readUtf8() } }.getOrNull() ?: return
        if (text.isEmpty()) {
            if (handled < 0) handled = 0
            return
        }
        val complete = text.split('\n').dropLast(1)
        if (handled < 0) {
            handled = complete.size
            return
        }
        if (complete.size < handled) handled = 0
        for (index in handled until complete.size) {
            val record = runCatching {
                json.decodeFromString<EventSpoolRecord>(complete[index])
            }.getOrNull() ?: continue
            onMessage(ServerPushMessage(record.title, record.text))
        }
        handled = complete.size
    }
}

/** 把 Kotlin 字符串写进 Win32 定长 WCHAR 数组，并补 `\0`。 */
private fun CPointer<WCHARVar>?.writeWide(value: String, capacity: Int) {
    if (this == null) return
    val text = value.take(capacity - 1)
    for (index in text.indices) this[index] = text[index].code.toUShort()
    this[text.length] = 0u
}
