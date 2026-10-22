package moe.lolosia.android.ui;

/**
 * liblolosia.so 的 JNI 门面。
 *
 * 与桌面版 C ABI 一一对应，但改成命令式：调用只把命令投递到 native 队列，
 * 由 SDL 帧线程在下一帧开头消费 —— Kotlin 主线程不直接碰 RmlUi。
 *
 * 唯一需要同步结果的是 {@link #nativeGetString(String)}（表单回读），
 * 它内部等待帧线程处理完命令，最多 500ms。
 *
 * @author 洛洛希雅Lolosia
 */
public final class NativeUi {

    private NativeUi() {
    }

    /** 缓存 JavaVM 与动作回调；必须在任何 native → Java 回调之前调用。 */
    public static native void nativeInit();

    public static native void nativeBindString(String name, String value);

    public static native void nativeBindInt(String name, int value);

    public static native void nativeBindBool(String name, boolean value);

    public static native void nativeSetString(String name, String value);

    public static native void nativeSetInt(String name, int value);

    public static native void nativeSetBool(String name, boolean value);

    /** 同步读回模型字符串；字段不存在或帧线程 500ms 内没处理时返回 null。 */
    public static native String nativeGetString(String name);

    /** 从 APK assets 载入 RML（例如 ui/pages/home.rml）。 */
    public static native void nativeLoadDocument(String assetPath);

    /** 直接载入 RML 文本（番组表这类由 Kotlin 整页生成的页面）。 */
    public static native void nativeLoadDocumentText(String rml);

    /** 登记图片字节；path 与 RML 里的 src 一致（可带开头 `/`）。 */
    public static native void nativeRegisterImage(String path, byte[] bytes);

    /** 唤醒阻塞中的帧循环。 */
    public static native void nativeWake();

    /** native 侧文档动作回调（SDL 线程）→ 主线程。 */
    public static void onNativeAction(String name) {
        AndroidUiHost.onAction(name);
    }
}
