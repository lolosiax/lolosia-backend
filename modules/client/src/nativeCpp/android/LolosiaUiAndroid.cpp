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
#include <ft2build.h>
#include FT_FREETYPE_H
#include <jni.h>

#include <cctype>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <iterator>
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

/** 字体候选：系统配置读不出来时的兜底，优先带中日韩字形的系统字体。 */
const char* const kFontCandidates[] = {
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/NotoSansCJKsc-Regular.otf",
    "/system/fonts/NotoSansSC-Regular.otf",
    "/system/fonts/DroidSansFallback.ttf",
    "/system/fonts/Roboto-Regular.ttf",
};

/** 系统字体配置：Android 用它声明默认字体、回退顺序和 .ttc 里的字面（MIUI 会软链到主题字体配置）。 */
constexpr const char* kSystemFontConfig = "/system/etc/fonts.xml";

void logInfo(const char* message);
void logError(const std::string& message);

/** 配置里写的是文件名，按这些目录依次拼回绝对路径。 */
const char* const kFontSearchDirs[] = {
    "/system/fonts/",
    "/product/fonts/",
    "/system_ext/fonts/",
    "/data/system/fonts/theme_webview/",
};

std::string readFileText(const char* path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) return {};
    return std::string(std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>());
}

/** 取标签里 attribute="value" 的值；没有就是空串。 */
std::string attributeValue(const std::string& tag, const std::string& name) {
    size_t position = tag.find(name + "=");
    while (position != std::string::npos && position > 0 &&
           !(isspace(static_cast<unsigned char>(tag[position - 1])) || tag[position - 1] == '<')) {
        position = tag.find(name + "=", position + 1);
    }
    if (position == std::string::npos) return {};
    const size_t begin = position + name.size() + 1;
    if (begin >= tag.size()) return {};
    const char quote = tag[begin];
    if (quote != '"' && quote != '\'') return {};
    const size_t end = tag.find(quote, begin + 1);
    if (end == std::string::npos) return {};
    return tag.substr(begin + 1, end - begin - 1);
}

/** 配置里的文件名 → 绝对路径：按目录表找第一个存在的，都没有就用第一个目录拼。 */
std::string resolveFontPath(const std::string& file_name) {
    if (!file_name.empty() && file_name[0] == '/') return file_name;
    for (const char* dir : kFontSearchDirs) {
        const std::string candidate = std::string(dir) + file_name;
        std::ifstream probe(candidate, std::ios::binary);
        if (probe.good()) return candidate;
    }
    return std::string(kFontSearchDirs[0]) + file_name;
}

/** 这个字体文件能不能画中文：用「中」探一下，集合里任一字面覆盖就算。 */
bool coversChinese(const char* path) {
    FT_Library library = nullptr;
    if (FT_Init_FreeType(&library) != 0) return false;

    bool covered = false;
    FT_Face probe = nullptr;
    if (FT_New_Face(library, path, 0, &probe) == 0) {
        const FT_Long face_count = probe->num_faces;
        FT_Done_Face(probe);
        for (FT_Long i = 0; i < face_count && !covered; ++i) {
            FT_Face face = nullptr;
            if (FT_New_Face(library, path, i, &face) != 0) continue;
            covered = FT_Get_Char_Index(face, 0x4E2D) != 0;
            FT_Done_Face(face);
        }
    }
    FT_Done_FreeType(library);
    return covered;
}

/** 一个字族里挑出来的字体：文件路径 + 配置里声明的 PostScript 名（用来选 .ttc 里的字面）。 */
struct FamilyFont {
    std::string path;
    std::string postscript;
};

/** 在 <family> 块里挑 weight 最接近 400、非斜体的那个 <font>。 */
FamilyFont pickFamilyFont(const std::string& block) {
    FamilyFont best;
    int best_distance = 1 << 20;
    size_t position = 0;
    while ((position = block.find("<font", position)) != std::string::npos) {
        const size_t tag_end = block.find('>', position);
        if (tag_end == std::string::npos) break;
        const std::string attributes = block.substr(position + 5, tag_end - position - 5);

        // 标签后的第一段文本就是文件名（后面可能还跟 <axis .../> 子元素）
        size_t text_begin = tag_end + 1;
        while (text_begin < block.size() && isspace(static_cast<unsigned char>(block[text_begin]))) ++text_begin;
        const size_t text_end = block.find('<', text_begin);
        std::string file_name = block.substr(text_begin, (text_end == std::string::npos ? block.size() : text_end) - text_begin);
        while (!file_name.empty() && isspace(static_cast<unsigned char>(file_name.back()))) file_name.pop_back();
        position = tag_end + 1;

        if (file_name.empty() || attributeValue(attributes, "style") == "italic") continue;
        int weight = 400;
        const std::string weight_text = attributeValue(attributes, "weight");
        if (!weight_text.empty()) weight = atoi(weight_text.c_str());
        const int distance = weight > 400 ? weight - 400 : 400 - weight;
        if (distance >= best_distance) continue;

        best_distance = distance;
        best.path = resolveFontPath(file_name);
        best.postscript = attributeValue(attributes, "postScriptName");
    }
    return best;
}

/**
 * 按系统配置挑字体：fonts.xml 里第一个「能画中文」的字族，就是 Android 自己会用的那个。
 *
 * 这样小米的 MiSans、AOSP 的 Noto CJK、以及用户装的主题字体都能自动跟上，
 * 不用猜某个具体文件名（这台机器的 /system/etc/fonts.xml 甚至软链到主题字体配置）。
 * 配置文件本身标注了「可能随版本变动」，所以解析失败就退回下面的候选列表。
 */
bool loadSystemFont() {
    const std::string config = readFileText(kSystemFontConfig);
    if (config.empty()) return false;

    size_t position = 0;
    while ((position = config.find("<family", position)) != std::string::npos) {
        const size_t block_end = config.find("</family>", position);
        if (block_end == std::string::npos) break;
        const FamilyFont pick = pickFamilyFont(config.substr(position, block_end - position));
        position = block_end + 9;
        if (pick.path.empty() || !coversChinese(pick.path.c_str())) continue;
        // 有 PostScript 名就按名字选字面；没有就让 "sc" 兜底（Noto CJK 合集里 0 号是港式字形）
        const char* hint = pick.postscript.empty() ? "sc" : pick.postscript.c_str();
        if (ll_ui_load_font_face(pick.path.c_str(), kFontFamily, hint) == 0) {
            logInfo(("已加载系统字体：" + pick.path + (pick.postscript.empty() ? "" : "#" + pick.postscript)).c_str());
            return true;
        }
    }
    return false;
}

/** 系统字体通常带中日韩字形；一个都加载不上时界面仍能开，只是文字缺字。 */
void loadFont() {
    if (loadSystemFont()) return;
    for (const char* path : kFontCandidates) {
        // "sc" 是给字体集合用的：Noto Sans CJK 的 .ttc 里 0 号是香港字面，
        // 不挑字面的话简体字形会变成繁体/港式写法（例如“复”的中间会画成目）。
        if (ll_ui_load_font_face(path, kFontFamily, "sc") == 0) {
            logInfo((std::string("已加载字体：") + path).c_str());
            return;
        }
    }
    logError(std::string("没有可用字体，界面文字会缺字（候选：") + kFontCandidates[0] + " 等）");
}

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
    if (local.empty()) return;

    for (const Command& command : local) applyCommand(command);

    // 这批命令可能换了文档或改了模型，补一个唤醒事件让本帧立刻画出来。
    // 入队时那次 ll_ui_wake 已经被上一帧的 ProcessEvents 用掉了，而 power_save 的等待上限是 10 秒，
    // 不补的话新页面要等到用户下一次触摸才出现。
    ll_ui_wake();
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
