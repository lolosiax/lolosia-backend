#Requires -Version 5.1
<#
.SYNOPSIS
    一次性构建 RmlUi 静态库（thirdparty/<目标>/librmlui.a + thirdparty/include/RmlUi/**）。

.DESCRIPTION
    RmlUi 是 UI 实验的视图层：HTML/RCSS 描述结构、Flexbox 自动算布局、data-model 做数据绑定（MVVM）。
    与 sdl3/build.ps1 / freetype/build.ps1 同一约定：只在新增或升级时手动跑一次，
    源码不入库、产物入库，日常 Gradle 构建只做 cinterop + 链接。

    前提：先跑 freetype/build.ps1 —— RmlUi 的字体引擎（RMLUI_FONT_ENGINE=freetype）要链接 FreeType。
    FreeType 不走 find_package 的搜索路径（它装在 thirdparty 这个非标准前缀下），
    而是把 FREETYPE_INCLUDE_DIR_* / FREETYPE_LIBRARY 三个缓存变量直接指过去，
    CMake 自带的 FindFreetype.cmake 会原样采纳，不依赖任何 pkg-config / config 文件。

    出两个静态库，不编 samples / tests / Lua 绑定 / Lottie / SVG 插件：
      - librmlui.a           Rml::* 全部内容 + 默认的 FreeType 字体引擎
      - librmlui_debugger.a  内置调试器（查元素树、看数据模型、看日志、画元素边框）；
                             F8 开关，宿主经 Rml::Debugger::* 接入，见 lolosia_ui.h 的 ll_ui_enable_debugger
    后端（Backends/ 下的 SDL_GPU 那几个 .cpp）不在这个脚本里 —— 它们不产出独立库，
    由 src/nativeCpp/build.ps1 连同自研门面一起编进 liblolosia.a。

.PARAMETER Target
    要构建的目标，当前只支持 mingwX64。

.PARAMETER Version
    RmlUi 版本号，默认 6.3（要求 C++17）。

.PARAMETER WorkDir
    源码与 CMake 中间目录，默认 <repo>/build/thirdparty（已被 .gitignore 忽略）。

.PARAMETER Force
    清掉源码与中间产物，全部重来。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File thirdparty/rmlui/build.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('mingwX64')]
    [string]$Target = 'mingwX64',
    [string]$Version = '6.3',
    [string]$WorkDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'gradle/toolchain.ps1')

$repoRoot = Get-LolosiaRepoRoot
$thirdpartyDir = Join-Path $repoRoot 'thirdparty'
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot 'build/thirdparty' }
$buildDir = Join-Path $WorkDir "cmake/$Target/rmlui"
$libOut = Join-Path $thirdpartyDir $Target
$incOut = Join-Path $thirdpartyDir 'include/RmlUi'

# ---------------------------------------------------------------- 1. 工具链
Write-Step '定位工具链'
$tool = Get-KonanToolchain $Target
Write-ToolchainSummary $tool
$cmake = Resolve-BuildTool 'cmake'
$ninja = Resolve-BuildTool 'ninja'
Write-Host "    cmake   : $cmake"
Write-Host "    ninja   : $ninja"

# ---------------------------------------------------------------- 2. 依赖自检
$ftInc = Join-Path $thirdpartyDir 'include/freetype2'
$ftLib = Join-Path $libOut 'libfreetype.a'
if (-not (Test-Path (Join-Path $ftInc 'ft2build.h'))) {
    Stop-WithError "缺少 FreeType 头文件：$ftInc（先跑 thirdparty/freetype/build.ps1）"
}
if (-not (Test-Path $ftLib)) {
    Stop-WithError "缺少 FreeType 静态库：$ftLib（先跑 thirdparty/freetype/build.ps1）"
}

# ---------------------------------------------------------------- 3. 源码
$tree = Get-ThirdpartySource -Name "RmlUi-$Version" `
    -Url "https://github.com/mikke89/RmlUi/archive/refs/tags/$Version.tar.gz" `
    -WorkDir $WorkDir -Force:$Force

# ---------------------------------------------------------------- 4. CMake 交叉构建
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }

Write-Step "cmake configure  rmlui $Version [$Target]"
$cfg = @(
    '-S', (ConvertTo-CmPath $tree), '-B', (ConvertTo-CmPath $buildDir), '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$(ConvertTo-CmPath $ninja)",
    '-DCMAKE_SYSTEM_NAME=Windows',
    '-DCMAKE_SYSTEM_PROCESSOR=x86_64',
    # 交叉编译不做 try_run，试编译产物直接当静态库，省掉链接器依赖
    '-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY',
    "-DCMAKE_C_COMPILER=$(ConvertTo-CmPath $tool.Clang)",
    '-DCMAKE_C_COMPILER_TARGET=x86_64-w64-mingw32',
    "-DCMAKE_CXX_COMPILER=$(ConvertTo-CmPath $tool.ClangXX)",
    '-DCMAKE_CXX_COMPILER_TARGET=x86_64-w64-mingw32',
    "-DCMAKE_SYSROOT=$(ConvertTo-CmPath $tool.Sysroot)",
    "-DCMAKE_RC_COMPILER=$(ConvertTo-CmPath $tool.Windres)",
    '-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_SHARED_LIBS=OFF',
    # 只要核心与调试器，样例 / 测试 / Lua / 插件全关
    # （调试器不在这些开关里，它是 Source/Debugger 下的独立目标，一直在建）
    '-DRMLUI_SAMPLES=OFF',
    '-DBUILD_TESTING=OFF',
    '-DRMLUI_LUA_BINDINGS=OFF',
    '-DRMLUI_LOTTIE_PLUGIN=OFF',
    '-DRMLUI_SVG_PLUGIN=OFF',
    '-DRMLUI_HARFBUZZ_SAMPLE=OFF',
    # 字体引擎用 FreeType（默认值，显式写出来免得将来改默认值踩到）
    '-DRMLUI_FONT_ENGINE=freetype',
    # 预编译头：核心有 200+ 个 cpp，开了能省一多半编译时间
    '-DRMLUI_PRECOMPILED_HEADERS=ON',
    '-DRMLUI_WARNINGS_AS_ERRORS=OFF',
    # FreeType 装在非标准前缀下，直接喂给 FindFreetype.cmake 的缓存变量
    "-DFREETYPE_LIBRARY=$(ConvertTo-CmPath $ftLib)",
    "-DFREETYPE_INCLUDE_DIR_ft2build=$(ConvertTo-CmPath $ftInc)",
    "-DFREETYPE_INCLUDE_DIR_freetype2=$(ConvertTo-CmPath $ftInc)"
)
& $cmake @cfg
if ($LASTEXITCODE -ne 0) { Stop-WithError "cmake configure 失败：rmlui [$Target]" }

Write-Step "cmake build      rmlui $Version [$Target]"
& $cmake '--build' $buildDir '--target' 'rmlui_core' 'rmlui_debugger' '--parallel'
if ($LASTEXITCODE -ne 0) { Stop-WithError "cmake build 失败：rmlui [$Target]" }

# ---------------------------------------------------------------- 5. 收产物
New-Item -ItemType Directory -Force -Path $libOut | Out-Null
$produced = Get-ChildItem $buildDir -Filter 'librmlui*.a' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object Name
if (-not $produced) { Stop-WithError '未生成 librmlui*.a，检查上面的构建输出' }

foreach ($lib in $produced) {
    Copy-Item $lib.FullName (Join-Path $libOut $lib.Name) -Force
    $size = [math]::Round((Get-Item (Join-Path $libOut $lib.Name)).Length / 1KB)
    Write-Host "    -> $Target/$($lib.Name) (${size} KB)"
}

$hdrSrc = Join-Path $tree 'Include/RmlUi'
if (-not (Test-Path $hdrSrc)) { Stop-WithError "头文件目录不存在：$hdrSrc" }
if (Test-Path $incOut) { Remove-Item $incOut -Recurse -Force }
Copy-Item $hdrSrc $incOut -Recurse -Force
$hdrCount = (Get-ChildItem $incOut -Recurse -Include '*.h', '*.hpp', '*.inl').Count
Write-Host "    -> include/RmlUi（$hdrCount 个头文件）"

Write-Host ''
Write-Step '完成。'
