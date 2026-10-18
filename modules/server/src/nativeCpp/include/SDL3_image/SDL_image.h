/*
 * SDL3_image 的替代声明 —— 不是 SDL_image 的完整头，只声明本项目的实现真正需要的那一个函数。
 *
 * 为什么会有这个文件：
 *   RmlUi 的 SDL_GPU 渲染后端（Backends/RmlUi_Renderer_SDL_GPU.cpp）在加载图片纹理时会
 *   `#include <SDL3_image/SDL_image.h>` 并调用 IMG_LoadTyped_IO 把图片解成 SDL_Surface。
 *   本项目不打算引入整套 SDL_image + stb_image（与「轻量化」相悖，且解码能力仓库里已经有了），
 *   而是自己实现这一个函数：字节交给宿主的解码回调（Kotlin 侧复用 libpng / libjpeg-turbo /
 *   libwebp / giflib 的门面），拿回 RGBA 包成 SDL_Surface。
 *
 *   实现见 src/SdlImage.cpp，回调的注册见 lolosia_ui.h 的 ll_ui_set_resource_loader。
 *
 * 调用方的容错：返回 nullptr 时 RmlUi 走 `return 0;`（无效纹理句柄），
 * 也就是「图缺失」而不是崩溃，日志里能看到具体原因。
 */
#ifndef LOLOSIA_SDL3_IMAGE_COMPAT_H
#define LOLOSIA_SDL3_IMAGE_COMPAT_H

#include <SDL3/SDL.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 与 SDL_image 3 的同名函数保持签名一致；closeio 为真时本实现会负责关闭 src */
extern SDL_Surface* IMG_LoadTyped_IO(SDL_IOStream* src, bool closeio, const char* type);

#ifdef __cplusplus
}
#endif

#endif /* LOLOSIA_SDL3_IMAGE_COMPAT_H */
