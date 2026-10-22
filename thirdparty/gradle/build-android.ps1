#Requires -Version 5.1
<#
.SYNOPSIS
    为 Android 构建 UI 实验所需的原生库（SDL3 / FreeType / RmlUi / liblolosia.so）。

.DESCRIPTION
    Android 的 UI 宿主走 JNI：SDL3 以共享库形式提供 SDLActivity 的 native 胶水，
    FreeType 与 RmlUi 静态链进 liblolosia.so，最终随 APK 打进 jniLibs。

    与桌面那几个脚本的差异只在工具链：这里用 Android NDK 的 clang + sysroot，
    而不是 Kotlin/Native 自带的那套。源码仍放 <repo>/build/thirdparty（gitignore），
    产物落到 thirdparty/androidJniLibs/<ABI>/，由 :modules:app-android 直接引用。

    前提：
      - NDK r27c（默认在 D:\AndroidSdk\ndk\android-ndk-r27c，可用 -Ndk 指定）
      - ninja（python -m pip install ninja）
      - RmlUi 的后端胶水源码来自 build/thirdparty/tree/RmlUi-6.3/Backends

.PARAMETER Abi
    Android ABI，默认 x86_64（本机模拟器）。

.PARAMETER Ndk
    NDK 根目录；留空则依次查 ANDROID_NDK_HOME、ANDROID_SDK_ROOT/ndk、D:\AndroidSdk\ndk。

.PARAMETER WorkDir
    源码与 CMake 中间目录，默认 <repo>/build/thirdparty（已被 .gitignore 忽略）。

.PARAMETER Force
    清掉中间目录与源码树，全部重来。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File thirdparty/gradle/build-android.ps1 -Abi x86_64
#>
[CmdletBinding()]
param(
    [ValidateSet('x86_64', 'arm64-v8a')]
    [string]$Abi = 'x86_64',
    [string]$Ndk,
    [string]$WorkDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')

$repoRoot = Get-LolosiaRepoRoot
$thirdpartyDir = Join-Path $repoRoot 'thirdparty'
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot 'build/thirdparty' }
$buildRoot = Join-Path $WorkDir "cmake/android-$Abi"
$libOut = Join-Path $thirdpartyDir "androidJniLibs/$Abi"
$objDir = Join-Path $WorkDir "nativeCpp/android-$Abi/obj"

# ---------------------------------------------------------------- 0. NDK / 构建工具
function Resolve-Ndk([string]$Explicit) {
    if ($Explicit) {
        if (-not (Test-Path (Join-Path $Explicit 'build/cmake/android.toolchain.cmake'))) {
            Stop-WithError "不是有效的 NDK 目录：$Explicit"
        }
        return (Resolve-Path $Explicit).Path
    }
    $candidates = @()
    if ($env:ANDROID_NDK_HOME) { $candidates += $env:ANDROID_NDK_HOME }
    foreach ($sdk in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, 'D:\AndroidSdk')) {
        if (-not $sdk) { continue }
        $ndkRoot = Join-Path $sdk 'ndk'
        if (-not (Test-Path $ndkRoot)) { continue }
        $latest = Get-ChildItem $ndkRoot -Directory -Filter 'android-ndk-*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($latest) { $candidates += $latest.FullName }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path (Join-Path $candidate 'build/cmake/android.toolchain.cmake')) { return $candidate }
    }
    Stop-WithError '找不到 NDK：用 -Ndk 指定，或把 NDK 放到 D:\AndroidSdk\ndk\android-ndk-r27c'
}

Write-Step '定位 Android 工具链'
$ndkRoot = Resolve-Ndk $Ndk
$toolchainFile = Join-Path $ndkRoot 'build/cmake/android.toolchain.cmake'
$ndkPrebuilt = Join-Path $ndkRoot 'toolchains/llvm/prebuilt/windows-x86_64'
$clangXX = Join-Path $ndkPrebuilt 'bin/clang++.exe'
$triple = if ($Abi -eq 'x86_64') { 'x86_64-linux-android24' } else { 'aarch64-linux-android24' }
$cmake = Resolve-BuildTool 'cmake'
$ninja = Resolve-BuildTool 'ninja'
Write-Host "    NDK     : $ndkRoot"
Write-Host "    ABI     : $Abi ($triple)"
Write-Host "    cmake   : $cmake"
Write-Host "    ninja   : $ninja"

foreach ($p in @($toolchainFile, $clangXX, $cmake, $ninja)) {
    if (-not (Test-Path $p)) { Stop-WithError "缺文件：$p" }
}

# ---------------------------------------------------------------- 1. 源码
$sdlTree = Get-ThirdpartySource -Name 'SDL3-3.4.18' `
    -Library 'sdl3' -Version '3.4.18' -FileName 'SDL3-3.4.18.tar.gz' `
    -Url 'https://github.com/libsdl-org/SDL/releases/download/release-3.4.18/SDL3-3.4.18.tar.gz' `
    -WorkDir $WorkDir -Force:$Force
$ftTree = Get-ThirdpartySource -Name 'freetype-VER-2-15-0' `
    -Library 'freetype' -Version '2.15.0' -FileName 'freetype-VER-2-15-0.tar.gz' `
    -Url 'https://github.com/freetype/freetype/archive/refs/tags/VER-2-15-0.tar.gz' `
    -WorkDir $WorkDir -Force:$Force
$rmluiTree = Get-ThirdpartySource -Name 'RmlUi-6.3' `
    -Library 'rmlui' -Version '6.3' -FileName 'RmlUi-6.3.tar.gz' `
    -Url 'https://github.com/mikke89/RmlUi/archive/refs/tags/6.3.tar.gz' `
    -WorkDir $WorkDir -Force:$Force

$includeRoot = Join-Path $thirdpartyDir 'include'
$freetypeInc = Join-Path $includeRoot 'freetype2'

function Invoke-CMake([string]$Name, [string]$Source, [string[]]$ExtraArgs) {
    $dir = Join-Path $buildRoot $Name
    if ($Force -and (Test-Path $dir)) { Remove-Item $dir -Recurse -Force }
    Write-Step "cmake configure  $Name [$Abi]"
    $cfg = @(
        '-S', (ConvertTo-CmPath $Source), '-B', (ConvertTo-CmPath $dir), '-G', 'Ninja',
        "-DCMAKE_MAKE_PROGRAM=$(ConvertTo-CmPath $ninja)",
        "-DCMAKE_TOOLCHAIN_FILE=$(ConvertTo-CmPath $toolchainFile)",
        "-DANDROID_ABI=$Abi",
        '-DANDROID_PLATFORM=android-24',
        '-DANDROID_STL=c++_static',
        '-DCMAKE_BUILD_TYPE=Release'
    ) + $ExtraArgs
    & $cmake @cfg
    if ($LASTEXITCODE -ne 0) { Stop-WithError "cmake configure 失败：$Name [$Abi]" }

    Write-Step "cmake build      $Name [$Abi]"
    & $cmake '--build' $dir '--parallel'
    if ($LASTEXITCODE -ne 0) { Stop-WithError "cmake build 失败：$Name [$Abi]" }
}

function Find-Artifact([string]$Dir, [string]$Filter) {
    $items = Get-ChildItem $Dir -Recurse -Filter $Filter -File -ErrorAction SilentlyContinue
    if (-not $items) { Stop-WithError "未生成 $Filter（$Dir）" }
    return ($items | Sort-Object Length -Descending | Select-Object -First 1).FullName
}

# ---------------------------------------------------------------- 2. SDL3（共享库：含 SDLActivity 的 JNI 胶水）
Invoke-CMake 'sdl3' $sdlTree @(
    '-DSDL_SHARED=ON',
    '-DSDL_STATIC=OFF',
    '-DSDL_TESTS=OFF',
    '-DSDL_EXAMPLES=OFF',
    '-DSDL_INSTALL=OFF',
    '-DSDL_WERROR=OFF'
)
$sdlBuild = Join-Path $buildRoot 'sdl3'
$sdlShared = Get-ChildItem $sdlBuild -Recurse -Filter 'libSDL3.so' -File |
    Sort-Object Length -Descending | Select-Object -First 1
if (-not $sdlShared) { Stop-WithError '未生成 libSDL3.so' }

# ---------------------------------------------------------------- 3. FreeType（静态）
Invoke-CMake 'freetype' $ftTree @(
    '-DBUILD_SHARED_LIBS=OFF',
    '-DFT_DISABLE_ZLIB=ON',
    '-DFT_DISABLE_BZIP2=ON',
    '-DFT_DISABLE_PNG=ON',
    '-DFT_DISABLE_HARFBUZZ=ON',
    '-DFT_DISABLE_BROTLI=ON',
    '-DCMAKE_POSITION_INDEPENDENT_CODE=ON'
)
$freetypeLib = Find-Artifact (Join-Path $buildRoot 'freetype') 'libfreetype.a'

# ---------------------------------------------------------------- 4. RmlUi（静态）
Invoke-CMake 'rmlui' $rmluiTree @(
    '-DBUILD_SHARED_LIBS=OFF',
    '-DRMLUI_SAMPLES=OFF',
    '-DBUILD_TESTING=OFF',
    '-DRMLUI_LUA_BINDINGS=OFF',
    '-DRMLUI_LOTTIE_PLUGIN=OFF',
    '-DRMLUI_SVG_PLUGIN=OFF',
    '-DRMLUI_HARFBUZZ_SAMPLE=OFF',
    '-DRMLUI_FONT_ENGINE=freetype',
    '-DRMLUI_PRECOMPILED_HEADERS=ON',
    '-DRMLUI_WARNINGS_AS_ERRORS=OFF',
    "-DFREETYPE_LIBRARY=$(ConvertTo-CmPath $freetypeLib)",
    "-DFREETYPE_INCLUDE_DIR_ft2build=$(ConvertTo-CmPath $freetypeInc)",
    "-DFREETYPE_INCLUDE_DIR_freetype2=$(ConvertTo-CmPath $freetypeInc)"
)
$rmluiBuild = Join-Path $buildRoot 'rmlui'
$rmluiLib = Find-Artifact $rmluiBuild 'librmlui.a'
$rmluiDebuggerLib = Find-Artifact $rmluiBuild 'librmlui_debugger.a'

# ---------------------------------------------------------------- 5. 收产物
if ($Force -and (Test-Path $objDir)) { Remove-Item $objDir -Recurse -Force }
$stageLib = Join-Path $WorkDir "nativeCpp/android-$Abi/lib"
New-Item -ItemType Directory -Force -Path $libOut, $objDir, $stageLib | Out-Null
Copy-Item $sdlShared.FullName (Join-Path $stageLib 'libSDL3.so') -Force
Copy-Item $freetypeLib (Join-Path $stageLib 'libfreetype.a') -Force
Copy-Item $rmluiLib (Join-Path $stageLib 'librmlui.a') -Force
Copy-Item $rmluiDebuggerLib (Join-Path $stageLib 'librmlui_debugger.a') -Force
Copy-Item $sdlShared.FullName (Join-Path $libOut 'libSDL3.so') -Force

# ---------------------------------------------------------------- 6. liblolosia.so
$nativeCpp = Join-Path $repoRoot 'modules/client/src/nativeCpp'
$backendDir = Join-Path $rmluiTree 'Backends'
$backendSources = @(
    'RmlUi_Backend_SDL_GPU.cpp',
    'RmlUi_Renderer_SDL_GPU.cpp',
    'RmlUi_Platform_SDL.cpp'
) | ForEach-Object { Join-Path $backendDir $_ }
foreach ($src in $backendSources) {
    if (-not (Test-Path $src)) { Stop-WithError "缺后端源文件：$src" }
}

$sources = @()
$sources += Get-ChildItem (Join-Path $nativeCpp 'src') -Filter '*.cpp' -File | Sort-Object Name
$sources += Get-ChildItem (Join-Path $nativeCpp 'android') -Filter '*.cpp' -File | Sort-Object Name
$sources += $backendSources | ForEach-Object { Get-Item $_ }
if (-not $sources) { Stop-WithError '没有可编译的 C++ 源文件' }

$compileFlags = @(
    "--target=$triple",
    '-std=c++17', '-O2', '-fPIC', '-Wall', '-Wextra',
    '-DRMLUI_STATIC_LIB',
    '-DRMLUI_SDL_VERSION_MAJOR=3',
    '-I', $includeRoot,
    '-I', $freetypeInc,
    '-I', (Join-Path $nativeCpp 'include'),
    '-I', $backendDir
)

Write-Step "clang++ 编译      liblolosia [$Abi]"
$objects = @()
foreach ($src in $sources) {
    $obj = Join-Path $objDir (($src.BaseName) + '.o')
    & $clangXX @compileFlags -c $src.FullName -o $obj
    if ($LASTEXITCODE -ne 0) { Stop-WithError "编译失败：$($src.Name) [$Abi]" }
    $objects += $obj
    Write-Host "    cc $($src.Name)"
}

$so = Join-Path $libOut 'liblolosia.so'
if (Test-Path $so) { Remove-Item $so -Force }
& $clangXX @(
    "--target=$triple",
    '-shared', '-static-libstdc++',
    '-Wl,-soname,liblolosia.so',
    '-o', $so
) @objects @(
    "-L$stageLib",
    '-lrmlui_debugger', '-lrmlui', '-lfreetype',
    '-lSDL3',
    '-landroid', '-llog', '-ljnigraphics'
)
if ($LASTEXITCODE -ne 0) { Stop-WithError "链接失败：liblolosia.so [$Abi]" }

# 发布产物不需要符号表；不 strip 的话 liblolosia.so 会接近 40 MB（APK 体积的大头）
$strip = Join-Path $ndkPrebuilt 'bin/llvm-strip.exe'
if (Test-Path $strip) {
    & $strip '--strip-unneeded' $so (Join-Path $libOut 'libSDL3.so')
    if ($LASTEXITCODE -ne 0) { Stop-WithError "strip 失败：$Abi" }
}

Write-Host ''
Write-Step '完成'
Get-ChildItem $libOut -File | ForEach-Object {
    Write-Host ("    -> {0} ({1} KB)" -f $_.Name, [math]::Round($_.Length / 1KB))
}
