// LolosiaUiAndroid.cpp —— Android 上的 UI 宿主：把 SDLActivity 的 native 入口接到 ll_ui_*。
//
// 只参与 Android 构建（thirdparty/gradle/build-android.ps1 单独编译这个目录），桌面脚本只扫 src/，
// 因此这里可以直接依赖 NDK 的 AAssetManager / BitmapFactory / libjnigraphics。
//
// 分工与桌面版一致：窗口、GPU 设备、输入事件由 RmlUi 的 SDL_GPU 后端负责。
// 与桌面版的差异只有一处 —— 业务状态在 Android 主线程的 Kotlin 里，而帧循环在 SDL 线程：
//   - Kotlin 通过 Java_moe_lolosia_android_ui_NativeUi_* 投递命令（绑定 / 赋值 / 载入文档）；
//   - 本文件在每帧开头消费命令队列，改完数据模型后继续跑 ll_ui_frame；
//   - 文档里的 action('x') 通过 JNI 回到 Kotlin 主线程。
// 资源（图片字节）由 Kotlin 下载后注册进内存表，读文件回调先查内存表再查 APK assets。

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

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char* kLogTag = "lolosia-ui";
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

AAssetManager* g_assets = nullptr;

/** Kotlin 注册的图片：key 是去掉开头 `/` 的 RmlUi 资源路径。 */
std::mutex g_imagesMutex;
std::map<std::string, std::vector<uint8_t>> g_images;

/** Kotlin 回调：NativeUi.onNativeAction(String)。SDL 线程上调用，需自行附着 JVM。 */
JavaVM* g_vm = nullptr;
jclass g_nativeUiClass = nullptr;
jmethodID g_onActionMethod = nullptr;

/** 同步读模型字符串的等待槽；由帧线程填值并唤醒主线程。 */
struct StringRequest {
    std::mutex mutex;
    std::condition_variable cv;
    bool done = false;
    std::string value;
};

/** 跨线程命令。帧线程在每帧开头整批取走。 */
struct Command {
    enum class Type {
        BindString,
        BindInt,
        BindBool,
        SetString,
        SetInt,
        SetBool,
        LoadDocument,
        LoadDocumentText,
        GetString,
    };

    Type type;
    std::string name;
    std::string value;
    int32_t intValue = 0;
    std::shared_ptr<StringRequest> request;
};

std::mutex g_queueMutex;
std::deque<Command> g_queue;

JNIEnv* androidEnv() {
    return reinterpret_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
}

/** 把 RmlUi 的路径规整成资源键（去掉开头的 `/` 与伪 scheme）。 */
std::string normalizeAssetPath(const char* path) {
    std::string value = path == nullptr ? "" : path;
    const std::string scheme = "lolosia://";
    if (value.rfind(scheme, 0) == 0) value.erase(0, scheme.size());
    while (!value.empty() && value.front() == '/') value.erase(value.begin());
    return value;
}

/** 读取一个资源：先查 Kotlin 注册的图片，再查 APK assets。 */
int32_t readAsset(void* /*user_data*/, const char* path, uint8_t* out, int32_t capacity) {
    if (path == nullptr) return -1;
    const std::string name = normalizeAssetPath(path);
    if (name.empty()) return -1;

    {
        std::lock_guard<std::mutex> lock(g_imagesMutex);
        const auto it = g_images.find(name);
        if (it != g_images.end()) {
            const auto& bytes = it->second;
            const int32_t needed = static_cast<int32_t>(bytes.size());
            if (out == nullptr || capacity < needed) return needed;
            memcpy(out, bytes.data(), static_cast<size_t>(needed));
            return needed;
        }
    }

    if (g_assets == nullptr) return -1;
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

/** 把命令挂到队列并唤醒阻塞中的帧循环。可从任意线程调用。 */
void enqueue(Command command) {
    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        g_queue.push_back(std::move(command));
    }
    ll_ui_wake();
}

void applyCommand(const Command& command) {
    switch (command.type) {
        case Command::Type::BindString:
            ll_ui_model_bind_string(command.name.c_str(), command.value.c_str());
            break;
        case Command::Type::BindInt:
            ll_ui_model_bind_int(command.name.c_str(), command.intValue);
            break;
        case Command::Type::BindBool:
            ll_ui_model_bind_bool(command.name.c_str(), command.intValue);
            break;
        case Command::Type::SetString:
            ll_ui_model_set_string(command.name.c_str(), command.value.c_str());
            break;
        case Command::Type::SetInt:
            ll_ui_model_set_int(command.name.c_str(), command.intValue);
            break;
        case Command::Type::SetBool:
            ll_ui_model_set_bool(command.name.c_str(), command.intValue);
            break;
        case Command::Type::LoadDocument: {
            const std::string document = readAssetString(command.name.c_str());
            if (document.empty()) {
                logError("加载文档失败：assets 里找不到 " + command.name);
                break;
            }
            if (ll_ui_load_document(document.c_str()) != 0) {
                logError(std::string("加载文档失败：") + ll_ui_last_error());
            }
            break;
        }
        case Command::Type::LoadDocumentText:
            if (ll_ui_load_document(command.value.c_str()) != 0) {
                logError(std::string("加载文档失败：") + ll_ui_last_error());
            }
            break;
        case Command::Type::GetString: {
            const int32_t size = ll_ui_model_get_string(command.name.c_str(), nullptr, 0);
            std::string value;
            if (size > 0) {
                std::vector<char> buffer(static_cast<size_t>(size));
                if (ll_ui_model_get_string(command.name.c_str(), buffer.data(), size) == size) {
                    value.assign(buffer.data(), buffer.size());
                }
            }
            if (command.request) {
                {
                    std::lock_guard<std::mutex> lock(command.request->mutex);
                    command.request->value = std::move(value);
                    command.request->done = true;
                }
                command.request->cv.notify_all();
            }
            break;
        }
    }
}

/** 帧线程每帧开头取走整批命令；之后才进入可能阻塞的 ll_ui_frame。 */
void drainCommands() {
    std::deque<Command> local;
    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        local.swap(g_queue);
    }
    for (const Command& command : local) applyCommand(command);
}

/** 文档里的 action('x') 落到 Kotlin 主线程（Kotlin 侧再切 Handler）。 */
void onAction(void* /*user_data*/, const char* name) {
    if (g_vm == nullptr || g_nativeUiClass == nullptr || g_onActionMethod == nullptr) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    if (env != nullptr) {
        jstring action = env->NewStringUTF(name == nullptr ? "" : name);
        env->CallStaticVoidMethod(g_nativeUiClass, g_onActionMethod, action);
        env->DeleteLocalRef(action);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) g_vm->DetachCurrentThread();
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

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" {

/** SDLActivity 的 nativeRunMain 通过 dlsym 找的就是这个符号（见 SDLActivity.getMainFunction）。 */
JNIEXPORT int SDL_main(int /*argc*/, char* /*argv*/[]) {
    logInfo("UI 宿主启动");

    if (ll_ui_create("洛洛希雅", 420, 900) != 0) {
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
    ll_ui_set_action_callback(onAction, nullptr);

    // 文档由 Kotlin 侧按阶段加载；这里不预先塞任何页面。
    logInfo("等待 Kotlin 投递页面");
    int result = 0;
    while (true) {
        drainCommands();
        const int frame = ll_ui_frame();
        if (frame == 1) break;
        if (frame < 0) {
            logError(std::string("渲染失败：") + ll_ui_last_error());
            result = 1;
            break;
        }
    }

    ll_ui_destroy();
    logInfo("UI 宿主退出");
    return result;
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeInit(JNIEnv* env, jclass clazz) {
    env->GetJavaVM(&g_vm);
    if (g_nativeUiClass != nullptr) env->DeleteGlobalRef(g_nativeUiClass);
    g_nativeUiClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    g_onActionMethod = env->GetStaticMethodID(clazz, "onNativeAction", "(Ljava/lang/String;)V");
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeBindString(JNIEnv* env, jclass, jstring name, jstring value) {
    enqueue(Command{Command::Type::BindString, toStdString(env, name), toStdString(env, value), 0, nullptr});
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeBindInt(JNIEnv* env, jclass, jstring name, jint value) {
    enqueue(Command{Command::Type::BindInt, toStdString(env, name), {}, value, nullptr});
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeBindBool(JNIEnv* env, jclass, jstring name, jboolean value) {
    enqueue(Command{Command::Type::BindBool, toStdString(env, name), {}, value != 0 ? 1 : 0, nullptr});
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeSetString(JNIEnv* env, jclass, jstring name, jstring value) {
    enqueue(Command{Command::Type::SetString, toStdString(env, name), toStdString(env, value), 0, nullptr});
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeSetInt(JNIEnv* env, jclass, jstring name, jint value) {
    enqueue(Command{Command::Type::SetInt, toStdString(env, name), {}, value, nullptr});
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeSetBool(JNIEnv* env, jclass, jstring name, jboolean value) {
    enqueue(Command{Command::Type::SetBool, toStdString(env, name), {}, value != 0 ? 1 : 0, nullptr});
}

JNIEXPORT jstring JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeGetString(JNIEnv* env, jclass, jstring name) {
    auto request = std::make_shared<StringRequest>();
    enqueue(Command{Command::Type::GetString, toStdString(env, name), {}, 0, request});

    std::unique_lock<std::mutex> lock(request->mutex);
    if (!request->cv.wait_for(lock, std::chrono::milliseconds(500), [&] { return request->done; })) {
        return nullptr;
    }
    return env->NewStringUTF(request->value.c_str());
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeLoadDocument(JNIEnv* env, jclass, jstring assetPath) {
    enqueue(Command{Command::Type::LoadDocument, toStdString(env, assetPath), {}, 0, nullptr});
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeLoadDocumentText(JNIEnv* env, jclass, jstring rml) {
    enqueue(Command{Command::Type::LoadDocumentText, {}, toStdString(env, rml), 0, nullptr});
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeRegisterImage(JNIEnv* env, jclass, jstring path, jbyteArray bytes) {
    if (path == nullptr || bytes == nullptr) return;
    const std::string name = normalizeAssetPath(toStdString(env, path).c_str());
    if (name.empty()) return;

    const jsize size = env->GetArrayLength(bytes);
    std::vector<uint8_t> data(static_cast<size_t>(size));
    if (size > 0) env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(data.data()));

    std::lock_guard<std::mutex> lock(g_imagesMutex);
    g_images[name] = std::move(data);
}

JNIEXPORT void JNICALL
Java_moe_lolosia_android_ui_NativeUi_nativeWake(JNIEnv*, jclass) {
    ll_ui_wake();
}

}  // extern "C"

#endif  // __ANDROID__
