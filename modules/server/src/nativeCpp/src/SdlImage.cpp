// SdlImage.cpp —— 把 RmlUi 要的图片解码接到宿主侧。
//
// 背景：RmlUi 的 SDL_GPU 渲染后端在实现 LoadTexture 时会调用 SDL_image 的
// IMG_LoadTyped_IO，把图片文件解成 SDL_Surface（见 Backends/RmlUi_Renderer_SDL_GPU.cpp）。
// 它只用到这一个函数。而在这个项目的分层里，「读文件」与「解码」都已经有实现：
// 文件在 classpath（发布时是内嵌 zip）里，解码是 src/nativeMain 下 libpng / libjpeg-turbo /
// libwebp / giflib 的门面（Kotlin 侧统一以 RgbaImage 收发）。
//
// 所以这里不重复实现解码器，而是把字节交给宿主的 decode_image 回调，拿回 RGBA 再包成
// SDL_Surface。用 RGBA32 是有意的：渲染后端对非 RGBA32 的表面会先做一次格式转换，
// 我们直接给 RGBA32 就省掉那一步。
//
// 失败一律返回 nullptr：RmlUi 那边会把无效句柄当「纹理缺失」处理（`return 0`），
// 界面缺图但不崩，日志里有具体原因。

#include "LolosiaUiInternal.h"

#include <SDL3/SDL.h>
#include <SDL3_image/SDL_image.h>

#include <stdio.h>
#include <string.h>
#include <vector>

extern "C" SDL_Surface* IMG_LoadTyped_IO(SDL_IOStream* src, bool closeio, const char* type) {
    const char* extension = (type == nullptr || type[0] == '\0') ? "(未知)" : type;

    // SDL_image 的约定：closeio 为真时由被调用方关闭流
    struct StreamGuard {
        SDL_IOStream* stream;
        bool owned;
        ~StreamGuard() {
            if (owned && stream != nullptr) SDL_CloseIO(stream);
        }
    } guard{src, closeio};

    auto fail = [extension](const char* reason) -> SDL_Surface* {
        char message[256];
        snprintf(message, sizeof(message), "图片加载失败（%s）：%s", extension, reason);
        ll_log_error(message);
        return nullptr;
    };

    if (src == nullptr) return fail("IO 流为空");

    const LlResourceCallbacks& callbacks = ll_resource_callbacks();
    if (callbacks.decode_image == nullptr) {
        return fail("宿主没注册解码回调（ll_ui_set_resource_loader）");
    }

    const Sint64 size = SDL_GetIOSize(src);
    if (size <= 0) return fail("取不到数据长度");

    std::vector<unsigned char> bytes(static_cast<size_t>(size));
    if (SDL_ReadIO(src, bytes.data(), bytes.size()) != bytes.size()) return fail("数据没读完");

    const int32_t byte_count = static_cast<int32_t>(bytes.size());

    // 两次调用：先只要尺寸 —— 宿主那边读文件头就够，不必先解一遍像素
    int32_t width = 0;
    int32_t height = 0;
    const int32_t needed =
        callbacks.decode_image(callbacks.user_data, bytes.data(), byte_count, nullptr, 0, &width, &height);
    if (needed <= 0 || width <= 0 || height <= 0) return fail("解码器认不出格式或数据已损坏");

    SDL_Surface* surface = SDL_CreateSurface(width, height, SDL_PIXELFORMAT_RGBA32);
    if (surface == nullptr) return fail("创建 SDL_Surface 失败");

    std::vector<unsigned char> rgba(static_cast<size_t>(needed));
    if (callbacks.decode_image(callbacks.user_data, bytes.data(), byte_count, rgba.data(), needed, &width,
            &height) != needed) {
        SDL_DestroySurface(surface);
        return fail("像素拷贝失败");
    }

    // 回调给的是紧凑 RGBA（行长 = width * 4）；SDL_Surface 的行长可能带对齐，所以逐行拷。
    // 渲染后端在做 alpha 预乘时是按 w*h*4 线性遍历的（它假定紧凑排列），因此行长不紧凑
    // 会导致颜色错乱 —— 真出现了要留个线索，别让它变成「颜色莫名其妙不对」。
    const size_t row_bytes = static_cast<size_t>(width) * 4u;
    if (surface->pitch != static_cast<int>(row_bytes)) {
        char message[192];
        snprintf(message, sizeof(message),
            "SDL_Surface 行长 %d 与图片宽度不符（应为 %d），渲染后端按紧凑排列处理，颜色可能不对",
            surface->pitch, static_cast<int>(row_bytes));
        ll_log_error(message);
    }
    for (int32_t y = 0; y < height; ++y) {
        memcpy(static_cast<unsigned char*>(surface->pixels) + static_cast<size_t>(y) * surface->pitch,
            rgba.data() + static_cast<size_t>(y) * row_bytes, row_bytes);
    }
    return surface;
}
