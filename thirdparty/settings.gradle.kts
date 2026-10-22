rootProject.name = "lolosia-thirdparty"

// 每个第三方库一个子项目；配置在各自 build.gradle.kts 中声明，
// 打包、发布与源码编译任务由 thirdparty/buildSrc 的 moe.thirdparty.publish 插件提供。
include("zlib")
include("png")
include("webp")
include("jpeg")
include("gif")
include("msgpack")
include("sdl3")
include("freetype")
include("rmlui")
