// LolosiaUiInternal.h —— 门面内部跨文件共享的状态与工具（不是对外 C ABI，不进 include/）。
//
// 对外只暴露 lolosia_ui.h 里的 ll_ui_*；这个头给 src/ 下的几个 .cpp 互相用：
//   LolosiaUi.cpp            C ABI 实现与全局状态
//   ResourceFileInterface.cpp 把 RmlUi 的文件访问接到宿主（并保留磁盘回退）
//   SdlImage.cpp            把图片解码接到宿主
#ifndef LOLOSIA_UI_INTERNAL_H
#define LOLOSIA_UI_INTERNAL_H

#include "lolosia_ui.h"

namespace Rml {
class FileInterface;
}

/** 宿主注册的资源回调；未注册时字段为 nullptr，资源层要降级并留下日志而不是崩。 */
struct LlResourceCallbacks {
    ll_ui_read_file read_file;
    ll_ui_decode_image decode_image;
    void* user_data;
};

/** 全局唯一的回调表：由 ll_ui_set_resource_loader 写，由资源层读。 */
LlResourceCallbacks& ll_resource_callbacks();

/** RmlUi 的文件访问实现：先问宿主的 read_file 回调（classpath 等），不认识再退回磁盘。 */
Rml::FileInterface* ll_resource_file_interface();

/** 往 RmlUi 的日志通道打一条（走系统接口，能落到 stderr）。message 为 UTF-8。 */
void ll_log_info(const char* message);
void ll_log_error(const char* message);

#endif  // LOLOSIA_UI_INTERNAL_H
