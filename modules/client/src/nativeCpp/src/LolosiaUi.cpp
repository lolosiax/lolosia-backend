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
#include <RmlUi/Debugger.h>
#include <SDL3/SDL.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_SFNT_NAMES_H
#include <atomic>
#include <cstdint>
#include <ctype.h>
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
    bool debugger;
    bool created;
    uint32_t wake_event_type;
    /** 后端建好的窗口；取安全区与像素尺寸用，随 Backend::Shutdown 一起失效。 */
    SDL_Window* window;
    /** 上一次套到文档上的安全区边距（像素）；换成新文档时置 dirty 重套。 */
    int safe_left;
    int safe_top;
    int safe_right;
    int safe_bottom;
    bool safe_area_dirty;
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

/**
 * 按键钩子：后端把每个 key down 在交给文档之前、之后各调一次（由 [priority] 区分）。
 * 约定是「返回 true 放行、false 吃掉」（见 Backends/RmlUi_Backend.h 与上游 Samples/shell）。
 *
 * 只接管 F8：切换内置调试器的显示。没启用调试器时一律放行。
 */
bool onKeyDown(Rml::Context*, Rml::Input::KeyIdentifier key, int, float, bool priority) {
    if (!priority || !g_state.debugger) return true;
    if (key != Rml::Input::KI_F8) return true;

    const bool visible = !Rml::Debugger::IsVisible();
    Rml::Debugger::SetVisible(visible);
    ll_log_info(visible ? "调试器：显示（再按 F8 隐藏）" : "调试器：隐藏");
    return false;
}

}  // namespace

LlResourceCallbacks& ll_resource_callbacks() {
    static LlResourceCallbacks callbacks = {nullptr, nullptr, nullptr};
    return callbacks;
}

/**
 * 往 RmlUi 的日志通道打一条，然后冲一下 stderr。
 *
 * 为什么要走系统接口而不是 Rml::Log::Message：后者也是转到系统接口，绕不开下面的问题。
 * 为什么要 fflush：RmlUi 在非 Win32-native 构建下的默认实现是 fprintf(stderr)（见 Source/Core/LogDefault.cpp），
 * 而 stderr 被重定向到文件时是块缓冲的 —— 不冲一下，从字体加载到 RML/RCSS 报错全都攒在缓冲区里，
 * 进程还在跑的时候一条也看不到。
 *
 * 系统接口取全局那一份、而不是后端自己那一份：调试器启用后会把全局系统接口换成它的包装
 * （DebuggerSystemInterface），经它打日志才会出现在调试器的日志面板里。
 */
static void logToSystem(Rml::Log::Type level, const char* message) {
    Rml::SystemInterface* system = Rml::GetSystemInterface();
    if (system == nullptr) system = Backend::GetSystemInterface();
    if (system != nullptr) system->LogMessage(level, message);
    fflush(stderr);
}

void ll_log_info(const char* message) { logToSystem(Rml::Log::LT_INFO, message); }
void ll_log_error(const char* message) { logToSystem(Rml::Log::LT_ERROR, message); }

/**
 * 把系统栏与刘海占掉的范围从文档根盒里让出来。
 *
 * Android 15 起窗口强制 edge-to-edge（本项目 targetSdk 36），状态栏、刘海、导航栏都盖在
 * 应用画面上：不让位的话标题会顶进状态栏，底部 tabbar 会被导航栏压住。
 * SDL 的 Android 后端把 SDLActivity.onApplyWindowInsets 收到的边距存成「安全区」，
 * 这里换算成四边宽度套在文档根盒的**边框**上。为什么不是 padding：
 * 绝对定位子元素（.tabbar / .content）的包含块是父级的 padding box，padding 推不动它们；
 * 而边框既能把 padding box 缩进去，又能在边框区域上色 —— RmlUi 的背景不画到边框区，
 * 所以边框颜色要跟着页面底色走，系统栏那一条才不会露出清屏色（见下面的赋值）。
 *
 * 桌面 / 无系统栏时四边为 0，直接把边框宽度清零，行为与不带安全区的版本一致。
 */
void applySafeArea() {
    if (g_state.window == nullptr || g_state.document == nullptr) return;

    SDL_Rect safe = {};
    if (!SDL_GetWindowSafeArea(g_state.window, &safe)) return;

    int width = 0;
    int height = 0;
    SDL_GetWindowSize(g_state.window, &width, &height);

    // 安全区矩形是「窗口内可安全使用的区域」，四边差值就是系统栏 / 刘海的厚度
    const int left = safe.x;
    const int top = safe.y;
    const int right = width - (safe.x + safe.w);
    const int bottom = height - (safe.y + safe.h);

    if (!g_state.safe_area_dirty &&
        left == g_state.safe_left && top == g_state.safe_top &&
        right == g_state.safe_right && bottom == g_state.safe_bottom) {
        return;
    }
    g_state.safe_area_dirty = false;
    g_state.safe_left = left;
    g_state.safe_top = top;
    g_state.safe_right = right;
    g_state.safe_bottom = bottom;

    char value[32];
    g_state.document->SetProperty("box-sizing", "border-box");
    snprintf(value, sizeof(value), "%dpx", top);
    g_state.document->SetProperty("border-top-width", value);
    snprintf(value, sizeof(value), "%dpx", right);
    g_state.document->SetProperty("border-right-width", value);
    snprintf(value, sizeof(value), "%dpx", bottom);
    g_state.document->SetProperty("border-bottom-width", value);
    snprintf(value, sizeof(value), "%dpx", left);
    g_state.document->SetProperty("border-left-width", value);
    // 边框是要露出来的：RmlUi 的背景不画到边框区域，透明边框会露出清屏色（黑），
    // 所以边框取页面自己的底色，系统栏那一条看起来就是页面的延续。
    const Rml::Colourb background = g_state.document->GetComputedValues().background_color();
    char color[32];
    if (background.alpha == 255) {
        snprintf(color, sizeof(color), "#%02X%02X%02X", background.red, background.green, background.blue);
    } else {
        snprintf(color, sizeof(color), "rgba(%d, %d, %d, %d)",
            (int)background.red, (int)background.green, (int)background.blue, (int)background.alpha);
    }
    g_state.document->SetProperty("border-color", color);
}

#if defined(__ANDROID__)
/**
 * 触摸设备上关掉输入框的拖拽选择。
 *
 * RmlUi 的文本控件在构造时把宿主元素标成 `drag: drag`（写在元素行内样式上，样式表覆盖不掉）；
 * Context::ProcessTouchMove 看到 drag 非空就跳过滚动 —— 症状就是从输入框里起手拖拽只框选文字、
 * 页面纹丝不动。这里在文档加载后把输入类元素改回 `drag: none`：点按仍然定位光标，
 * 拖动则交还给最近的滚动容器。
 */
void disableTextDragSelect() {
    if (g_state.document == nullptr) return;
    for (const char* tag : {"input", "textarea"}) {
        Rml::ElementList elements;
        g_state.document->GetElementsByTagName(elements, tag);
        for (Rml::Element* element : elements) element->SetProperty("drag", "none");
    }
}

/**
 * 触摸按下 / 抬起的原始数据。
 *
 * SDL 的事件监听可能在任何线程（Android 上是 Java 线程）触发，所以只往这里塞原子量，
 * 真正的「点按还是拖拽」判定放到帧线程做（见 applyKeyboardDecision）。
 */
struct TouchPress {
    std::atomic<bool> down{false};
    std::atomic<bool> up_pending{false};
    std::atomic<float> down_x{0.0f};
    std::atomic<float> down_y{0.0f};
    std::atomic<float> up_x{0.0f};
    std::atomic<float> up_y{0.0f};
    std::atomic<uint64_t> down_ms{0};
    std::atomic<uint64_t> up_ms{0};
};

TouchPress g_touch_press;

/** 点按判定：位移不超过 12dp、时长不超过 400ms 才算点按，否则视为拖拽 / 长按。 */
constexpr float kTapSlopDp = 12.0f;
constexpr uint64_t kTapMaxMillis = 400;

/**
 * 延迟弹软键盘的系统接口包装。
 *
 * RmlUi 的文本框一按下就走 ShowCursor(true) → SystemInterface::ActivateKeyboard()，
 * 而后端的实现直接 SDL_StartTextInput()：手指刚落下、还没分清是点按还是拖拽，键盘就弹出来了。
 * 这里把激活请求先挂起，等触摸抬起后由帧线程判定：点按才补发，拖拽直接丢弃。
 * 没有手指按着时的激活请求（例如代码里主动聚焦）照旧立刻转发。
 */
class DeferredKeyboardSystemInterface final : public Rml::SystemInterface {
public:
    void SetInner(Rml::SystemInterface* value) { inner = value; }
    Rml::SystemInterface* GetInner() const { return inner; }

    double GetElapsedTime() override { return inner->GetElapsedTime(); }
    int TranslateString(Rml::String& translated, const Rml::String& input) override {
        return inner->TranslateString(translated, input);
    }
    void JoinPath(Rml::String& translated_path, const Rml::String& document_path, const Rml::String& path) override {
        inner->JoinPath(translated_path, document_path, path);
    }
    bool LogMessage(Rml::Log::Type type, const Rml::String& message) override {
        return inner->LogMessage(type, message);
    }
    void SetMouseCursor(const Rml::String& cursor_name) override { inner->SetMouseCursor(cursor_name); }
    void SetClipboardText(const Rml::String& text) override { inner->SetClipboardText(text); }
    void GetClipboardText(Rml::String& text) override { inner->GetClipboardText(text); }

    void ActivateKeyboard(Rml::Vector2f caret_position, float line_height) override {
        if (!g_touch_press.down.load()) {
            inner->ActivateKeyboard(caret_position, line_height);
            return;
        }
        pending_position = caret_position;
        pending_line_height = line_height;
        has_pending = true;
    }
    void DeactivateKeyboard() override {
        has_pending = false;
        inner->DeactivateKeyboard();
    }

    /** 判定为点按：补发挂起的激活请求。 */
    void FlushKeyboard() {
        if (!has_pending) return;
        has_pending = false;
        inner->ActivateKeyboard(pending_position, pending_line_height);
    }

    /** 判定为拖拽 / 长按：丢弃挂起的请求。 */
    void CancelKeyboard() { has_pending = false; }

private:
    Rml::SystemInterface* inner = nullptr;
    Rml::Vector2f pending_position;
    float pending_line_height = 0.0f;
    bool has_pending = false;
};

DeferredKeyboardSystemInterface g_keyboard_system;

/** SDL 事件监听：只记录按下的位置与时间，不在这里做判定（可能不在主线程）。 */
bool onTouchEventWatch(void*, SDL_Event* event) {
    switch (event->type) {
    case SDL_EVENT_FINGER_DOWN:
        g_touch_press.down_x = event->tfinger.x;
        g_touch_press.down_y = event->tfinger.y;
        g_touch_press.down_ms = SDL_GetTicks();
        g_touch_press.down = true;
        break;
    case SDL_EVENT_FINGER_UP:
        g_touch_press.up_x = event->tfinger.x;
        g_touch_press.up_y = event->tfinger.y;
        g_touch_press.up_ms = SDL_GetTicks();
        // down 标志留到帧线程判定完再清：一次快速滑动可能把 down/up 塞进同一批事件，
        // 提前清掉会让按下时的键盘激活直接透传出去（又变成「一按就弹」）。
        g_touch_press.up_pending = true;
        break;
    default: break;
    }
    return true;
}

/** 松手后把指针移出窗口、清掉 hover（定义见下）。 */
void scheduleHoverClear();

/** 帧线程：把刚结束的这次触摸归类，然后决定补发还是丢弃挂起的键盘激活。 */
void applyKeyboardDecision() {
    if (g_state.window == nullptr) return;
    if (!g_touch_press.up_pending.exchange(false)) return;
    g_touch_press.down = false;

    int width = 0;
    int height = 0;
    SDL_GetWindowSizeInPixels(g_state.window, &width, &height);
    // SDL3 的触摸坐标是归一化的 0..1，换算成像素再比较距离
    const float dx = (g_touch_press.up_x.load() - g_touch_press.down_x.load()) * float(width);
    const float dy = (g_touch_press.up_y.load() - g_touch_press.down_y.load()) * float(height);
    const float distance = SDL_sqrtf(dx * dx + dy * dy);
    const float slop = kTapSlopDp * SDL_GetWindowPixelDensity(g_state.window);
    const uint64_t elapsed = g_touch_press.up_ms.load() - g_touch_press.down_ms.load();

    if (distance <= slop && elapsed <= kTapMaxMillis) g_keyboard_system.FlushKeyboard();
    else g_keyboard_system.CancelKeyboard();

    scheduleHoverClear();
}

/** 松手后过多久把「指针」移出窗口（毫秒）。 */
constexpr uint32_t kHoverClearDelayMs = 100;

/** 一次性定时器到点了；帧线程看到后执行清理。 */
std::atomic<bool> g_hover_clear_due{false};

Uint32 onHoverClearTimer(void*, SDL_TimerID, Uint32) {
    g_hover_clear_due = true;
    // 帧循环可能正阻塞在 SDL_WaitEventTimeout 里（power_save），戳一下
    ll_ui_wake();
    return 0;
}

/**
 * 排一次「把指针移出窗口」。
 *
 * 移动端没有鼠标：手指抬起后 RmlUi 仍认为指针停在最后位置，:hover 会一直挂着，
 * 看起来像有个假鼠标悬在那里。这里松手 100ms 后调 ProcessMouseLeave()，
 * 下一次触摸（ProcessMouseMove）会重新激活，不影响后续交互。
 */
void scheduleHoverClear() {
    g_hover_clear_due = false;
    SDL_AddTimer(kHoverClearDelayMs, &onHoverClearTimer, nullptr);
}

/** 帧线程：定时器到点就清掉所有 hover。 */
void applyPendingHoverClear() {
    if (!g_hover_clear_due.exchange(false)) return;
    if (g_state.context != nullptr) g_state.context->ProcessMouseLeave();
}
#endif

int32_t ll_ui_create(const char* title, int32_t width, int32_t height) {
    if (g_state.created) {
        setError("ll_ui 已初始化，同一进程只允许一个窗口");
        return -1;
    }
    clearError();
    g_state.slot_count = 0;
    g_state.action = nullptr;
    g_state.action_user_data = nullptr;
    g_state.debugger = false;
    g_state.wake_event_type = 0;
    g_state.document = nullptr;
    g_state.context = nullptr;
    g_state.window = nullptr;
    g_state.safe_left = -1;
    g_state.safe_top = -1;
    g_state.safe_right = -1;
    g_state.safe_bottom = -1;
    g_state.safe_area_dirty = true;
    g_state.created = true;

    // 后端自己开窗、建 GPU 设备、接管输入，并把 SDL 事件翻译成 RmlUi 输入
    if (!Backend::Initialize(title == nullptr ? "lolosia" : title, width, height, true)) {
        setError("Backend::Initialize 失败：窗口或 GPU 设备创建不出来");
        ll_ui_destroy();
        return -1;
    }

    // 自定义唤醒事件：后台线程要能打断 SDL_WaitEventTimeout，不能直接推 SDL_EVENT_USER。
    g_state.wake_event_type = SDL_RegisterEvents(1);
    if (g_state.wake_event_type == 0) {
        ll_log_error("SDL_RegisterEvents 失败：后台更新将无法主动唤醒帧循环");
    }

    // 渲染/系统/文件接口都必须在 Rml::Initialise() 之前挂上。
    // 文件接口换成「先问宿主、再退回磁盘」的实现：界面资源在 classpath（内嵌 zip）里，
    // 而字体这类东西仍是磁盘文件，两者都要能读到。
    Rml::SetSystemInterface(Backend::GetSystemInterface());
#if defined(__ANDROID__)
    // 软键盘改为「点按才弹」：包一层后端的系统接口，激活请求先挂起，等触摸抬起判定
    g_keyboard_system.SetInner(Backend::GetSystemInterface());
    Rml::SetSystemInterface(&g_keyboard_system);
    g_touch_press.down = false;
    g_touch_press.up_pending = false;
    SDL_AddEventWatch(&onTouchEventWatch, nullptr);
#endif
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
        g_state.window = window;

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

namespace {

/** 大小写不敏感的子串查找（字体名都是 ASCII）。 */
bool containsIgnoreCase(const char* text, const char* needle) {
    if (text == nullptr || needle == nullptr || needle[0] == '\0') return false;
    const size_t length = strlen(needle);
    for (const char* start = text; *start != '\0'; ++start) {
        size_t i = 0;
        while (i < length && start[i] != '\0' &&
               tolower(static_cast<unsigned char>(start[i])) == tolower(static_cast<unsigned char>(needle[i]))) {
            ++i;
        }
        if (i == length) return true;
    }
    return false;
}

/** 取字面的英文家族名（name 表 nameID=1、Windows Unicode BMP、en-US）。 */
Rml::String faceFamilyName(FT_Face face) {
    const FT_UInt count = FT_Get_Sfnt_Name_Count(face);
    for (FT_UInt i = 0; i < count; ++i) {
        FT_SfntName record{};
        if (FT_Get_Sfnt_Name(face, i, &record) != 0) continue;
        if (record.name_id != 1 || record.platform_id != 3 || record.language_id != 0x0409) continue;
        Rml::String text;
        for (FT_UInt k = 0; k + 1 < record.string_len; k += 2) {
            // UTF-16BE，英文名字每个字符的高字节都是 0
            if (record.string[k] == 0 && record.string[k + 1] != 0) text.push_back(static_cast<char>(record.string[k + 1]));
        }
        if (!text.empty()) return text;
    }
    return {};
}

/**
 * 在字体集合里找「名字里带 hint」的字面。
 *
 * Noto Sans CJK 这类 .ttc 会把 HK / JP / KR / SC / TC 依次排开，0 号是香港字面；
 * 简体中文界面要的 SC 往往在后面，所以要按名字挑，而不是无脑用 0 号。
 * 找不到（普通 ttf/otf、或名字对不上）就退回 0 号。
 */
int findFaceIndex(const char* file_path, const char* hint) {
    FT_Library library = nullptr;
    if (FT_Init_FreeType(&library) != 0) return 0;

    int face_index = 0;
    FT_Face first = nullptr;
    if (FT_New_Face(library, file_path, 0, &first) == 0) {
        const FT_Long face_count = first->num_faces;
        FT_Done_Face(first);
        for (FT_Long i = 0; i < face_count; ++i) {
            FT_Face face = nullptr;
            if (FT_New_Face(library, file_path, i, &face) != 0) continue;
            const char* postscript = FT_Get_Postscript_Name(face);
            const Rml::String family = faceFamilyName(face);
            const bool hit = containsIgnoreCase(postscript, hint) || containsIgnoreCase(family.c_str(), hint);
            FT_Done_Face(face);
            if (hit) {
                face_index = static_cast<int>(i);
                break;
            }
        }
    }
    FT_Done_FreeType(library);
    return face_index;
}

}  // namespace

int32_t ll_ui_load_font(const char* file_path, const char* family) {
    return ll_ui_load_font_face(file_path, family, nullptr);
}

int32_t ll_ui_load_font_face(const char* file_path, const char* family, const char* face_hint) {
    if (file_path == nullptr || file_path[0] == '\0') {
        setError("字体路径为空");
        return -1;
    }
    if (family == nullptr || family[0] == '\0') {
        setError("字体家族名为空");
        return -1;
    }
    const int face_index = (face_hint == nullptr || face_hint[0] == '\0') ? 0 : findFaceIndex(file_path, face_hint);
    // 指定家族名 + fallback_face：RCSS 里写 font-family: <family> 就能命中，
    // 其他家族缺字形时也会退到它
    if (!Rml::LoadFontFace(file_path, family, Rml::Style::FontStyle::Normal, Rml::Style::FontWeight::Auto, true, face_index)) {
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

int32_t ll_ui_model_get_string(const char* name, char* out, int32_t capacity) {
    const int index = requireSlot(name, VarKind::String);
    if (index < 0) return -1;
    const Rml::String& value = g_state.strings[index];
    const int32_t size = static_cast<int32_t>(value.size());
    if (out == nullptr || capacity < size) return size;
    if (size > 0) memcpy(out, value.data(), static_cast<size_t>(size));
    clearError();
    return size;
}

int32_t ll_ui_model_get_int(const char* name, int32_t* out) {
    const int index = requireSlot(name, VarKind::Int);
    if (index < 0 || out == nullptr) return -1;
    *out = g_state.ints[index];
    clearError();
    return 0;
}

int32_t ll_ui_model_get_float(const char* name, float* out) {
    const int index = requireSlot(name, VarKind::Float);
    if (index < 0 || out == nullptr) return -1;
    *out = g_state.floats[index];
    clearError();
    return 0;
}

int32_t ll_ui_model_get_bool(const char* name, int32_t* out) {
    const int index = requireSlot(name, VarKind::Bool);
    if (index < 0 || out == nullptr) return -1;
    *out = g_state.bools[index] ? 1 : 0;
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
    // 新文档是一张新样式表算出来的盒子，边距要重新套一遍
    g_state.safe_area_dirty = true;
    applySafeArea();
#if defined(__ANDROID__)
    disableTextDragSelect();
#endif
    clearError();
    return 0;
}

int32_t ll_ui_enable_debugger(void) {
    if (g_state.context == nullptr) {
        setError("上下文尚未初始化");
        return -1;
    }
    if (g_state.debugger) {
        clearError();
        return 0;
    }
    // 它自己往上下文里挂几个文档（菜单 / 日志 / 数据模型 / 元素信息 / 钩子），
    // 并接管全局系统接口以便截获日志；这些都是 RmlUi 官方的调试器插件在管，宿主不用操心。
    if (!Rml::Debugger::Initialise(g_state.context)) {
        setError("Rml::Debugger::Initialise 失败（详见上方 RmlUi 日志）");
        return -1;
    }
    g_state.debugger = true;
    clearError();
    return 0;
}

int32_t ll_ui_frame(void) {
    if (g_state.context == nullptr) return -1;

    // 系统栏 / 刘海随时可能变（转屏、切换手势导航、外接显示器），每帧校验一次，变了才动文档
    applySafeArea();

    // power_save：没有事件、也不需要动画时阻塞等待（最多等下一帧更新），不空转烧 CPU
    const bool keep_running = Backend::ProcessEvents(g_state.context, &onKeyDown, true);
#if defined(__ANDROID__)
    // 触摸抬起后立刻归类：点按才补发键盘激活，拖拽 / 长按不弹
    applyKeyboardDecision();
    // 松手 100ms 后把指针移出窗口，避免留下虚假 hover
    applyPendingHoverClear();
#endif
    if (!keep_running) return 1;

    g_state.context->Update();
    Backend::BeginFrame();
    g_state.context->Render();
    Backend::PresentFrame();
    return 0;
}

const char* ll_ui_last_error(void) { return g_state.error; }

int32_t ll_ui_wake(void) {
    if (g_state.wake_event_type == 0) return -1;

    SDL_Event event;
    SDL_zero(event);
    event.type = g_state.wake_event_type;
    if (!SDL_PushEvent(&event)) {
        setError("SDL_PushEvent 失败");
        return -1;
    }
    clearError();
    return 0;
}

void ll_ui_destroy(void) {
    // 调试器要排在 RemoveContext 之前收：它得把自己挂进上下文的那些文档摘掉、
    // 并把被它接管的系统接口还原（Rml::Shutdown 里也能收，但那时代价是上下文已经没了）。
    if (g_state.debugger) {
        Rml::Debugger::Shutdown();
        g_state.debugger = false;
    }
    // 顺序要紧：先撤 RmlUi（要经渲染接口释放 GPU 资源），再关后端的设备与窗口
    if (g_state.context != nullptr) {
        Rml::RemoveContext(kContextName);
        g_state.context = nullptr;
    }
    g_state.document = nullptr;
    g_state.window = nullptr;
#if defined(__ANDROID__)
    SDL_RemoveEventWatch(&onTouchEventWatch, nullptr);
    if (Rml::GetSystemInterface() == &g_keyboard_system) Rml::SetSystemInterface(g_keyboard_system.GetInner());
#endif
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
