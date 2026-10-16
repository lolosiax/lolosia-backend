#Requires -Version 5.1
<#
.SYNOPSIS
    一次性构建 SDL3 静态库（thirdparty/<目标>/libSDL3.a + thirdparty/include/SDL3/**）。

.DESCRIPTION
    只在新增或升级 SDL3 时手动跑一次；产物（.a + 公共头文件）提交入库，源码不入库，
    日常 Gradle 构建只做 cinterop，不再碰 C 工具链（与 build-libs.ps1 同一约定）。

    另一点与 build-libs.ps1 相同：编译器直接用 Kotlin/Native 自带的那一套
    （clang + msys2 sysroot，见 build-libs.ps1 的文件头说明），因此产物的 ABI/glibc
    与 Kotlin/Native 链接期的完全一致。

    与 build-libs.ps1 的差异：SDL3 只支持 CMake 构建，而本机没有系统级 cmake，
    因此这里按下面的顺序找 cmake / ninja，都找不到就在提示里给出 pip 安装命令：
      1. PATH
      2. python 用户级 Scripts 目录（%APPDATA%\Python\Python3xx\Scripts）
      3. python 安装级 Scripts 目录（sysconfig 的 scripts 路径）

.PARAMETER Target
    要构建的目标，当前只支持 mingwX64。

.PARAMETER Version
    SDL3 版本号，默认 3.4.18。

.PARAMETER WorkDir
    源码下载/解压/CMake 构建目录，默认 <repo>/build/thirdparty（已被 .gitignore 忽略）。

.PARAMETER Force
    清掉构建目录与已下载的源码包，全部重来。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File thirdparty/build-sdl3.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('mingwX64')]
    [string]$Target = 'mingwX64',
    [string]$Version = '3.4.18',
    [string]$WorkDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot 'build/thirdparty' }
$srcDir = Join-Path $WorkDir 'src'
$treeDir = Join-Path $WorkDir 'tree'
$buildDir = Join-Path $WorkDir "cmake/$Target/sdl3"
$prefixDir = Join-Path $WorkDir "deps/$Target/sdl3"
$incOut = Join-Path $PSScriptRoot 'include'
$libOut = Join-Path $PSScriptRoot $Target

function Info([string]$m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Warn([string]$m) { Write-Host "!!  $m" -ForegroundColor Yellow }
function Fail([string]$m) { throw $m }

# ------------------------------------------------ 0. 定位 cmake / ninja
function Resolve-Tool([string]$Name) {
    $cmd = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @()
    if ($env:APPDATA) {
        $pyUser = Join-Path $env:APPDATA 'Python'
        if (Test-Path $pyUser) {
            $candidates += Get-ChildItem $pyUser -Directory -Filter 'Python3*' -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName 'Scripts' }
        }
    }
    $sysScripts = (& python -c "import sysconfig; print(sysconfig.get_path('scripts'))" 2>$null)
    if ($sysScripts) { $candidates += $sysScripts.Trim() }

    foreach ($dir in $candidates) {
        $p = Join-Path $dir "$Name.exe"
        if (Test-Path $p) { return $p }
    }
    Fail "找不到 $Name。安装：python -m pip install cmake ninja（装完无需加 PATH，本脚本会自行定位）"
}

$cmake = Resolve-Tool 'cmake'
$ninja = Resolve-Tool 'ninja'
Write-Host "    cmake   : $cmake"
Write-Host "    ninja   : $ninja"

# ------------------------------------------------ 1. 定位 Kotlin/Native 工具链
Info '定位 Kotlin/Native 自带交叉工具链'

$versionsToml = Join-Path $repoRoot 'gradle/libs.versions.toml'
$kotlinVersion = (Select-String -Path $versionsToml -Pattern '^\s*kotlin\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$deps = Join-Path $env:USERPROFILE '.konan/dependencies'
if (-not (Test-Path $deps)) { Fail "找不到 $deps，请先执行一次 Gradle 构建以下载 Kotlin/Native 依赖" }

$llvmHome = Get-ChildItem $deps -Directory -Filter 'llvm-*-windows-essentials-*' |
    Sort-Object Name -Descending | Select-Object -First 1
$msysHome = Get-ChildItem $deps -Directory -Filter 'msys2-mingw-w64-x86_64-*' | Select-Object -First 1
foreach ($d in @($llvmHome, $msysHome)) {
    if (-not $d) { Fail "Kotlin/Native 依赖不完整（缺 llvm 或 msys2-mingw），请先执行一次 Gradle 构建" }
}

$clang = Join-Path $llvmHome.FullName 'bin/clang.exe'
# SDL3 在 Windows 上会 enable_language(CXX)（src/core/windows/*.cpp 是 C++），
# 不给它指 clang++ 的话 CMake 会自己在 sysroot 里翻出 c++.exe，而那是台残废的 GCC 驱动（缺 cc1plus）
$clangxx = Join-Path $llvmHome.FullName 'bin/clang++.exe'
$sysroot = $msysHome.FullName
$windres = Join-Path $msysHome.FullName 'bin/windres.exe'
foreach ($p in @($clang, $clangxx, $sysroot, $windres)) {
    if (-not (Test-Path $p)) { Fail "缺文件：$p" }
}
Write-Host "    clang   : $clang"
Write-Host "    clang++ : $clangxx"
Write-Host "    sysroot : $sysroot"
Write-Host "    windres : $windres"
Write-Host "    kotlin  : $kotlinVersion"

# ------------------------------------------------ 2. 下载解压（源码不入库）
$tarball = Join-Path $srcDir "SDL3-$Version.tar.gz"
$sdlTree = Join-Path $treeDir "SDL3-$Version"

if ($Force) {
    Info '清理旧的构建目录'
    foreach ($p in @($buildDir, $prefixDir, $sdlTree)) {
        if (Test-Path $p) { Remove-Item $p -Recurse -Force }
    }
    if (Test-Path $tarball) { Remove-Item $tarball -Force }
}
New-Item -ItemType Directory -Force -Path $srcDir, $treeDir | Out-Null

if (-not (Test-Path $tarball)) {
    Info "下载 SDL3 $Version"
    $url = "https://github.com/libsdl-org/SDL/releases/download/release-$Version/SDL3-$Version.tar.gz"
    Invoke-WebRequest -Uri $url -OutFile $tarball -UseBasicParsing -TimeoutSec 600
    $magic = [System.IO.File]::ReadAllBytes($tarball)[0..1]
    if ($magic[0] -eq 0x3C -and $magic[1] -eq 0x21) {
        Remove-Item $tarball -Force
        Fail "下载到的是 HTML 而非 tarball，源地址已失效（$url）"
    }
} else {
    Write-Host "    已存在 SDL3-$Version.tar.gz"
}

if (-not (Test-Path $sdlTree)) {
    Info "解压 SDL3 $Version"
    & tar -xzf $tarball -C $treeDir
    if ($LASTEXITCODE -ne 0) { Fail "解压失败：$tarball" }
}
if (-not (Test-Path (Join-Path $sdlTree 'CMakeLists.txt'))) { Fail "源码目录不完整：$sdlTree" }

# ---------------------------------------------------------------- 2.5 打补丁
# 共同的背景：SDL3 在 mingw-w64 下会 `#include <intrin.h>`，而 clang 自带的 intrin.h 会先把
# SSE3 系内建（pmmintrin.h）声明成 constexpr，随后 mingw 的 intrin.h 再以非 constexpr 重声明一次。
# C 模式下这只是警告，C++ 模式下是硬错误 —— SDL3 有 C++ 源文件，且 CMake 给目标挂了 PCH，必然踩到。
# 补丁按精确文本替换，SDL3 改版后若这段没了会直接报错，避免补丁静默失效。
$sdlPatches = @(
    @{
        Note = 'SDL_assert.h：clang 下绕开 <intrin.h>'
        File = 'include/SDL3/SDL_assert.h'
        Old  = @"
#elif defined(__MINGW32__)
    #include <_mingw.h>
    #ifdef __MINGW64_VERSION_MAJOR
"@
        New  = @"
#elif defined(__MINGW32__)
    #include <_mingw.h>
    #if defined(__MINGW64_VERSION_MAJOR) && !defined(__clang__)
"@
    }
    @{
        Note = 'SDL_intrin.h：clang 的 C++ 侧改用自带的 x86intrin.h'
        File = 'include/SDL3/SDL_intrin.h'
        Old  = @"
#elif defined(__MINGW64_VERSION_MAJOR)
#include <intrin.h>
"@
        New  = @"
#elif defined(__MINGW64_VERSION_MAJOR)
#if defined(__clang__) && defined(__cplusplus)
#include <x86intrin.h>
#else
#include <intrin.h>
#endif
"@
    }
)

foreach ($patch in $sdlPatches) {
    $file = Join-Path $sdlTree $patch.File
    if (-not (Test-Path $file)) { Fail "补丁目标不存在：$file" }
    $text = [IO.File]::ReadAllText($file)
    if ($text.Contains($patch.Old)) {
        Info "打补丁：$($patch.Note)"
        [IO.File]::WriteAllText($file, $text.Replace($patch.Old, $patch.New))
    } elseif (-not $text.Contains($patch.New)) {
        Fail "补丁无法应用（内容与预期不符）：$($patch.File)"
    } else {
        Write-Host "    已打过：$($patch.Note)"
    }
}

# ------------------------------------------------ 3. CMake 交叉构建（静态库）
# CMake 会把这些路径原样写进生成的 *.cmake 里，Windows 反斜杠会被当成转义
# （CMakeRCCompiler.cmake 里的 \U 直接报 Invalid character escape），统一换成正斜杠。
function CmPath([string]$p) { return ($p -replace '\\', '/') }

# 缓存目录一次性用，重跑时先清掉，避免上一轮的坏缓存把 configure 卡死
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }

Info "cmake configure  sdl3 [$Target]"
$cfg = @(
    '-S', (CmPath $sdlTree), '-B', (CmPath $buildDir), '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$(CmPath $ninja)",
    '-DCMAKE_SYSTEM_NAME=Windows',
    '-DCMAKE_SYSTEM_PROCESSOR=x86_64',
    # 交叉编译不做 try_run，试编译产物直接当静态库，省掉链接器依赖
    '-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY',
    "-DCMAKE_C_COMPILER=$(CmPath $clang)",
    '-DCMAKE_C_COMPILER_TARGET=x86_64-w64-mingw32',
    "-DCMAKE_CXX_COMPILER=$(CmPath $clangxx)",
    '-DCMAKE_CXX_COMPILER_TARGET=x86_64-w64-mingw32',
    "-DCMAKE_SYSROOT=$(CmPath $sysroot)",
    "-DCMAKE_RC_COMPILER=$(CmPath $windres)",
    '-DCMAKE_BUILD_TYPE=Release',
    # 静态库优先：只出 libSDL3.a，不出 dll 与导入库
    '-DSDL_SHARED=OFF',
    '-DSDL_STATIC=ON',
    '-DSDL_TESTS=OFF',
    '-DSDL_EXAMPLES=OFF',
    # 关掉 D3D12 相关后端：Kotlin/Native 那份 msys2 sysroot 的 dxgi1_6.h 没有 IDXGIFactory6，
    # 编不过；实验目标本来就是 Vulkan，这条链路不需要。
    # HAVE_DXGI1_6_H 是 check_include_file 的缓存变量，预先定义即可跳过探测（同时挡住
    # src/render/direct3d12 与 src/gpu/d3d12 两处源文件收集）。
    '-DHAVE_DXGI1_6_H=FALSE',
    '-DSDL_RENDER_D3D12=OFF',
    '-DSDL_WERROR=OFF'
)
& $cmake @cfg
if ($LASTEXITCODE -ne 0) { Fail "cmake configure 失败：sdl3 [$Target]" }

Info "cmake build      sdl3 [$Target]"
& $cmake '--build' $buildDir '--parallel'
if ($LASTEXITCODE -ne 0) { Fail "cmake build 失败：sdl3 [$Target]" }

# ------------------------------------------------ 4. 收产物
$sdlLib = Get-ChildItem $buildDir -Filter 'libSDL3*.a' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*.dll.a' -and $_.Name -ne 'libSDL3_test.a' } |
    Sort-Object Length -Descending | Select-Object -First 1
if (-not $sdlLib) { Fail "未生成 libSDL3.a，检查上面的构建输出" }

New-Item -ItemType Directory -Force -Path $libOut | Out-Null
Copy-Item $sdlLib.FullName (Join-Path $libOut 'libSDL3.a') -Force
$size = [math]::Round((Get-Item (Join-Path $libOut 'libSDL3.a')).Length / 1KB)
Write-Host "    -> $Target/libSDL3.a (${size} KB)"

Info '复制公共头文件到 thirdparty/include/SDL3'
$hdrSrc = Join-Path $sdlTree 'include/SDL3'
if (-not (Test-Path $hdrSrc)) { Fail "头文件目录不存在：$hdrSrc" }
$hdrDst = Join-Path $incOut 'SDL3'
if (Test-Path $hdrDst) { Remove-Item $hdrDst -Recurse -Force }
Copy-Item $hdrSrc $hdrDst -Recurse -Force
$hdrCount = (Get-ChildItem $hdrDst -Filter '*.h').Count
Write-Host "    -> include/SDL3（$hdrCount 个头文件）"

# ------------------------------------------------ 5. 静态链接所需的系统库
# SDL3 静态库不自带 Windows 系统库，最终链接由 Kotlin/Native 侧补 linkerOpts。
# 这里把 SDL3 自己算出来的清单打印出来，供 build.gradle.kts 抄写。
$staticTargets = Get-ChildItem $buildDir -Filter 'SDL3staticTargets.cmake' -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($staticTargets) {
    Write-Host ''
    Info 'SDL3 静态链接依赖（摘自 SDL3staticTargets.cmake，抄进 build.gradle.kts 的 linkerOpts）'
    Select-String -Path $staticTargets.FullName -Pattern 'INTERFACE_LINK_LIBRARIES' |
        ForEach-Object { Write-Host "    $($_.Line.Trim())" }
}

Write-Host ''
Info '完成。'
