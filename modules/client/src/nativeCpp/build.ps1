#Requires -Version 5.1
<#
.SYNOPSIS
    编译自研的 C/C++ 门面层为 build/thirdparty/out/<目标>/liblolosia.a。

.DESCRIPTION
    src/nativeCpp 下的 C++ 代码把 RmlUi / SDL3 包成 ll_ui_* 这套 C ABI，供 Kotlin 侧用 cinterop 调用。
    自研 .a 与第三方静态库放在一起（build/thirdparty/out/<目标>/），这样 cinterop 只需要一个 libraryPath。

    编进来的有两部分：
      1. 自研门面 src/nativeCpp/src/*.cpp；
      2. RmlUi 的后端胶水（Backends/ 下的 SDL_GPU 三件套）。它不产出独立库，
         上游也是让使用者把这些 .cpp 加进自己的目标里（见 RmlUi 的 Backends/CMakeLists.txt）。

    编译器与 gradle/build-codecs.ps1 / sdl3/build.ps1 / freetype/build.ps1 / rmlui/build.ps1 一致，
    都是 Kotlin/Native 自带的那套（clang + msys2 sysroot），保证 ABI 与最终可执行文件对齐。

    依赖（都得先跑各自的脚本）：
      build/thirdparty/out/include/SDL3/**          thirdparty/sdl3/build.ps1
      build/thirdparty/out/<目标>/libSDL3.a
      build/thirdparty/out/include/RmlUi/**        thirdparty/rmlui/build.ps1
      build/thirdparty/out/<目标>/librmlui*.a      thirdparty/rmlui/build.ps1（含内置调试器 librmlui_debugger.a）
      build/thirdparty/out/include/freetype2/**    thirdparty/freetype/build.ps1
      build/thirdparty/out/<目标>/libfreetype.a
      <repo>/build/thirdparty/tree/RmlUi-*/Backends/**（后端胶水的源码，随 rmlui/build.ps1 落到那里）

    ⚠️ 从引入 RmlUi 起，liblolosia.a 会引用 C++ 运行时（libstdc++）——
    之前那层「不用 STL、-fno-exceptions」的性质到此结束，链接期由 build.gradle.kts 补 -lstdc++。

.PARAMETER Target
    要构建的目标，当前只支持 mingwX64。

.PARAMETER WorkDir
    编译中间目录，默认 <repo>/build/nativeCpp/<目标>（已被 .gitignore 忽略）。

.PARAMETER Force
    清掉中间目录，全部重编。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File src/nativeCpp/build.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('mingwX64')]
    [string]$Target = 'mingwX64',
    [string]$WorkDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../../../../thirdparty/gradle/toolchain.ps1')

$repoRoot = Get-LolosiaRepoRoot
$outRoot = Join-Path $repoRoot 'build/thirdparty/out'
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot "build/nativeCpp/$Target" }
$objDir = Join-Path $WorkDir 'obj'
$libOut = Join-Path $outRoot $Target
$includeRoot = Join-Path $outRoot 'include'

$backendSources = @(
    'RmlUi_Backend_SDL_GPU.cpp',
    'RmlUi_Renderer_SDL_GPU.cpp',
    'RmlUi_Platform_SDL.cpp'
)

# ---------------------------------------------------------------- 1. 工具链
Write-Step '定位 Kotlin/Native 自带交叉工具链'
$tool = Get-KonanToolchain $Target
Write-ToolchainSummary $tool

# ---------------------------------------------------------------- 2. 依赖自检
Write-Step '检查依赖'
$required = @(
    @{ Path = (Join-Path $includeRoot 'SDL3/SDL.h'); Hint = 'thirdparty/sdl3/build.ps1' },
    @{ Path = (Join-Path $libOut 'libSDL3.a'); Hint = 'thirdparty/sdl3/build.ps1' },
    @{ Path = (Join-Path $includeRoot 'RmlUi/Core.h'); Hint = 'thirdparty/rmlui/build.ps1' },
    @{ Path = (Join-Path $libOut 'librmlui.a'); Hint = 'thirdparty/rmlui/build.ps1' },
    @{ Path = (Join-Path $libOut 'librmlui_debugger.a'); Hint = 'thirdparty/rmlui/build.ps1' },
    @{ Path = (Join-Path $includeRoot 'freetype2/ft2build.h'); Hint = 'thirdparty/freetype/build.ps1' },
    @{ Path = (Join-Path $libOut 'libfreetype.a'); Hint = 'thirdparty/freetype/build.ps1' }
)
foreach ($item in $required) {
    if (-not (Test-Path $item.Path)) { Stop-WithError "缺少 $($item.Path)（先跑 $($item.Hint)）" }
}

# 后端胶水的源码只在构建目录里存在（源码不入库），所以这里必须能找到 RmlUi 的源码树
$rmluiTree = Get-ChildItem (Join-Path $repoRoot 'build/thirdparty/tree') -Directory -Filter 'RmlUi-*' `
    -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if (-not $rmluiTree) {
    Stop-WithError '找不到 RmlUi 源码树：<repo>/build/thirdparty/tree/RmlUi-*（先跑 thirdparty/rmlui/build.ps1）'
}
$backendDir = Join-Path $rmluiTree.FullName 'Backends'
foreach ($name in $backendSources) {
    if (-not (Test-Path (Join-Path $backendDir $name))) { Stop-WithError "后端源文件不存在：$backendDir/$name" }
}
Write-Host "    RmlUi   : $($rmluiTree.Name)"

# ---------------------------------------------------------------- 3. 编译
if ($Force -and (Test-Path $WorkDir)) { Remove-Item $WorkDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $objDir, $libOut | Out-Null

$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Filter '*.cpp' | Sort-Object Name)
if (-not $sources) { Stop-WithError 'src/nativeCpp/src 下没有 .cpp 源文件' }
$sources += $backendSources | ForEach-Object { Get-Item (Join-Path $backendDir $_) }

$compileFlags = @(
    "--target=$($tool.Triple)", "--sysroot=$($tool.Sysroot)",
    '-std=c++17', '-O2', '-Wall', '-Wextra',
    # RmlUi 以静态库链接，头文件里的 __dllspec 要据此展开为空
    '-DRMLUI_STATIC_LIB',
    # RmlUi 的 SDL 后端按这个宏选择 SDL3 的 API 分支
    '-DRMLUI_SDL_VERSION_MAJOR=3',
    '-I', $includeRoot,
    # FreeType 的惯例：ft2build.h 所在目录本身要在 include path 上
    '-I', (Join-Path $includeRoot 'freetype2'),
    '-I', (Join-Path $PSScriptRoot 'include'),
    '-I', $backendDir
)

Write-Step "clang++ 编译      liblolosia [$Target]"
$objects = @()
foreach ($src in $sources) {
    $obj = Join-Path $objDir (($src.BaseName) + '.o')
    & $tool.ClangXX @compileFlags -c $src.FullName -o $obj
    if ($LASTEXITCODE -ne 0) { Stop-WithError "编译失败：$($src.Name) [$Target]" }
    $objects += $obj
    Write-Host "    cc $($src.Name)"
}

# ---------------------------------------------------------------- 4. 归档
$lib = Join-Path $libOut 'liblolosia.a'
if (Test-Path $lib) { Remove-Item $lib -Force }
& $tool.LlvmAr rcs $lib @objects
if ($LASTEXITCODE -ne 0) { Stop-WithError "归档失败：liblolosia.a [$Target]" }
Write-Host "    -> $Target/liblolosia.a ($([math]::Round((Get-Item $lib).Length / 1KB)) KB)"

Write-Host ''
Write-Step '完成。'
