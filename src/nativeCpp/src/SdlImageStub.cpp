// SdlImageStub.cpp —— SDL3_image 占位实现，只为让 RmlUi 的 SDL_GPU 渲染后端能编进来。
//
// RmlUi 的渲染后端在加载图片纹理时调用 IMG_LoadTyped_IO()，而接图片这件事本轮没做
// （理由见 include/SDL3_image/SDL_image.h）。这里给出一个「大声失败」的实现：
// 关掉调用方交过来的 IO 流、往 RmlUi 日志里打一条 ERROR、返回 nullptr。
//
// 返回 nullptr 之后 RmlUi 会把纹理句柄当 0 处理（`return 0;`），界面缺图但不会崩。
// 真正需要图片时，把这个函数换成基于仓库已有 C 编解码库（thirdparty 里的
// png / jpeg / webp / gif）或换成真正的 SDL_image 即可。

#include <SDL3/SDL.h>
#include <SDL3_image/SDL_image.h>

#include <RmlUi/Core/Log.h>

extern "C" SDL_Surface* IMG_LoadTyped_IO(SDL_IOStream* src, bool closeio, const char* type) {
    // SDL_image 的约定：closeio 为真时由被调用方关闭流，这里照做以免调用方泄漏
    if (src != nullptr && closeio) {
        SDL_CloseIO(src);
    }
    Rml::Log::Message(
        Rml::Log::LT_ERROR,
        "图片加载未接入：IMG_LoadTyped_IO 目前是占位实现（type=%s），界面里引用到图片的地方会缺图。"
        "需要图片时请按 src/nativeCpp/include/SDL3_image/SDL_image.h 的说明接入。",
        type == nullptr ? "(未知)" : type);
    return nullptr;
}
