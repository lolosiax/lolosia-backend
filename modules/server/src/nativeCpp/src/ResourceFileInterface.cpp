// ResourceFileInterface.cpp —— 把 RmlUi 的资源访问接到宿主，并保留磁盘回退。
//
// RmlUi 加载文档、样式、图片、字体时都走 Rml::FileInterface。默认实现读磁盘路径，
// 而本项目的界面资源在 classpath（发布时是内嵌 zip）里，磁盘上并不存在，
// 所以这里换成一个两段式的实现：
//
//   1. 先问宿主（read_file 回调，Kotlin 侧就是 classpath.read）；
//   2. 宿主不认识这个路径就退回真实文件系统。
//
// 第 2 步不是可选项：字体就是磁盘文件（C:/Windows/Fonts/…），少了回退会导致
// 界面文字全部画不出来 —— 而且日志里只会看到「字体加载失败」，与图片资源毫无关联。
//
// 路径约定：RML 里写以 `/` 开头的路径（如 `/ui/sample.png`）最稳 —— RmlUi 的
// SystemInterface::JoinPath 对以 `/` 开头的路径会直接剥掉前缀返回，与文档所在位置无关，
// 于是它就成了「classpath 根相对路径」。

#include "LolosiaUiInternal.h"

#include <RmlUi/Core/FileInterface.h>

#include <stdio.h>
#include <string.h>
#include <vector>

namespace {

/** 内容超过这个大小就不接了：正常资源没有这么大，多半是路径写错指到了别的东西。 */
constexpr size_t kMaxResourceBytes = 64u * 1024u * 1024u;

/** 一个已打开的资源；句柄就是它的指针。 */
struct OpenResource {
    bool from_host;                    // true：内容来自宿主（classpath）；false：磁盘文件
    std::vector<unsigned char> data;   // from_host 时的内容
    size_t position;                   // from_host 时的读位置
    FILE* file;                        // 磁盘回退时的文件
};

// RmlUi 6 的 FileHandle 是整数类型（早期版本是指针），所以句柄在「指针 ↔ 整数」之间转。
// 这是上游自己的做法：FileInterfaceDefault 就是 `(FileHandle)fopen(...)`，失败用 0 表示。
Rml::FileHandle toHandle(OpenResource* resource) { return reinterpret_cast<Rml::FileHandle>(resource); }
OpenResource* toResource(Rml::FileHandle handle) { return reinterpret_cast<OpenResource*>(handle); }

class ResourceFileInterface : public Rml::FileInterface {
public:
    Rml::FileHandle Open(const Rml::String& path) override {
        OpenResource* resource = openFromHost(path);
        if (resource == nullptr) resource = openFromDisk(path);
        return resource == nullptr ? 0 : toHandle(resource);
    }

    void Close(Rml::FileHandle file_handle) override {
        OpenResource* resource = toResource(file_handle);
        if (resource == nullptr) return;
        if (resource->file != nullptr) fclose(resource->file);
        delete resource;
    }

    size_t Read(void* buffer, size_t size, Rml::FileHandle file_handle) override {
        OpenResource* resource = toResource(file_handle);
        if (resource == nullptr || buffer == nullptr) return 0;

        if (resource->file != nullptr) return fread(buffer, 1, size, resource->file);

        const size_t remaining = resource->data.size() - resource->position;
        const size_t count = size < remaining ? size : remaining;
        memcpy(buffer, resource->data.data() + resource->position, count);
        resource->position += count;
        return count;
    }

    bool Seek(Rml::FileHandle file_handle, long offset, int origin) override {
        OpenResource* resource = toResource(file_handle);
        if (resource == nullptr) return false;

        if (resource->file != nullptr) return fseek(resource->file, offset, origin) == 0;

        const long base = (origin == SEEK_SET) ? 0L
                        : (origin == SEEK_CUR) ? static_cast<long>(resource->position)
                                               : static_cast<long>(resource->data.size());
        const long target = base + offset;
        if (target < 0 || static_cast<size_t>(target) > resource->data.size()) return false;
        resource->position = static_cast<size_t>(target);
        return true;
    }

    size_t Tell(Rml::FileHandle file_handle) override {
        OpenResource* resource = toResource(file_handle);
        if (resource == nullptr) return 0;
        if (resource->file != nullptr) {
            const long position = ftell(resource->file);
            return position < 0 ? 0 : static_cast<size_t>(position);
        }
        return resource->position;
    }

    size_t Length(Rml::FileHandle file_handle) override {
        OpenResource* resource = toResource(file_handle);
        if (resource == nullptr) return 0;
        if (resource->file == nullptr) return resource->data.size();

        // 磁盘文件：记下当前位置，跳到末尾问长度，再跳回来（与基类默认实现同一思路）
        const long current = ftell(resource->file);
        if (current < 0) return 0;
        if (fseek(resource->file, 0, SEEK_END) != 0) return 0;
        const long end = ftell(resource->file);
        fseek(resource->file, current, SEEK_SET);
        return end < 0 ? 0 : static_cast<size_t>(end);
    }

private:
    /** 经宿主的 read_file 回调取内容；回调没注册、文件不存在、或内容不合理时返回 nullptr。 */
    static OpenResource* openFromHost(const Rml::String& path) {
        const LlResourceCallbacks& callbacks = ll_resource_callbacks();
        if (callbacks.read_file == nullptr) return nullptr;

        // 两次调用：先问需要多大，再真正取内容
        const int32_t needed = callbacks.read_file(callbacks.user_data, path.c_str(), nullptr, 0);
        if (needed <= 0 || static_cast<size_t>(needed) > kMaxResourceBytes) return nullptr;

        OpenResource* resource = new OpenResource{true, {}, 0, nullptr};
        resource->data.resize(static_cast<size_t>(needed));
        const int32_t written =
            callbacks.read_file(callbacks.user_data, path.c_str(), resource->data.data(), needed);
        if (written != needed) {
            delete resource;
            return nullptr;
        }
        return resource;
    }

    static OpenResource* openFromDisk(const Rml::String& path) {
        FILE* file = fopen(path.c_str(), "rb");
        if (file == nullptr) return nullptr;
        return new OpenResource{false, {}, 0, file};
    }
};

ResourceFileInterface g_file_interface;

}  // namespace

Rml::FileInterface* ll_resource_file_interface() { return &g_file_interface; }
