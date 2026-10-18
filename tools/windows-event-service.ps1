# 注册 / 移除 Windows 事件总线服务。
# install / uninstall / start / stop / status 需要管理员权限。
param(
    [ValidateSet('install', 'uninstall', 'start', 'stop', 'status')]
    [string]$Action = 'status',
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$Token = 'mock-token'
)

$ErrorActionPreference = 'Stop'
$serviceName = 'LolosiaEventBus'
$exe = (Resolve-Path (Join-Path $PSScriptRoot '..\modules\app-desktop\build\bin\mingwX64\debugExecutable\lolosia-backend.exe')).Path
$configDir = Join-Path $env:ProgramData 'Lolosia'
$configFile = Join-Path $configDir 'event-service.json'

function Assert-Admin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]$identity
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw '需要管理员权限：请在提升的 PowerShell 中运行本脚本。'
    }
}

function Invoke-Sc {
    param([string[]]$Arguments)
    & sc.exe @Arguments 2>$null | Out-Null
}

switch ($Action) {
    'install' {
        Assert-Admin
        New-Item -ItemType Directory -Force $configDir | Out-Null
        $json = @{ baseUrl = $BaseUrl; token = $Token; userName = 'service' } | ConvertTo-Json -Compress
        [IO.File]::WriteAllText($configFile, $json, [Text.UTF8Encoding]::new($false))

        Invoke-Sc @('stop', $serviceName)
        Invoke-Sc @('delete', $serviceName)
        Start-Sleep -Milliseconds 500
        $binPath = '"' + $exe + '" --event-service'
        Invoke-Sc @('create', $serviceName, 'binPath=', $binPath, 'start=', 'demand', 'DisplayName=', 'Lolosia Event Bus')
        Invoke-Sc @('description', $serviceName, 'Lolosia 客户端服务器事件订阅（SSE）')
        Invoke-Sc @('start', $serviceName)
        Start-Sleep -Seconds 1
        & sc.exe query $serviceName
        "配置：$configFile"
        "程序：$exe"
    }
    'uninstall' {
        Assert-Admin
        Invoke-Sc @('stop', $serviceName)
        Start-Sleep -Seconds 2
        Invoke-Sc @('delete', $serviceName)
        "已移除服务：$serviceName"
    }
    'start' {
        Assert-Admin
        & sc.exe start $serviceName
    }
    'stop' {
        Assert-Admin
        & sc.exe stop $serviceName
    }
    'status' {
        & sc.exe query $serviceName
        if (Test-Path $configFile) { Get-Content $configFile }
    }
}
