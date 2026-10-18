package moe.lolosia.web.util.fs

/**
 * 创建单个目录；目录已存在时返回非 0，调用方忽略即可（真正的失败留给随后的写入去暴露）。
 *
 * 拆成 expect/actual 是因为两端签名不同：POSIX 是 `mkdir(path, mode)`，mingw 的 CRT 只声明了
 * `mkdir(path)`（权限位在 Windows 上本来就被忽略）。
 */
internal expect fun makeDirectory(path: String)
