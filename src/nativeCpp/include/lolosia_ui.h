/*
 * 洛洛希雅桌面 UI 的 C ABI 门面。
 *
 * 视图层用 RmlUi：RML + RCSS 描述结构与样式（layout 走 flexbox，不手算 x/y），
 * data-model 做数据绑定。Kotlin 侧只通过本文件的函数驱动它，
 * 不接触 RmlUi / SDL3 的头文件与指针；RmlUi 的后端胶水与渲染细节都在 liblolosia 内部。
 *
 * 典型顺序（每一步失败都用 ll_ui_last_error() 取原因）：
 *
 *     ll_ui_create("标题", 1280, 800);
 *     ll_ui_load_font("C:/Windows/Fonts/msyh.ttc");      // 没有字体画不出字
 *     ll_ui_model_bind_string("title", "初始值");          // 先声明模型字段……
 *     ll_ui_model_bind_int("count", 0);
 *     ll_ui_set_action_callback(&onAction);              // 文档里 action('x') 会回到这里
 *     ll_ui_load_document(rml_text);                     // ……再加载引用它们的文档
 *     while (ll_ui_frame() == 0) { }                     // 事件 / 更新 / 渲染 / 呈现
 *     ll_ui_destroy();
 *
 * 约定：
 *   - 返回 int32_t 的函数以 0 表示成功、负数表示失败；ll_ui_frame() 例外（0 = 继续，1 = 关闭）；
 *   - 所有字符串参数按 UTF-8 传入，返回的字符串指针由库内部持有，调用方只读、不释放；
 *   - 单实例：一个进程同时只允许一个窗口，create / destroy 成对使用。
 */
#ifndef LOLOSIA_UI_H
#define LOLOSIA_UI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * 创建窗口、初始化 RmlUi 与它的数据模型；成功返回 0。
 *
 * width / height 是**逻辑尺寸（dp）**：窗口的实际像素尺寸由后端按显示器缩放乘出来
 * （150% 缩放下 1120dp → 1680px），dp 比与 context 尺寸在内部对齐，调用方不用管。
 */
int32_t ll_ui_create(const char* title, int32_t width, int32_t height);

/* 释放窗口、上下文与全部 RmlUi 资源 */
void ll_ui_destroy(void);

/*
 * 从文件加载字体（ttf / otf / ttc），并把它注册成指定家族名；没有可用字体时所有文字都画不出来。
 * 家族名由调用方定（RCSS 里 font-family 写同一个名字即可），因此不依赖字体内部的家族信息。
 * 该字体同时注册为兜底字面：其他家族找不到字形时会退到它。
 */
int32_t ll_ui_load_font(const char* file_path, const char* family);

/*
 * 数据模型字段声明：必须在 ll_ui_load_document() 之前完成，
 * 否则文档里的 data-bind-* 找不到变量。重名会覆盖此前声明。
 */
int32_t ll_ui_model_bind_string(const char* name, const char* initial);
int32_t ll_ui_model_bind_int(const char* name, int32_t initial);
int32_t ll_ui_model_bind_float(const char* name, float initial);
int32_t ll_ui_model_bind_bool(const char* name, int32_t initial);

/* 更新已声明字段并通知视图重绘；名字不存在返回负数 */
int32_t ll_ui_model_set_string(const char* name, const char* value);
int32_t ll_ui_model_set_int(const char* name, int32_t value);
int32_t ll_ui_model_set_float(const char* name, float value);
int32_t ll_ui_model_set_bool(const char* name, int32_t value);

/*
 * 文档触发的动作回调（data-event-click="action('refresh')" 之类）。
 *
 * user_data 原样回传，供宿主携带自己的上下文（Kotlin 侧通常放一个 StableRef）；
 * name 由库内部持有，只在本次调用期间有效，需要留存请自行拷贝。
 * 回调在视图线程上同步执行，不要在里边再调 ll_ui_* 之外的阻塞操作。
 */
typedef void (*ll_ui_action_callback)(void* user_data, const char* name);
void ll_ui_set_action_callback(ll_ui_action_callback callback, void* user_data);

/* 加载 RML 文档（样式可用内联 <style>）；已加载的旧文档会被替换 */
int32_t ll_ui_load_document(const char* rml_utf8);

/*
 * 资源回调：把 RmlUi 的「读文件」与「解码图片」接到宿主侧。
 *
 * 为什么要回调而不是在 C++ 里自己解码：文件要从 classpath（内嵌 zip）读、解码复用仓库里
 * 已有的 C 编解码库（libpng / libjpeg-turbo / libwebp / giflib，见 src/nativeMain 的门面）。
 * 这两件事宿主都已经有实现，回调过去就不用把解码器再写一遍，也不给链接顺序添新依赖。
 *
 * 两个回调都按「两次调用」约定工作，调用方据此避免先猜缓冲大小：
 *   - out 为 NULL 或 capacity 不足：不写数据，只返回**所需字节数**（用于探大小）；
 *   - 容量足够：写入数据并返回**写入字节数**。
 *   返回负数表示失败（文件不存在、格式不认识、数据损坏等）。
 * 回调在视图线程上同步调用；已在 ll_ui_create 之后、ll_ui_load_document 之前注册即可。
 *
 * 用不到图片时不注册也能跑：引用到图片的地方会缺图并留一条 ERROR 日志，不会崩。
 */
typedef int32_t (*ll_ui_read_file)(void* user_data, const char* path, uint8_t* out, int32_t capacity);

/* 解码回调额外回写像素尺寸（两次调用都会写）；out_rgba 的预期大小为 width * height * 4。 */
typedef int32_t (*ll_ui_decode_image)(void* user_data, const uint8_t* bytes, int32_t size,
                                      uint8_t* out_rgba, int32_t capacity,
                                      int32_t* out_width, int32_t* out_height);

void ll_ui_set_resource_loader(ll_ui_read_file read_file, ll_ui_decode_image decode_image, void* user_data);

/*
 * 跑一帧：处理输入事件 → 更新 → 渲染 → 呈现。
 * 空闲时会阻塞等事件（不空转烧 CPU）。返回 0 表示继续，1 表示收到关闭请求，负数表示出错。
 */
int32_t ll_ui_frame(void);

/* 最近一次失败的原因；无失败时返回空串 */
const char* ll_ui_last_error(void);

#ifdef __cplusplus
}
#endif

#endif /* LOLOSIA_UI_H */
