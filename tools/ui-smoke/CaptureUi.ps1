# CaptureUi.ps1 —— 抓 lolosia-backend 的 UI 窗口客户区（只用于本地冒烟，不参与产品代码）。
#
# 为什么用 CopyFromScreen 而不是 PrintWindow：窗口内容由 Vulkan 出帧，PrintWindow 抓出来是黑的。
# 代价是必须先置顶（SWP_NOSIZE|SWP_NOMOVE|SWP_NOACTIVATE 不变尺寸也不抢焦点）。
#
# 用法（cwd 在 backend 根目录）：
#   powershell -ExecutionPolicy Bypass -File tools/ui-smoke/CaptureUi.ps1 [-Out <png 路径>] [-ProcessName lolosia-backend]
#   ... -ResizeTo 1680x1500        # 先把窗口调到「客户区 WxH（物理像素）」，一次性看全整页
#
#   -ResizeTo 的存在理由：SDL 只处理有鼠标焦点的鼠标消息，注入滚轮去翻页不可靠，改成把窗口拉高再看。
#   默认输出到 build/ui-shot.png（build/ 已 gitignore，截图不落进仓库）。
[CmdletBinding()]
param(
    [string]$Out = '',
    [string]$ProcessName = 'lolosia-backend',
    [string]$ResizeTo = ''
)

$ErrorActionPreference = 'Stop'
# 默认值在这里取：param 默认值求值时 $PSScriptRoot 还没有值（PS 5.1）
if (-not $Out) { $Out = Join-Path $PSScriptRoot '../../build/ui-shot.png' }
Add-Type -AssemblyName System.Drawing

Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class WinCap
{
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);

    public struct RECT { public int Left, Top, Right, Bottom; }
    public struct POINT { public int X, Y; }

    // 窗口 z 序最前，但不改尺寸、不移动、不激活
    public static void Raise(IntPtr h)
    {
        SetWindowPos(h, (IntPtr)(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010);
    }

    // 把「客户区大小」换算成窗口大小（窗口边框那些非客户区的厚度保持当前值）
    public static void ResizeClient(IntPtr h, int clientW, int clientH, out int windowW, out int windowH)
    {
        RECT win, client;
        GetWindowRect(h, out win);
        GetClientRect(h, out client);
        int frameW = (win.Right - win.Left) - (client.Right - client.Left);
        int frameH = (win.Bottom - win.Top) - (client.Bottom - client.Top);
        windowW = clientW + frameW;
        windowH = clientH + frameH;
        // SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE：只改尺寸
        SetWindowPos(h, IntPtr.Zero, 0, 0, windowW, windowH, 0x0002 | 0x0004 | 0x0010);
    }
}
'@

[void][WinCap]::SetProcessDPIAware()

$proc = Get-Process -Name $ProcessName -ErrorAction Stop |
    Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } |
    Select-Object -First 1
if (-not $proc) { throw "没找到带窗口的 $ProcessName 进程" }

$hwnd = $proc.MainWindowHandle

if ($ResizeTo) {
    $parts = $ResizeTo -split 'x'
    $wantedW = [int]$parts[0]
    $wantedH = [int]$parts[1]
    $winW = 0
    $winH = 0
    [WinCap]::ResizeClient($hwnd, $wantedW, $wantedH, [ref]$winW, [ref]$winH)
    Write-Host "已把窗口调到客户区 ${wantedW}x${wantedH}（窗口 ${winW}x${winH}），等它重新布局"
    Start-Sleep -Milliseconds 1200
}

[void][WinCap]::Raise($hwnd)
Start-Sleep -Milliseconds 500

$rect = New-Object WinCap+RECT
if (-not [WinCap]::GetClientRect($hwnd, [ref]$rect)) { throw 'GetClientRect 失败' }
$origin = New-Object WinCap+POINT
if (-not [WinCap]::ClientToScreen($hwnd, [ref]$origin)) { throw 'ClientToScreen 失败' }

$w = $rect.Right - $rect.Left
$h = $rect.Bottom - $rect.Top
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($origin.X, $origin.Y, 0, 0, (New-Object System.Drawing.Size($w, $h)))
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

"客户区 ${w}x${h}（屏幕坐标 $($origin.X),$($origin.Y)）-> $Out"
