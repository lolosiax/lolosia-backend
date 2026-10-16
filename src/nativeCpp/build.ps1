#Requires -Version 5.1
<#
.SYNOPSIS
    编译自研的 C/C++ 门面层为 thirdparty/<目标>/liblolosia.a。

.DESCRIPTION
    src/nativeCpp 下的 C++ 代码只做一件事：把 SDL3 / 图形栈包成 ll_ui_* 这套 C ABI，
    供 Kotlin 侧用 cinterop 调用。产物与第三方静态库放在一起（thirdparty/<目标>/），
    这样 cinterop 只需要一个 libraryPath，链接期 SDL3 等依赖也由同一目录解析。

    编译器与 build-libs.ps1 / build-sdl3.ps1 一致，都是 Kotlin/Native 自带的那套
    （clang + msys2 sysroot），保证 ABI 与最终可执行文件完全对齐。

    依赖：thirdparty/include/SDL3/**（先跑 build-sdl3.ps1）、thirdparty/<目标>/libSDL3.a。

    本层用 -fno-exceptions -fno-rtti 编译，不产生 libstdc++ 符号；
    将来引入 RmlUi / FreeType 这类需要 C++ 运行时的库时，在 $runtimeLibs 里补链接参数。

.PARAMETER Target
    要构建的目标，当前只支持 mingwX64。

.PARAMETER WorkDir
    编译中间目录，默认 <repo>/build/nativeCpp（已被 .gitignore 忽略）。

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

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$thirdpartyDir = Join-Path $repoRoot 'thirdparty'
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot "build/nativeCpp/$Target" }
$objDir = Join-Path $WorkDir 'obj'
$libOut = Join-Path $thirdpartyDir $Target

function Info([string]$m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Fail([string]$m) { throw $m }

# ---------------------------------------------------------------- 目标参数
$targetDefs = @{
    mingwX64 = @{
        Triple = 'x86_64-w64-mingw32'
        SysrootFilter = 'msys2-mingw-w64-x86_64-*'
        SysrootSub = ''
    }
}
$def = $targetDefs[$Target]

# ---------------------------------------------------------------- 1. 工具链
Info '定位 Kotlin/Native 自带交叉工具链'

$deps = Join-Path $env:USERPROFILE '.konan/dependencies'
if (-not (Test-Path $deps)) { Fail "找不到 $deps，请先执行一次 Gradle 构建以下载 Kotlin/Native 依赖" }

$llvmHome = Get-ChildItem $deps -Directory -Filter 'llvm-*-windows-essentials-*' |
    Sort-Object Name -Descending | Select-Object -First 1
$sysHome = Get-ChildItem $deps -Directory -Filter $def.SysrootFilter | Select-Object -First 1
if (-not $llvmHome -or -not $sysHome) { Fail 'Kotlin/Native 依赖不完整（缺 llvm 或 sysroot），请先执行一次 Gradle 构建' }

$clang = Join-Path $llvmHome.FullName 'bin/clang.exe'
$llvmAr = Join-Path $llvmHome.FullName 'bin/llvm-ar.exe'
$sysroot = if ($def.SysrootSub) { Join-Path $sysHome.FullName $def.SysrootSub } else { $sysHome.FullName }
foreach ($p in @($clang, $llvmAr, $sysroot)) {
    if (-not (Test-Path $p)) { Fail "缺文件：$p" }
}
Write-Host "    clang   : $clang"
Write-Host "    sysroot : $sysroot"

# ---------------------------------------------------------------- 2. 依赖自检
$sdlInc = Join-Path $thirdpartyDir 'include/SDL3/SDL.h'
$sdlLib = Join-Path $libOut 'libSDL3.a'
if (-not (Test-Path $sdlInc)) { Fail "缺少 SDL3 头文件：$sdlInc（先跑 thirdparty/build-sdl3.ps1）" }
if (-not (Test-Path $sdlLib)) { Fail "缺少 SDL3 静态库：$sdlLib（先跑 thirdparty/build-sdl3.ps1）" }

# ---------------------------------------------------------------- 3. 编译
if ($Force -and (Test-Path $WorkDir)) { Remove-Item $WorkDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $objDir, $libOut | Out-Null

$sources = Get-ChildItem (Join-Path $PSScriptRoot 'src') -Filter '*.cpp' | Sort-Object Name
if (-not $sources) { Fail "src/nativeCpp/src 下没有 .cpp 源文件" }

Info "clang 编译        liblolosia [$Target]"
$objects = @()
foreach ($src in $sources) {
    $obj = Join-Path $objDir (($src.BaseName) + '.o')
    & $clang "--target=$($def.Triple)" "--sysroot=$sysroot" `
        -std=c++17 -O2 -Wall -Wextra `
        -fno-exceptions -fno-rtti `
        -I (Join-Path $thirdpartyDir 'include') `
        -I (Join-Path $PSScriptRoot 'include') `
        -c $src.FullName -o $obj
    if ($LASTEXITCODE -ne 0) { Fail "编译失败：$($src.Name) [$Target]" }
    $objects += $obj
    Write-Host "    cc $($src.Name)"
}

# ---------------------------------------------------------------- 4. 归档
$lib = Join-Path $libOut 'liblolosia.a'
if (Test-Path $lib) { Remove-Item $lib -Force }
& $llvmAr rcs $lib @objects
if ($LASTEXITCODE -ne 0) { Fail "归档失败：liblolosia.a [$Target]" }
Write-Host "    -> $Target/liblolosia.a ($([math]::Round((Get-Item $lib).Length / 1KB)) KB)"

Write-Host ''
Info '完成。'
