/*
 * SDL3_image 的占位头文件 —— 不是 SDL_image 的完整头，只声明 RmlUi 的 SDL_GPU 渲染后端
 * 真正会用到的那个函数。
 *
 * 为什么要有这个文件：
 *   RmlUi_Renderer_SDL_GPU.cpp 会 `#include <SDL3_image/SDL_image.h>` 并调用
 *   IMG_LoadTyped_IO() 来把图片文件解成 SDL_Surface（供 background-image / decorator: image() 用）。
 *   本轮实验只做文字与盒子（控件、布局、数据绑定），不接图片，而为这一个函数编一整套
 *   SDL_image + stb_image 与「轻量化」的目标相悖，因此这里先放一个占位实现
 *   （见 src/SdlImageStub.cpp：关掉 IO 流、打一条明确的错误日志、返回 nullptr）。
 *
 * 调用方的容错：RmlUi 在这个函数返回 nullptr 时走 `return 0;`（无效纹理句柄），
 * 也就是「图缺失」而不是崩溃；日志里能看到我们打的那条 ERROR。
 *
 * 将来要支持图片时：把它替换成真正的 SDL_image（thirdparty/build-sdl3-image.ps1 之类），
 * 或复用仓库里已有的 C 编解码库在 SdlImageStub.cpp 里实现这个函数。
 */
#ifndef LOLOSIA_SDL3_IMAGE_PLACEHOLDER_H
#define LOLOSIA_SDL3_IMAGE_PLACEHOLDER_H

#include <SDL3/SDL.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 与 SDL_image 3 的同名函数保持签名一致；closeio 为真时本实现会负责关闭 src */
extern SDL_Surface* IMG_LoadTyped_IO(SDL_IOStream* src, bool closeio, const char* type);

#ifdef __cplusplus
}
#endif

#endif /* LOLOSIA_SDL3_IMAGE_PLACEHOLDER_H */
