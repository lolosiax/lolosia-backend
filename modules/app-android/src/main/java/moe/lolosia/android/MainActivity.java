package moe.lolosia.android;

import org.libsdl.app.SDLActivity;

/**
 * Android 入口：直接复用 SDL3 的 Activity / Surface 生命周期。
 *
 * SDLActivity 会加载 {@code getLibraries()} 里的共享库，再通过 dlsym 调用最后一个库里的
 * {@code SDL_main}（见 {@code org.libsdl.app.SDLActivity#getMainSharedObject}）。
 * liblolosia.so 就是这个入口，面板渲染与输入都在它的帧循环里。
 */
public class MainActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "lolosia" };
    }
}
