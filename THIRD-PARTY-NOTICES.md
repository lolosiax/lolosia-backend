# 第三方组件与许可声明

本仓库自身代码以 [MIT](LICENSE) 许可发布。下面列出会被构建进最终产物的第三方组件，以及仓库内随源码分发的第三方代码。

再分发本项目的**二进制产物**时，请随产物保留本文件（或等价的许可声明）。

## 1. 构建期下载的原生静态库

这些库的**源码与构建产物都不入库**：版本在 [`thirdparty/manifest.json`](thirdparty/manifest.json) 声明，
构建时由 `thirdparty/gradle/build-codecs.ps1` 与 `thirdparty/{sdl3,freetype,rmlui}/build.ps1` 下载并交叉编译，
产物落 `build/thirdparty/out/`（已忽略），最终静态链接进可执行文件。

| 组件 | 版本 | 许可 | 上游 |
| --- | --- | --- | --- |
| zlib | 1.3.1 | zlib License | <https://github.com/madler/zlib> |
| libpng | 1.6.44 | libpng License（PNG Reference Library License v2） | <https://github.com/pnggroup/libpng> |
| libjpeg-turbo | 3.1.0 | IJG License + zlib License + BSD-3-Clause（SIMD 部分） | <https://github.com/libjpeg-turbo/libjpeg-turbo> |
| libwebp | 1.6.0 | BSD-3-Clause | <https://chromium.googlesource.com/webm/libwebp> |
| giflib | 5.2.2 | MIT | <https://sourceforge.net/projects/giflib/> |
| msgpack-c | 7.0.2 | Boost Software License 1.0 | <https://github.com/msgpack/msgpack-c> |
| SDL3 | 3.4.18 | zlib License | <https://github.com/libsdl-org/SDL> |
| FreeType | 2.15.0 | FreeType License（FTL） | <https://github.com/freetype/freetype> |
| RmlUi | 6.3 | MIT | <https://github.com/mikke89/RmlUi> |

各库的完整许可文本以上游仓库内的 `LICENSE` / `COPYING` 为准。

**FreeType** 的 FTL 要求在文档中保留以下声明：

> Portions of this software are copyright © The FreeType Project (<https://www.freetype.org>). All rights reserved.

**libpng** 的许可要求在再分发时保留其版权声明：若修改过源码，还需明确标注为修改版。
本项目只对 libpng 的构建脚本做交叉编译适配，不修改其运行时代码。

## 2. 随源码分发的第三方代码

| 位置 | 来源 | 许可 |
| --- | --- | --- |
| `modules/app-android/src/main/java/org/libsdl/app/*.java` | SDL 3.4.18 的 Android Java 胶水（`org.libsdl.app`） | zlib License，Copyright © Sam Lantinga 及 SDL 贡献者 |
| `modules/server/src/commonMain/kotlin/moe/lolosia/web/util/crypto/BCrypt.kt` | 移植自 jBCrypt 0.4（`org.mindrot.jbcrypt.BCrypt`） | ISC License，Copyright © 2006 Damien Miller；版权头已在文件内保留 |

SDL3 的 Java 胶水为原样引入，未作修改；其许可同第 1 节的 SDL3 条目。

## 3. 由 Gradle 解析并静态链接的依赖

这些依赖的源码不随本仓库分发，构建时由 Gradle 从 Maven 仓库解析，并在 Kotlin/Native 下静态链接进可执行文件。

| 组件 | 许可 |
| --- | --- |
| Kotlin/Native 运行库与标准库、kotlinx.coroutines / kotlinx.serialization / kotlinx-datetime | Apache-2.0 |
| Ktor（3.4.2） | Apache-2.0 |
| Koin（4.2.2） | Apache-2.0 |
| okio（3.9.1） | Apache-2.0 |
| KAML（0.97.0） | Apache-2.0 |
| sqlx4k（1.14.0） | MIT；native 目标内嵌预编译的 Rust `sqlx` 静态库（MIT OR Apache-2.0） |

完整依赖清单见各模块的 `build.gradle.kts` 与 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。
Apache-2.0 组件的许可全文见 <https://www.apache.org/licenses/LICENSE-2.0>。
