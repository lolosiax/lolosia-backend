#Requires -Version 5.1
<#
.SYNOPSIS
    准备 RmlUi 源码树，供 nativeCpp/build.ps1 编译 RmlUi 的 SDL_GPU 后端胶水。

.DESCRIPTION
    优先从自建工件仓库的 sources/ 拉取源码包；没有配置仓库（或镜像取不到）时回退到 GitHub。
    仓库地址来自 Gradle 传入的环境变量，或主工程 local.properties —— 这里不写死任何域名。
    已存在 build/thirdparty/tree/RmlUi-<版本>/Backends 时直接跳过。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File modules/client/src/nativeCpp/fetch-rmlui.ps1
#>
[CmdletBinding()]
param(
    [string]$Version = '6.3'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../../../../thirdparty/gradle/toolchain.ps1')

$repoRoot = Get-LolosiaRepoRoot
$workDir = Join-Path $repoRoot 'build/thirdparty'
$treeDir = Join-Path $workDir "tree/RmlUi-$Version"
if (Test-Path (Join-Path $treeDir 'Backends')) {
    Write-Host "RmlUi 源码已存在：$treeDir"
    exit 0
}

$tree = Get-ThirdpartySource -Name "RmlUi-$Version" `
    -Library 'rmlui' -Version $Version -FileName "RmlUi-$Version.tar.gz" `
    -Url "https://github.com/mikke89/RmlUi/archive/refs/tags/$Version.tar.gz" `
    -WorkDir $workDir

if (-not (Test-Path (Join-Path $tree 'Backends'))) {
    throw "解压后缺少 RmlUi Backends：$tree"
}
Write-Host "RmlUi 源码就绪：$tree"
