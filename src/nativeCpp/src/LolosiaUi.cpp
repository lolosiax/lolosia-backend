// LolosiaUi.cpp —— ll_ui_* 的实现：RmlUi 的 SDL_GPU 后端 + 数据模型 + 帧循环。
//
// 分工：
//   - 窗口、GPU 设备、输入事件与 DPI 缩放、渲染接口全部交给 RmlUi 自带的后端
//     （Backends/RmlUi_Backend_SDL_GPU.cpp 等三件套，见 src/nativeCpp/build.ps1 的编译清单）；
//   - 本文件只做「把 RmlUi 的 C++ API 收成一套 C ABI」这件事：状态、错误信息、
//     数据模型字段的转发表、以及把文档里的 action(...) 转发回宿主。
//
// 数据模型字段为什么要转发表：RmlUi 的 Bind(name, ptr) 绑的是「变量的地址」，
// 指针必须在模型存活期间一直有效，而字段名是宿主运行时给的。
// 于是这里用固定容量的一组静态槽位存值，按名字查下标。

#define SDL_MAIN_HANDLED 1

#include "lolosia_ui.h"
#include "LolosiaUiInternal.h"

#include "RmlUi_Backend.h"

#include <RmlUi/Core.h>
#include <SDL3/SDL.h>
#include <stdio.h>
#include <string.h>

namespace {

/** 数据模型名，与文档里的 data-model="main" 对应。 */
constexpr const char* kContextName = "main";
constexpr const char* kModelName = "main";

/** 数据模型字段上限：固定容量换「无动态分配」，超出时明确报错而不是静默丢弃。 */
constexpr int kMaxVars = 32;

enum class VarKind { String, Int, Float, Bool };

struct VarSlot {
    Rml::String name;
    VarKind kind;
};

/** 单窗口状态；生命周期由 ll_ui_create / ll_ui_destroy 划定。 */
struct UiState {
    Rml::Context* context;
    Rml::ElementDocument* document;
    Rml::DataModelConstructor model;
    Rml::DataModelHandle handle;
    VarSlot slots[kMaxVars];
    int slot_count;
    Rml::String strings[kMaxVars];
    int32_t ints[kMaxVars];
    float floats[kMaxVars];
    bool bools[kMaxVars];
    ll_ui_action_callback action;
    void* action_user_data;
    bool created;
    char error[512];
};

UiState g_state = {};

void setError(const char* detail) {
    snprintf(g_state.error, sizeof(g_state.error), "%s", detail);
    g_state.error[sizeof(g_state.error) - 1] = '\0';
}

void setNamedError(const char* prefix, const char* name) {
    snprintf(g_state.error, sizeof(g_state.error), "%s：%s", prefix, name == nullptr ? "(空)" : name);
    g_state.error[sizeof(g_state.error) - 1] = '\0';
}

void clearError() { g_state.error[0] = '\0'; }

/** 按名字找槽位；要求类型一致，避免把 int 字段当 string 写。返回 -1 表示没有可用槽位。 */
int findSlot(const char* name, VarKind kind) {
    if (name == nullptr || name[0] == '\0') return -1;
    for (int i = 0; i < g_state.slot_count; ++i) {
        if (g_state.slots[i].name == name && g_state.slots[i].kind == kind) return i;
    }
    return -1;
}

/** 取一个槽位（重名同类型则复用，否则新占一个）；返回 -1 表示已满或名字非法。 */
int acquireSlot(const char* name, VarKind kind) {
    if (name == nullptr || name[0] == '\0') return -1;
    for (int i = 0; i < g_state.slot_count; ++i) {
        if (g_state.slots[i].name == name) return g_state.slots[i].kind == kind ? i : -1;
    }
    if (g_state.slot_count >= kMaxVars) return -1;
    const int index = g_state.slot_count++;
    g_state.slots[index].name = name;
    g_state.slots[index].kind = kind;
    return index;
}

/** 按名字找到已声明的槽位下标；找不到或类型不符时写错误信息。 */
int requireSlot(const char* name, VarKind kind) {
    const int index = findSlot(name, kind);
    if (index < 0) {
        setNamedError("数据模型里没有这个字段（或类型不符）", name);
        return -1;
    }
    return index;
}

/** 文档里的 action('x') 落到这里，再转发给宿主注册的回调。 */
void onAction(Rml::DataModelHandle, Rml::Event&, const Rml::VariantList& arguments) {
    if (g_state.action == nullptr) return;
    Rml::String name;
    if (!arguments.empty() && arguments[0].GetType() == Rml::Variant::STRING) {
        name = arguments[0].Get<Rml::String>();
    }
    g_state.action(g_state.action_user_data, name.c_str());
}

}  // namespace

LlResourceCallbacks& ll_resource_callbacks() {
    static LlResourceCallbacks callbacks = {nullptr, nullptr, nullptr};
    return callbacks;
}

/**
 * 往 RmlUi 的日志通道打一条。走系统接口而不是 Rml::Log::Message：
 * 后者受 RmlUi 的日志级别过滤（默认会把 INFO 滤掉），而系统接口是后端自己报
 * 「Using SDL device driver」用的通道，能稳定落到 stderr。
 */
static void logToSystem(Rml::Log::Type level, const char* message) {
    Rml::SystemInterface* system = Backend::GetSystemInterface();
    if (system != nullptr) system->LogMessage(level, message);
}

void ll_log_info(const char* message) { logToSystem(Rml::Log::LT_INFO, message); }
void ll_log_error(const char* message) { logToSystem(Rml::Log::LT_ERROR, message); }

int32_t ll_ui_create(const char* title, int32_t width, int32_t height) {
    if (g_state.created) {
        setError("ll_ui 已初始化，同一进程只允许一个窗口");
        return -1;
    }
    clearError();
    g_state.slot_count = 0;
    g_state.action = nullptr;
    g_state.action_user_data = nullptr;
    g_state.document = nullptr;
    g_state.context = nullptr;
    g_state.created = true;

    // 后端自己开窗、建 GPU 设备、接管输入，并把 SDL 事件翻译成 RmlUi 输入
    if (!Backend::Initialize(title == nullptr ? "lolosia" : title, width, height, true)) {
        setError("Backend::Initialize 失败：窗口或 GPU 设备创建不出来");
        ll_ui_destroy();
        return -1;
    }

    // 渲染/系统/文件接口都必须在 Rml::Initialise() 之前挂上。
    // 文件接口换成「先问宿主、再退回磁盘」的实现：界面资源在 classpath（内嵌 zip）里，
    // 而字体这类东西仍是磁盘文件，两者都要能读到。
    Rml::SetSystemInterface(Backend::GetSystemInterface());
    Rml::SetRenderInterface(Backend::GetRenderInterface());
    Rml::SetFileInterface(ll_resource_file_interface());
    if (!Rml::Initialise()) {
        setError("Rml::Initialise 失败");
        ll_ui_destroy();
        return -1;
    }

    g_state.context = Rml::CreateContext(kContextName, Rml::Vector2i(width, height));
    if (g_state.context == nullptr) {
        setError("Rml::CreateContext 失败");
        ll_ui_destroy();
        return -1;
    }

    // 先建模型再加载文档：文档里的 data-bind-* / data-model 在解析时就要能找到变量
    g_state.model = g_state.context->CreateDataModel(kModelName);
    if (!g_state.model) {
        setError("CreateDataModel 失败");
        ll_ui_destroy();
        return -1;
    }
    g_state.handle = g_state.model.GetModelHandle();

    // 把 context 的视口尺寸与 dp 比对齐到窗口的真实像素尺寸。
    //
    // 约定（来自 RmlUi 自己的实现，别按 CSS 的直觉猜）：
    //   Context::SetDimensions 的尺寸直接当**像素**用 —— 它同时是渲染视口与文档根盒的尺寸；
    //   SetDensityIndependentPixelRatio 才是 dp→px 的乘数（ComputeLength 里 Unit::DP × dp_ratio）。
    //
    // 坑：RmlUi 的 SDL 后端只在「显示缩放变化」事件里设 dp 比，窗口刚建好时不会设，
    // 而它的示例把逻辑尺寸当 dp 直接交给 CreateContext。在 100% 缩放下两者恰好相等，看不出问题；
    // 150% 缩放下窗口是 1680x1140 像素，若视口只给 1120x760，内容就只铺满左上角一块，
    // 其余区域是后端清出的透明黑 —— 表现就是「可视区域没有覆盖整个窗口」。
    {
        SDL_Window* window = nullptr;
        int window_count = 0;
        SDL_Window** windows = SDL_GetWindows(&window_count);
        if (windows != nullptr && window_count > 0) window = windows[0];

        int pixel_width = 0;
        int pixel_height = 0;
        float dp_ratio = 1.0f;
        if (window != nullptr) {
            SDL_GetWindowSizeInPixels(window, &pixel_width, &pixel_height);
            const float display_scale = SDL_GetWindowDisplayScale(window);
            if (display_scale > 0.0f) dp_ratio = display_scale;
        }
        if (pixel_width <= 0 || pixel_height <= 0) {
            // 拿不到窗口像素尺寸时退回调用方给的逻辑尺寸（等于按 100% 缩放处理）
            pixel_width = width;
            pixel_height = height;
            dp_ratio = 1.0f;
        }

        g_state.context->SetDimensions(Rml::Vector2i(pixel_width, pixel_height));
        g_state.context->SetDensityIndependentPixelRatio(dp_ratio);

        char message[192];
        snprintf(message, sizeof(message),
            "渲染视口 %dx%d 像素（%.2f dp 比，即 %.0fx%.0f dp）",
            pixel_width, pixel_height, (double)dp_ratio,
            (double)(pixel_width / dp_ratio), (double)(pixel_height / dp_ratio));
        ll_log_info(message);
    }

    // 注册名为 action 的数据事件：文档里的 data-event-click="action('x')" 全靠它派发。
    // 这一步失败必须报出来 —— 否则症状是「按钮点了完全没反应」，且没有任何线索可查。
    if (!g_state.model.BindEventCallback("action", &onAction)) {
        setError("BindEventCallback(action) 失败：文档里的 action(...) 不会触发");
        ll_ui_destroy();
        return -1;
    }
    return 0;
}

int32_t ll_ui_load_font(const char* file_path, const char* family) {
    if (file_path == nullptr || file_path[0] == '\0') {
        setError("字体路径为空");
        return -1;
    }
    if (family == nullptr || family[0] == '\0') {
        setError("字体家族名为空");
        return -1;
    }
    // 指定家族名 + fallback_face：RCSS 里写 font-family: <family> 就能命中，
    // 其他家族缺字形时也会退到它
    if (!Rml::LoadFontFace(file_path, family, Rml::Style::FontStyle::Normal, Rml::Style::FontWeight::Auto, true)) {
        setNamedError("加载字体失败", file_path);
        return -1;
    }
    clearError();
    return 0;
}

int32_t ll_ui_model_bind_string(const char* name, const char* initial) {
    const int index = acquireSlot(name, VarKind::String);
    if (index < 0) {
        setNamedError("数据模型字段声明失败（槽位已满、名字为空或与已有字段类型冲突）", name);
        return -1;
    }
    g_state.strings[index] = initial == nullptr ? "" : initial;
    if (!g_state.model.Bind(name, &g_state.strings[index])) {
        setNamedError("Bind(string) 失败", name);
        return -1;
    }
    clearError();
    return 0;
}

int32_t ll_ui_model_bind_int(const char* name, int32_t initial) {
    const int index = acquireSlot(name, VarKind::Int);
    if (index < 0) {
        setNamedError("数据模型字段声明失败（槽位已满、名字为空或与已有字段类型冲突）", name);
        return -1;
    }
    g_state.ints[index] = initial;
    if (!g_state.model.Bind(name, &g_state.ints[index])) {
        setNamedError("Bind(int) 失败", name);
        return -1;
    }
    clearError();
    return 0;
}

int32_t ll_ui_model_bind_float(const char* name, float initial) {
    const int index = acquireSlot(name, VarKind::Float);
    if (index < 0) {
        setNamedError("数据模型字段声明失败（槽位已满、名字为空或与已有字段类型冲突）", name);
        return -1;
    }
    g_state.floats[index] = initial;
    if (!g_state.model.Bind(name, &g_state.floats[index])) {
        setNamedError("Bind(float) 失败", name);
        return -1;
    }
    clearError();
    return 0;
}

int32_t ll_ui_model_bind_bool(const char* name, int32_t initial) {
    const int index = acquireSlot(name, VarKind::Bool);
    if (index < 0) {
        setNamedError("数据模型字段声明失败（槽位已满、名字为空或与已有字段类型冲突）", name);
        return -1;
    }
    g_state.bools[index] = initial != 0;
    if (!g_state.model.Bind(name, &g_state.bools[index])) {
        setNamedError("Bind(bool) 失败", name);
        return -1;
    }
    clearError();
    return 0;
}

int32_t ll_ui_model_set_string(const char* name, const char* value) {
    const int index = requireSlot(name, VarKind::String);
    if (index < 0) return -1;
    g_state.strings[index] = value == nullptr ? "" : value;
    g_state.handle.DirtyVariable(name);
    clearError();
    return 0;
}

int32_t ll_ui_model_set_int(const char* name, int32_t value) {
    const int index = requireSlot(name, VarKind::Int);
    if (index < 0) return -1;
    g_state.ints[index] = value;
    g_state.handle.DirtyVariable(name);
    clearError();
    return 0;
}

int32_t ll_ui_model_set_float(const char* name, float value) {
    const int index = requireSlot(name, VarKind::Float);
    if (index < 0) return -1;
    g_state.floats[index] = value;
    g_state.handle.DirtyVariable(name);
    clearError();
    return 0;
}

int32_t ll_ui_model_set_bool(const char* name, int32_t value) {
    const int index = requireSlot(name, VarKind::Bool);
    if (index < 0) return -1;
    g_state.bools[index] = value != 0;
    g_state.handle.DirtyVariable(name);
    clearError();
    return 0;
}

void ll_ui_set_action_callback(ll_ui_action_callback callback, void* user_data) {
    g_state.action = callback;
    g_state.action_user_data = user_data;
}

void ll_ui_set_resource_loader(ll_ui_read_file read_file, ll_ui_decode_image decode_image, void* user_data) {
    LlResourceCallbacks& callbacks = ll_resource_callbacks();
    callbacks.read_file = read_file;
    callbacks.decode_image = decode_image;
    callbacks.user_data = user_data;
}

int32_t ll_ui_load_document(const char* rml_utf8) {
    if (g_state.context == nullptr) {
        setError("上下文尚未初始化");
        return -1;
    }
    if (rml_utf8 == nullptr || rml_utf8[0] == '\0') {
        setError("文档内容为空");
        return -1;
    }

    // 换文档：旧的在下次 Update 时才真正销毁，这里先取消引用
    if (g_state.document != nullptr) {
        g_state.context->UnloadDocument(g_state.document);
        g_state.document = nullptr;
    }

    g_state.document = g_state.context->LoadDocumentFromMemory(rml_utf8, "lolosia://probe.rml");
    if (g_state.document == nullptr) {
        setError("LoadDocumentFromMemory 失败：RML 语法错误或引用了未声明的数据模型字段（详见上方 RmlUi 日志）");
        return -1;
    }
    g_state.document->Show();
    clearError();
    return 0;
}

int32_t ll_ui_frame(void) {
    if (g_state.context == nullptr) return -1;

    // power_save：没有事件、也不需要动画时阻塞等待（最多等下一帧更新），不空转烧 CPU
    if (!Backend::ProcessEvents(g_state.context, nullptr, true)) return 1;

    g_state.context->Update();
    Backend::BeginFrame();
    g_state.context->Render();
    Backend::PresentFrame();
    return 0;
}

const char* ll_ui_last_error(void) { return g_state.error; }

void ll_ui_destroy(void) {
    // 顺序要紧：先撤 RmlUi（要经渲染接口释放 GPU 资源），再关后端的设备与窗口
    if (g_state.context != nullptr) {
        Rml::RemoveContext(kContextName);
        g_state.context = nullptr;
    }
    g_state.document = nullptr;
    g_state.model = Rml::DataModelConstructor();
    if (g_state.created) {
        Rml::Shutdown();
        Backend::Shutdown();
    }
    g_state.created = false;
    g_state.slot_count = 0;
    g_state.action = nullptr;
    g_state.action_user_data = nullptr;
}
