#Requires -Version 5.1
<#
.SYNOPSIS
    一次性构建 FreeType 静态库（build/thirdparty/out/<目标>/libfreetype.a + build/thirdparty/out/include/freetype2/**）。

.DESCRIPTION
    RmlUi 的字体引擎（RMLUI_FONT_ENGINE=freetype）依赖它，因此它必须在 rmlui/build.ps1 之前跑。
    与 sdl3/build.ps1 同一约定：只在新增或升级时手动跑一次，源码与产物都不入库（产物落 build/thirdparty/out/），
    日常 Gradle 构建只做 cinterop + 链接，不碰 C 工具链。

    依赖裁剪 —— 只留字形解析本身需要的东西：
      - bzip2 / PNG 内嵌位图 / HarfBuzz / Brotli 全部关掉（我们不解压压缩字体表、不读彩色位图字、
        HarfBuzz 只在阿拉伯、天城体这类复杂文种整形时起作用，中日韩用不到）；
      - zlib 用 FreeType 自带的内部实现（FT_DISABLE_ZLIB=ON），产物不引用任何外部压缩库，
        也就不会和 thirdparty 里那份 libz.a 抢符号。
    代价是失去 WOFF 压缩字体支持 —— 桌面 UI 直接吃 ttf / otf，够了。

    头文件按 FreeType 的惯例放在 include/freetype2 下（`#include <ft2build.h>` 需要该目录在 include path 上），
    与 include/SDL3 的布局差异来源就在这里。

.PARAMETER Target
    要构建的目标，当前只支持 mingwX64。

.PARAMETER Version
    FreeType 版本号，默认 2.15.0。

.PARAMETER WorkDir
    源码与 CMake 中间目录，默认 <repo>/build/thirdparty（已被 .gitignore 忽略）。

.PARAMETER Force
    清掉源码与中间产物，全部重来。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File thirdparty/freetype/build.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('mingwX64')]
    [string]$Target = 'mingwX64',
    [string]$Version = '2.15.0',
    [string]$WorkDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'gradle/toolchain.ps1')

$repoRoot = Get-LolosiaRepoRoot
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot 'build/thirdparty' }
$buildDir = Join-Path $WorkDir "cmake/$Target/freetype"
$stageDir = Join-Path $WorkDir "deps/$Target/freetype"
$libOut = Join-Path $WorkDir "out/$Target"
$incOut = Join-Path $WorkDir 'out/include/freetype2'

# ---------------------------------------------------------------- 1. 工具链
Write-Step '定位工具链'
$tool = Get-KonanToolchain $Target
Write-ToolchainSummary $tool
$cmake = Resolve-BuildTool 'cmake'
$ninja = Resolve-BuildTool 'ninja'
Write-Host "    cmake   : $cmake"
Write-Host "    ninja   : $ninja"

# ---------------------------------------------------------------- 2. 源码
$tag = 'VER-' + ($Version -replace '\.', '-')
$tree = Get-ThirdpartySource -Name "freetype-$tag" `
    -Library 'freetype' -Version $Version -FileName "freetype-$tag.tar.gz" `
    -Url "https://github.com/freetype/freetype/archive/refs/tags/$tag.tar.gz" `
    -WorkDir $WorkDir -Force:$Force

# ---------------------------------------------------------------- 2.5 打补丁
# FreeType 在 WIN32 下会编 src/base/ftver.rc（版本资源）。那是给 dll / exe 看的元数据，
# 静态库用不到；而 windres 处理带 #include 的 .rc 时要拉一个 gcc / cpp 做预处理，
# Kotlin/Native 那份 sysroot 里只有 GCC 的驱动壳（cc1 缺失），必然报 preprocessing failed。
# 因此连同 enable_language(RC) 一起去掉。补丁按精确文本替换，改版后失配会直接报错。
$patches = @(
    @{
        Note = 'CMakeLists.txt：不编版本资源 ftver.rc'
        File = 'CMakeLists.txt'
        Old  = @"
if (WIN32)
  enable_language(RC)
  list(APPEND BASE_SRCS builds/windows/ftdebug.c
                        src/base/ftver.rc)
"@
        New  = @"
if (WIN32)
  # 交叉编译去掉版本资源，理由见 thirdparty/freetype/build.ps1 的注释
  list(APPEND BASE_SRCS builds/windows/ftdebug.c)
"@
    }
)

foreach ($patch in $patches) {
    $file = Join-Path $tree $patch.File
    if (-not (Test-Path $file)) { Stop-WithError "补丁目标不存在：$file" }
    $text = [IO.File]::ReadAllText($file)
    if ($text.Contains($patch.Old)) {
        Write-Step "打补丁：$($patch.Note)"
        [IO.File]::WriteAllText($file, $text.Replace($patch.Old, $patch.New))
    } elseif (-not $text.Contains($patch.New)) {
        Stop-WithError "补丁无法应用（内容与预期不符）：$($patch.File)"
    } else {
        Write-Host "    已打过：$($patch.Note)"
    }
}

# ---------------------------------------------------------------- 3. CMake 交叉构建
if ($Force -or (Test-Path $buildDir)) { Remove-Item $buildDir -Recurse -Force -ErrorAction SilentlyContinue }

Write-Step "cmake configure  freetype $Version [$Target]"
$cfg = @(
    '-S', (ConvertTo-CmPath $tree), '-B', (ConvertTo-CmPath $buildDir), '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$(ConvertTo-CmPath $ninja)",
    '-DCMAKE_SYSTEM_NAME=Windows',
    '-DCMAKE_SYSTEM_PROCESSOR=x86_64',
    # 交叉编译不做 try_run，试编译产物直接当静态库，省掉链接器依赖
    '-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY',
    "-DCMAKE_C_COMPILER=$(ConvertTo-CmPath $tool.Clang)",
    '-DCMAKE_C_COMPILER_TARGET=x86_64-w64-mingw32',
    "-DCMAKE_SYSROOT=$(ConvertTo-CmPath $tool.Sysroot)",
    "-DCMAKE_RC_COMPILER=$(ConvertTo-CmPath $tool.Windres)",
    '-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_SHARED_LIBS=OFF',
    # 依赖裁剪，理由见文件头
    '-DFT_DISABLE_ZLIB=ON',
    '-DFT_DISABLE_BZIP2=ON',
    '-DFT_DISABLE_PNG=ON',
    '-DFT_DISABLE_HARFBUZZ=ON',
    '-DFT_DISABLE_BROTLI=ON',
    '-DFT_DISABLE_HVF=ON',
    "-DCMAKE_INSTALL_PREFIX=$(ConvertTo-CmPath $stageDir)"
)
& $cmake @cfg
if ($LASTEXITCODE -ne 0) { Stop-WithError "cmake configure 失败：freetype [$Target]" }

Write-Step "cmake build      freetype $Version [$Target]"
& $cmake '--build' $buildDir '--parallel'
if ($LASTEXITCODE -ne 0) { Stop-WithError "cmake build 失败：freetype [$Target]" }

# ---------------------------------------------------------------- 4. 收产物
# 走 CMake 自己的 install 规则（头文件分两处放、配置头是构建期生成的，自己拼容易漏），
# 装到 build/ 下的暂存前缀后再拣需要的搬进 build/thirdparty/out。
Write-Step '安装到暂存前缀并收集产物'
if (Test-Path $stageDir) { Remove-Item $stageDir -Recurse -Force }
& $cmake '--install' $buildDir '--prefix' (ConvertTo-CmPath $stageDir)
if ($LASTEXITCODE -ne 0) { Stop-WithError "cmake install 失败：freetype [$Target]" }

$staticLib = Get-ChildItem $stageDir -Filter 'libfreetype*.a' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object Length -Descending | Select-Object -First 1
if (-not $staticLib) { Stop-WithError '未生成 libfreetype.a，检查上面的构建输出' }

New-Item -ItemType Directory -Force -Path $libOut | Out-Null
Copy-Item $staticLib.FullName (Join-Path $libOut 'libfreetype.a') -Force
Write-Host "    -> $Target/libfreetype.a ($([math]::Round((Get-Item (Join-Path $libOut 'libfreetype.a')).Length / 1KB)) KB)"

$hdrSrc = Join-Path $stageDir 'include/freetype2'
if (-not (Test-Path $hdrSrc)) { Stop-WithError "暂存前缀里没有 include/freetype2：$stageDir" }
if (Test-Path $incOut) { Remove-Item $incOut -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path $incOut) | Out-Null
Copy-Item $hdrSrc $incOut -Recurse -Force
$hdrCount = (Get-ChildItem $incOut -Recurse -Filter '*.h').Count
Write-Host "    -> include/freetype2（$hdrCount 个头文件）"

Write-Host ''
Write-Step '完成。'
