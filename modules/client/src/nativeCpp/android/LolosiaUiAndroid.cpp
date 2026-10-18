// LolosiaUiAndroid.cpp —— Android 上的 UI 宿主：把 SDLActivity 的 native 入口接到 ll_ui_*。
//
// 只参与 Android 构建（thirdparty/build-android.ps1 单独编译这个目录），桌面脚本只扫 src/，
// 因此这里可以直接依赖 NDK 的 AAssetManager / BitmapFactory / libjnigraphics。
//
// 分工与桌面版一致：窗口、GPU 设备、输入事件由 RmlUi 的 SDL_GPU 后端负责；
// 本文件只做三件事 —— 把资源接到 APK assets、把表单/动作接到一份宿主状态、跑帧循环。

#if defined(__ANDROID__)

#define SDL_MAIN_HANDLED 1

#include "lolosia_ui.h"
#include "../src/LolosiaUiInternal.h"

#include <SDL3/SDL.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char* kLogTag = "lolosia-ui";
constexpr const char* kDocumentPath = "ui/probe.rml";
constexpr const char* kFontFamily = "lolosia-ui";

/** 资源上限与桌面版一致：正常界面资源没有这么大，多半是路径写错。 */
constexpr int64_t kMaxResourceBytes = 64ll * 1024 * 1024;

/** 字体候选：优先带中日韩字形的系统字体，最后才是纯拉丁字体。 */
const char* const kFontCandidates[] = {
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/NotoSansCJKsc-Regular.otf",
    "/system/fonts/NotoSansSC-Regular.otf",
    "/system/fonts/DroidSansFallback.ttf",
    "/system/fonts/Roboto-Regular.ttf",
};

void logInfo(const char* message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message);
    ll_log_info(message);
}

void logError(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
    ll_log_error(message.c_str());
}

/** 表单控件在帧末读回；字段与 UiProbeViewModel 的初始值保持一致。 */
struct ProbeState {
    int count = 0;
    bool highlighted = false;
    std::string lastAction = "（还没有交互）";
    std::string nickname = "洛洛希雅";
    bool agree = false;
    std::string theme = "dark";
    float opacity = 0.6f;
    std::string bio = "第一行\n第二行：textarea 的换行会原样写回 ViewModel。";
    std::string password = "hunter2";
    float load = 0.35f;
    std::string formEcho = "（还没改过表单控件）";
    bool formSyncPending = false;
};

ProbeState g_state;
AAssetManager* g_assets = nullptr;

JNIEnv* androidEnv() {
    return reinterpret_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
}

/** 把 RmlUi 的路径规整成 assets 内的相对路径（去掉开头的 `/` 与伪 scheme）。 */
std::string normalizeAssetPath(const char* path) {
    std::string value = path == nullptr ? "" : path;
    const std::string scheme = "lolosia://";
    if (value.rfind(scheme, 0) == 0) value.erase(0, scheme.size());
    while (!value.empty() && value.front() == '/') value.erase(value.begin());
    return value;
}

/** 读取一个 assets 资源；按「两次调用」约定返回所需字节数或写入字节数。 */
int32_t readAsset(void* /*user_data*/, const char* path, uint8_t* out, int32_t capacity) {
    if (g_assets == nullptr || path == nullptr) return -1;
    const std::string name = normalizeAssetPath(path);
    if (name.empty()) return -1;

    AAsset* asset = AAssetManager_open(g_assets, name.c_str(), AASSET_MODE_BUFFER);
    if (asset == nullptr) return -1;

    const int64_t length = AAsset_getLength64(asset);
    if (length <= 0 || length > kMaxResourceBytes) {
        AAsset_close(asset);
        return -1;
    }
    const int32_t needed = static_cast<int32_t>(length);
    if (out == nullptr || capacity < needed) {
        AAsset_close(asset);
        return needed;
    }
    const int32_t read = AAsset_read(asset, out, static_cast<size_t>(needed));
    AAsset_close(asset);
    return read == needed ? needed : -1;
}

/** 用 Android 平台的 BitmapFactory 解码图片，输出 RGBA8（ARGB_8888 的内存布局即 RGBA）。 */
int32_t decodeImage(void* /*user_data*/, const uint8_t* bytes, int32_t size,
                    uint8_t* out, int32_t capacity, int32_t* outWidth, int32_t* outHeight) {
    JNIEnv* env = androidEnv();
    if (env == nullptr || bytes == nullptr || size <= 0) return -1;

    jbyteArray array = env->NewByteArray(size);
    if (array == nullptr) return -1;
    env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte*>(bytes));

    jclass factory = env->FindClass("android/graphics/BitmapFactory");
    jmethodID decode = factory == nullptr ? nullptr
        : env->GetStaticMethodID(factory, "decodeByteArray", "([BII)Landroid/graphics/Bitmap;");
    jobject bitmap = decode == nullptr ? nullptr
        : env->CallStaticObjectMethod(factory, decode, array, 0, size);
    env->DeleteLocalRef(array);
    if (bitmap == nullptr) return -1;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        env->DeleteLocalRef(bitmap);
        return -1;
    }

    if (outWidth != nullptr) *outWidth = static_cast<int32_t>(info.width);
    if (outHeight != nullptr) *outHeight = static_cast<int32_t>(info.height);

    const int32_t needed = static_cast<int32_t>(info.width * info.height * 4);
    if (out == nullptr || capacity < needed) {
        env->DeleteLocalRef(bitmap);
        return needed;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        env->DeleteLocalRef(bitmap);
        return -1;
    }
    memcpy(out, pixels, static_cast<size_t>(needed));
    AndroidBitmap_unlockPixels(env, bitmap);
    env->DeleteLocalRef(bitmap);
    return needed;
}

std::string readAssetString(const char* path) {
    const int32_t size = readAsset(nullptr, path, nullptr, 0);
    if (size <= 0) return {};
    std::vector<uint8_t> buffer(static_cast<size_t>(size));
    const int32_t written = readAsset(nullptr, path, buffer.data(), size);
    if (written != size) return {};
    return std::string(reinterpret_cast<const char*>(buffer.data()), buffer.size());
}

std::string modelString(const char* name) {
    const int32_t size = ll_ui_model_get_string(name, nullptr, 0);
    if (size <= 0) return {};
    std::vector<char> buffer(static_cast<size_t>(size));
    const int32_t written = ll_ui_model_get_string(name, buffer.data(), size);
    if (written != size) return {};
    return std::string(buffer.data(), buffer.size());
}

float modelFloat(const char* name, float fallback) {
    float value = fallback;
    return ll_ui_model_get_float(name, &value) == 0 ? value : fallback;
}

bool modelBool(const char* name, bool fallback) {
    int32_t value = fallback ? 1 : 0;
    return ll_ui_model_get_bool(name, &value) == 0 ? value != 0 : fallback;
}

void pushLastAction(const std::string& action) {
    g_state.lastAction = action;
    ll_ui_model_set_string("lastAction", g_state.lastAction.c_str());
}

void pushLoad() { ll_ui_model_set_float("load", g_state.load); }

/** 文档里的 action('x') 落到这里，逻辑与桌面版的 UiProbeViewModel 对齐。 */
void onAction(void* /*user_data*/, const char* name) {
    const std::string action = name == nullptr ? "" : name;
    if (action == "sync-form") {
        g_state.formSyncPending = true;
        return;
    }

    if (action == "increment") {
        g_state.count += 1;
        ll_ui_model_set_int("count", g_state.count);
    } else if (action == "increment-ten") {
        g_state.count += 10;
        ll_ui_model_set_int("count", g_state.count);
    } else if (action == "reset") {
        g_state.count = 0;
        ll_ui_model_set_int("count", g_state.count);
    } else if (action == "toggle-highlight") {
        g_state.highlighted = !g_state.highlighted;
        ll_ui_model_set_bool("highlighted", g_state.highlighted ? 1 : 0);
    } else if (action == "load-up") {
        g_state.load = g_state.load + 0.1f > 1.0f ? 1.0f : g_state.load + 0.1f;
        pushLoad();
    } else if (action == "load-down") {
        g_state.load = g_state.load - 0.1f < 0.0f ? 0.0f : g_state.load - 0.1f;
        pushLoad();
    } else if (action == "load-reset") {
        g_state.load = 0.0f;
        pushLoad();
    } else {
        pushLastAction("未处理的动作：" + action);
        return;
    }
    pushLastAction(action);
}

/** 帧末把表单控件的值读回宿主，并更新界面上的回执 —— 与桌面版 onFormSynced 同一口径。 */
void syncForm() {
    if (!g_state.formSyncPending) return;
    g_state.formSyncPending = false;

    g_state.nickname = modelString("nickname");
    g_state.theme = modelString("theme");
    g_state.opacity = modelFloat("opacity", g_state.opacity);
    g_state.agree = modelBool("agree", g_state.agree);
    g_state.bio = modelString("bio");
    g_state.password = modelString("password");

    char opacity[32];
    snprintf(opacity, sizeof(opacity), "%g", g_state.opacity);
    g_state.formEcho = "已读回 ViewModel：昵称=" + g_state.nickname + "｜主题=" + g_state.theme +
        "｜不透明度=" + opacity + "｜同意=" + (g_state.agree ? "true" : "false") +
        "｜简介=" + std::to_string(g_state.bio.size()) + " 字｜密码=" +
        std::to_string(g_state.password.size()) + " 位";
    ll_ui_model_set_string("formEcho", g_state.formEcho.c_str());
}

void bindModel() {
    ll_ui_model_bind_string("title", "洛洛希雅 · RmlUi 布局实验");
    ll_ui_model_bind_string("subtitle", "结构写在 RML、样式写在 RCSS（flexbox 自动排版）；数值全部来自 Kotlin 的 ViewModel");
    ll_ui_model_bind_int("count", g_state.count);
    ll_ui_model_bind_bool("highlighted", g_state.highlighted ? 1 : 0);
    ll_ui_model_bind_string("lastAction", g_state.lastAction.c_str());
    ll_ui_model_bind_string("nickname", g_state.nickname.c_str());
    ll_ui_model_bind_bool("agree", g_state.agree ? 1 : 0);
    ll_ui_model_bind_string("theme", g_state.theme.c_str());
    ll_ui_model_bind_float("opacity", g_state.opacity);
    ll_ui_model_bind_string("bio", g_state.bio.c_str());
    ll_ui_model_bind_string("password", g_state.password.c_str());
    ll_ui_model_bind_float("load", g_state.load);
    ll_ui_model_bind_string("formEcho", g_state.formEcho.c_str());
}

/** 系统字体通常带中日韩字形；一个都加载不上时界面仍能开，只是文字缺字。 */
void loadFont() {
    for (const char* path : kFontCandidates) {
        if (ll_ui_load_font(path, kFontFamily) == 0) {
            logInfo((std::string("已加载字体：") + path).c_str());
            return;
        }
    }
    logError(std::string("没有可用字体，界面文字会缺字（候选：") + kFontCandidates[0] + " 等）");
}

/** 从 SDLActivity 拿到 AssetManager；必须在 ll_ui_create 之后调用（SDL 已初始化）。 */
bool setupAssets() {
    JNIEnv* env = androidEnv();
    jobject activity = reinterpret_cast<jobject>(SDL_GetAndroidActivity());
    if (env == nullptr || activity == nullptr) {
        logError("拿不到 SDLActivity / JNIEnv");
        return false;
    }
    jclass activityClass = env->GetObjectClass(activity);
    if (activityClass == nullptr) return false;
    jmethodID getAssets = env->GetMethodID(activityClass, "getAssets", "()Landroid/content/res/AssetManager;");
    if (getAssets == nullptr) {
        env->DeleteLocalRef(activityClass);
        return false;
    }
    jobject manager = env->CallObjectMethod(activity, getAssets);
    env->DeleteLocalRef(activityClass);
    if (manager == nullptr) return false;
    g_assets = AAssetManager_fromJava(env, manager);
    env->DeleteLocalRef(manager);
    return g_assets != nullptr;
}

}  // namespace

/** SDLActivity 的 nativeRunMain 通过 dlsym 找的就是这个符号（见 SDLActivity.getMainFunction）。 */
extern "C" int SDL_main(int /*argc*/, char* /*argv*/[]) {
    logInfo("UI 宿主启动");

    if (ll_ui_create("洛洛希雅 · RmlUi 布局实验", 1120, 760) != 0) {
        logError(std::string("创建窗口失败：") + ll_ui_last_error());
        return 1;
    }

    if (!setupAssets()) {
        logError("初始化 assets 失败");
        ll_ui_destroy();
        return 1;
    }

    ll_ui_set_resource_loader(readAsset, decodeImage, nullptr);
    loadFont();
    bindModel();
    ll_ui_set_action_callback(onAction, nullptr);

    const std::string document = readAssetString(kDocumentPath);
    if (document.empty()) {
        logError(std::string("assets 里找不到 ") + kDocumentPath);
        ll_ui_destroy();
        return 1;
    }
    if (ll_ui_load_document(document.c_str()) != 0) {
        logError(std::string("加载文档失败：") + ll_ui_last_error());
        ll_ui_destroy();
        return 1;
    }

    if (ll_ui_enable_debugger() == 0) {
        logInfo("内置调试器已启用（外接键盘时 F8 开关）");
    }

    logInfo("面板已就绪，进入帧循环");
    int result = 0;
    while (true) {
        const int frame = ll_ui_frame();
        if (frame == 1) break;
        if (frame < 0) {
            logError(std::string("渲染失败：") + ll_ui_last_error());
            result = 1;
            break;
        }
        syncForm();
    }

    ll_ui_destroy();
    logInfo("UI 宿主退出");
    return result;
}

#endif  // __ANDROID__
