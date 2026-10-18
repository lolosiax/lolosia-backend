package moe.lolosia.android;

import android.os.Bundle;
import android.view.KeyEvent;

import org.libsdl.app.SDLActivity;

import moe.lolosia.android.ui.AndroidUiHost;

/**
 * Android 入口：直接复用 SDL3 的 Activity / Surface 生命周期。
 *
 * SDLActivity 会加载 {@code getLibraries()} 里的共享库，再通过 dlsym 调用最后一个库里的
 * {@code SDL_main}（见 {@code org.libsdl.app.SDLActivity#getMainSharedObject}）。
 * liblolosia.so 是这个入口，窗口与帧循环在它的 SDL 线程里；Kotlin 的页面状态在 Activity
 * 主线程，通过 JNI 命令队列驱动同一套 RmlUi 数据模型。
 */
public class MainActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "lolosia" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // super.onCreate 里已经 loadLibraries()，此时 JNI 符号可用。
        AndroidUiHost.start(this);
    }

    /**
     * SDLActivity 会把硬件返回键直接送进 SDL（不走 onBackPressed），所以这里先截下来：
     * 松开时交给 UI 决定是「控件库 → 我的」还是退出。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            AndroidUiHost.onBack(this);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // 手势返回 / 旧式返回：控件库先回「我的」，其余情况退出。
        AndroidUiHost.onBack(this);
    }
}
