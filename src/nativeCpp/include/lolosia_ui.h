/*
 * 洛洛希雅桌面 UI 的 C ABI 门面。
 *
 * C++ 侧的 SDL3 / 图形栈只在本文件声明的边界内活动：Kotlin 侧通过 cinterop 绑定这些函数，
 * 不直接接触 SDL3 的头文件与指针。后续接入的布局 / 渲染库（RmlUi 等）同样在 liblolosia 内部
 * 调用，对外只增删本文件的声明。
 *
 * 约定：
 *   - 返回 int32_t 的函数以 0 表示成功、负数表示失败，失败原因用 ll_ui_last_error() 取；
 *   - 所有字符串指针均由库内部持有，调用方只读，不需要释放；
 *   - 单实例：一个进程同时只允许一个 ll_ui 窗口，create / destroy 成对使用。
 */
#ifndef LOLOSIA_UI_H
#define LOLOSIA_UI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 事件类型，取值与 Kotlin 侧 UiEventKind 一一对应 */
enum {
    LL_UI_EVENT_NONE = 0,
    LL_UI_EVENT_QUIT = 1,
    LL_UI_EVENT_RESIZE = 2,
    LL_UI_EVENT_MOUSE_DOWN = 3,
    LL_UI_EVENT_MOUSE_UP = 4,
    LL_UI_EVENT_MOUSE_MOVE = 5,
    LL_UI_EVENT_WHEEL = 6,
    LL_UI_EVENT_KEY_DOWN = 7,
    LL_UI_EVENT_KEY_UP = 8,
    LL_UI_EVENT_TEXT = 9
};

/* 一次输入事件的扁平表示：字段按类型取用，未用到的保持 0 */
typedef struct ll_ui_event {
    int32_t kind;
    int32_t x;
    int32_t y;
    int32_t dx;
    int32_t dy;
    int32_t code;
    uint32_t codepoint;
} ll_ui_event;

/* 创建窗口并初始化 GPU 设备；成功返回 0 */
int32_t ll_ui_create(const char* title, int32_t width, int32_t height);

/*
 * 取一个事件，最多等待 timeout_ms 毫秒。
 * 返回 1 表示 out 已填充；0 表示超时；-1 表示窗口已请求关闭（调用方应退出循环）。
 */
int32_t ll_ui_poll(ll_ui_event* out, int32_t timeout_ms);

/* 开始一帧：取当前帧的绘制目标，尺寸写入 out_width / out_height（可为 NULL） */
int32_t ll_ui_begin_frame(int32_t* out_width, int32_t* out_height);

/* 设置本帧清屏色，需在 ll_ui_begin_frame 之后、ll_ui_end_frame 之前调用 */
int32_t ll_ui_clear(float r, float g, float b, float a);

/* 结束一帧：提交并呈现 */
int32_t ll_ui_end_frame(void);

/* 实际生效的 GPU 后端名（如 vulkan）；未初始化时返回空串 */
const char* ll_ui_backend(void);

/* 最近一次失败的原因；无失败时返回空串 */
const char* ll_ui_last_error(void);

/* 释放窗口与 GPU 设备 */
void ll_ui_destroy(void);

#ifdef __cplusplus
}
#endif

#endif /* LOLOSIA_UI_H */
