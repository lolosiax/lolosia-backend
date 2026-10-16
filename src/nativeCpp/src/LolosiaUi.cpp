// LolosiaUi.cpp —— ll_ui_* 的实现：SDL3 窗口 + SDL3 GPU（Windows / Linux 上即 Vulkan）的帧循环。
//
// 这一层刻意保持「没有 C++ 运行时依赖」：不用 STL、不用异常与 RTTI（编译参数 -fno-exceptions -fno-rtti），
// 状态放文件级静态变量，字符串只落固定缓冲。Kotlin/Native 侧因此不必额外链 libstdc++。
// 后续接入 RmlUi / FreeType 时，它们各自需要的 C++ 运行时在 build.ps1 里单独补链接参数。

#define SDL_MAIN_HANDLED 1

#include "lolosia_ui.h"

#include <SDL3/SDL.h>
// 入口点由 Kotlin/Native 提供，SDL 只管子系统初始化：这里要的是 SDL_SetMainReady 的声明
// （SDL.h 不拉 SDL_main.h），调用它才会把主线程 ID 登记给 SDL。
#include <SDL3/SDL_main.h>
#include <stdio.h>
#include <string.h>

namespace {

/** 单窗口状态；SDL 窗口与 GPU 设备都挂在这里，由 ll_ui_create / ll_ui_destroy 成对管理。 */
struct UiState {
    SDL_Window* window;
    SDL_GPUDevice* device;
    SDL_GPUCommandBuffer* commands;
    SDL_GPUTexture* swapchain;
    bool initialized;
    bool claimed;
    bool quit_requested;
    bool frame_open;
    int32_t frame_width;
    int32_t frame_height;
    SDL_FColor clear_color;
    char error[512];
};

UiState g_state = {};

void setError(const char* detail) {
    snprintf(g_state.error, sizeof(g_state.error), "%s", detail);
    g_state.error[sizeof(g_state.error) - 1] = '\0';
}

/** 把 SDL 的最近一次错误拼进 last_error，形如「前缀：SDL 报错」。 */
void setSdlError(const char* prefix) {
    snprintf(g_state.error, sizeof(g_state.error), "%s：%s", prefix, SDL_GetError());
    g_state.error[sizeof(g_state.error) - 1] = '\0';
}

/** 取 UTF-8 串的首个码点，供 LL_UI_EVENT_TEXT 使用；序列被截断时返回 0（调用方据此丢弃该事件）。 */
uint32_t firstCodepoint(const char* text) {
    if (text == NULL || text[0] == '\0') return 0;
    const unsigned char* bytes = (const unsigned char*)text;
    const unsigned char c0 = bytes[0];
    if (c0 < 0x80) return c0;
    if ((c0 & 0xE0) == 0xC0) {
        if (bytes[1] == '\0') return 0;
        return ((uint32_t)(c0 & 0x1F) << 6) | (uint32_t)(bytes[1] & 0x3F);
    }
    if ((c0 & 0xF0) == 0xE0) {
        if (bytes[1] == '\0' || bytes[2] == '\0') return 0;
        return ((uint32_t)(c0 & 0x0F) << 12) | ((uint32_t)(bytes[1] & 0x3F) << 6) | (uint32_t)(bytes[2] & 0x3F);
    }
    if ((c0 & 0xF8) == 0xF0) {
        if (bytes[1] == '\0' || bytes[2] == '\0' || bytes[3] == '\0') return 0;
        return ((uint32_t)(c0 & 0x07) << 18) | ((uint32_t)(bytes[1] & 0x3F) << 12) |
               ((uint32_t)(bytes[2] & 0x3F) << 6) | (uint32_t)(bytes[3] & 0x3F);
    }
    return 0;
}

/** SDL 事件 → 扁平事件；不关心的类型返回 false。 */
bool translate(const SDL_Event& event, ll_ui_event* out) {
    switch (event.type) {
        case SDL_EVENT_QUIT:
        case SDL_EVENT_WINDOW_CLOSE_REQUESTED:
            out->kind = LL_UI_EVENT_QUIT;
            return true;
        case SDL_EVENT_WINDOW_RESIZED:
        case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
            out->kind = LL_UI_EVENT_RESIZE;
            out->x = event.window.data1;
            out->y = event.window.data2;
            return true;
        case SDL_EVENT_MOUSE_MOTION:
            out->kind = LL_UI_EVENT_MOUSE_MOVE;
            out->x = (int32_t)event.motion.x;
            out->y = (int32_t)event.motion.y;
            out->dx = (int32_t)event.motion.xrel;
            out->dy = (int32_t)event.motion.yrel;
            return true;
        case SDL_EVENT_MOUSE_BUTTON_DOWN:
        case SDL_EVENT_MOUSE_BUTTON_UP:
            out->kind = event.type == SDL_EVENT_MOUSE_BUTTON_DOWN ? LL_UI_EVENT_MOUSE_DOWN : LL_UI_EVENT_MOUSE_UP;
            out->x = (int32_t)event.button.x;
            out->y = (int32_t)event.button.y;
            out->code = event.button.button;
            return true;
        case SDL_EVENT_MOUSE_WHEEL:
            // 一格滚轮按 120 归一，与常见 UI 框架的刻度一致
            out->kind = LL_UI_EVENT_WHEEL;
            out->dx = (int32_t)(event.wheel.x * 120.0f);
            out->dy = (int32_t)(event.wheel.y * 120.0f);
            return true;
        case SDL_EVENT_KEY_DOWN:
        case SDL_EVENT_KEY_UP:
            out->kind = event.type == SDL_EVENT_KEY_DOWN ? LL_UI_EVENT_KEY_DOWN : LL_UI_EVENT_KEY_UP;
            out->code = (int32_t)event.key.key;
            out->x = (int32_t)event.key.mod;
            return true;
        case SDL_EVENT_TEXT_INPUT:
            out->kind = LL_UI_EVENT_TEXT;
            out->codepoint = firstCodepoint(event.text.text);
            return out->codepoint != 0;
        default:
            return false;
    }
}

}  // namespace

int32_t ll_ui_create(const char* title, int32_t width, int32_t height) {
    if (g_state.initialized) {
        setError("ll_ui 已初始化，同一进程只允许一个窗口");
        return -1;
    }
    g_state.error[0] = '\0';
    g_state.clear_color = SDL_FColor{0.09f, 0.09f, 0.12f, 1.0f};

    SDL_SetMainReady();
    if (!SDL_Init(SDL_INIT_VIDEO)) {
        setSdlError("SDL_Init 失败");
        return -1;
    }
    g_state.initialized = true;

    g_state.window = SDL_CreateWindow(title, width, height, SDL_WINDOW_RESIZABLE);
    if (g_state.window == NULL) {
        setSdlError("SDL_CreateWindow 失败");
        ll_ui_destroy();
        return -1;
    }

    // 只要有 Vulkan（SPIR-V 是 Vulkan 的着色器格式）。拿不到设备不算致命：
    // 窗口照常存在，last_error 留下原因，绘制相关调用降级为空操作。
    g_state.device = SDL_CreateGPUDevice(SDL_GPU_SHADERFORMAT_SPIRV, false, NULL);
    if (g_state.device == NULL) {
        setSdlError("SDL_CreateGPUDevice(SPIRV) 失败，窗口可显示但不出帧");
        return 0;
    }
    if (!SDL_ClaimWindowForGPUDevice(g_state.device, g_state.window)) {
        setSdlError("SDL_ClaimWindowForGPUDevice 失败，窗口可显示但不出帧");
        SDL_DestroyGPUDevice(g_state.device);
        g_state.device = NULL;
        return 0;
    }
    g_state.claimed = true;
    return 0;
}

int32_t ll_ui_poll(ll_ui_event* out, int32_t timeout_ms) {
    if (!g_state.initialized || out == NULL) return -1;
    if (g_state.quit_requested) return -1;

    SDL_Event event;
    if (!SDL_WaitEventTimeout(&event, timeout_ms)) return 0;

    // WaitEventTimeout 只取一个事件，同批事件留给下一次调用（调用方用 timeout_ms = 0 排空积压）
    do {
        if (event.type == SDL_EVENT_QUIT || event.type == SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
            g_state.quit_requested = true;
            return -1;
        }
        memset(out, 0, sizeof(ll_ui_event));
        if (translate(event, out)) return 1;
    } while (SDL_PollEvent(&event));
    return 0;
}

int32_t ll_ui_begin_frame(int32_t* out_width, int32_t* out_height) {
    if (!g_state.initialized) return -1;
    g_state.swapchain = NULL;
    g_state.frame_width = 0;
    g_state.frame_height = 0;

    if (g_state.device == NULL) {
        // 无 GPU 设备：只回报窗口尺寸，不产帧
        SDL_GetWindowSizeInPixels(g_state.window, &g_state.frame_width, &g_state.frame_height);
    } else {
        g_state.commands = SDL_AcquireGPUCommandBuffer(g_state.device);
        if (g_state.commands == NULL) {
            setSdlError("SDL_AcquireGPUCommandBuffer 失败");
            return -1;
        }
        Uint32 width = 0;
        Uint32 height = 0;
        if (!SDL_WaitAndAcquireGPUSwapchainTexture(
                g_state.commands, g_state.window, &g_state.swapchain, &width, &height)) {
            // 窗口最小化等情形取不到交换链纹理：本帧跳过绘制，命令缓冲仍要提交
            setSdlError("SDL_WaitAndAcquireGPUSwapchainTexture 失败");
            g_state.swapchain = NULL;
        }
        g_state.frame_width = (int32_t)width;
        g_state.frame_height = (int32_t)height;
    }

    if (out_width != NULL) *out_width = g_state.frame_width;
    if (out_height != NULL) *out_height = g_state.frame_height;
    g_state.frame_open = true;
    return 0;
}

int32_t ll_ui_clear(float r, float g, float b, float a) {
    if (!g_state.frame_open) return -1;
    g_state.clear_color = SDL_FColor{r, g, b, a};
    return 0;
}

int32_t ll_ui_end_frame(void) {
    if (!g_state.frame_open) return -1;
    g_state.frame_open = false;

    if (g_state.device == NULL) return 0;

    if (g_state.swapchain != NULL) {
        SDL_GPUColorTargetInfo target = {};
        target.texture = g_state.swapchain;
        target.clear_color = g_state.clear_color;
        target.load_op = SDL_GPU_LOADOP_CLEAR;
        target.store_op = SDL_GPU_STOREOP_STORE;

        SDL_GPURenderPass* pass = SDL_BeginGPURenderPass(g_state.commands, &target, 1, NULL);
        if (pass == NULL) {
            setSdlError("SDL_BeginGPURenderPass 失败");
            SDL_CancelGPUCommandBuffer(g_state.commands);
            g_state.commands = NULL;
            return -1;
        }
        // 控件绘制将插在这里；本帧只有清屏
        SDL_EndGPURenderPass(pass);
    }

    if (!SDL_SubmitGPUCommandBuffer(g_state.commands)) {
        setSdlError("SDL_SubmitGPUCommandBuffer 失败");
        g_state.commands = NULL;
        return -1;
    }
    g_state.commands = NULL;
    return 0;
}

const char* ll_ui_backend(void) {
    if (g_state.device == NULL) return "";
    const char* name = SDL_GetGPUDeviceDriver(g_state.device);
    return name == NULL ? "" : name;
}

const char* ll_ui_last_error(void) {
    return g_state.error;
}

void ll_ui_destroy(void) {
    if (g_state.device != NULL && g_state.claimed && g_state.window != NULL) {
        SDL_ReleaseWindowFromGPUDevice(g_state.device, g_state.window);
    }
    g_state.claimed = false;
    g_state.swapchain = NULL;
    if (g_state.device != NULL) {
        SDL_DestroyGPUDevice(g_state.device);
        g_state.device = NULL;
    }
    if (g_state.window != NULL) {
        SDL_DestroyWindow(g_state.window);
        g_state.window = NULL;
    }
    if (g_state.initialized) {
        SDL_Quit();
    }
    g_state.initialized = false;
    g_state.quit_requested = false;
    g_state.frame_open = false;
}
