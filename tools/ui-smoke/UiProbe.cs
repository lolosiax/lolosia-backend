// UiProbe.cs —— UI 实验的自动化冒烟帮手（入选的冒烟工具，不参与产品代码；配套 CaptureUi.ps1）。
//
// 用途：在客户区截图里按按钮填充色找出按钮位置，并按客户区坐标把点击直投给窗口。
// 为什么点击走 PostMessage 而不是 SetCursorPos + mouse_event：后者会真的挪动用户的光标、
// 在有 DPI 缩放时坐标还会被虚拟化，用户同时在操作电脑时更会打偏。
//
// ⚠️ 实际效果（2026-10 实测，别指望它能点）：SDL3 只在窗口有**鼠标焦点**时才处理鼠标消息，
//    而 PostMessage 不改焦点，所以注入的点击通常被 SDL 丢掉 —— 交互验证还是得人手动点。
//    目前真正好用的是 LocateButtons / ColumnStats 这两个「从截图里读位置」的函数
//    （比如核对某个控件的填充色、量它的盒模型尺寸），配合 CaptureUi.ps1 一起用。
//
// 注意：Windows PowerShell 5.1 的 Add-Type 只支持到 C# 5，
// 因此这里不能用表达式体成员（=>）、字符串插值（$""）、自动属性初始化器。
//
// 用法（PowerShell 里先 Add-Type 再调，cwd 在 backend 根目录；少了 -ReferencedAssemblies 会报
//      「命名空间 System.Drawing 中不存在 Imaging」）：
//   Add-Type -Path tools/ui-smoke/UiProbe.cs -ReferencedAssemblies System.Drawing
//   [UiProbe]::LocateButtons($pngPath)                找出按钮（客户区坐标）
//   [UiProbe]::ColumnStats($pngPath, $x)              诊断：某一列的命中情况
//   [UiProbe]::ClickCenter($hwnd, $box, $times)       按客户区坐标点击（见上面的限制）
//
// 前提：截图必须是「客户区」且调用方已 SetProcessDPIAware —— 这样截图坐标 == PostMessage 坐标。
// 客户区里可用的填充色常量见下面 IsButtonFill（当前对应 probe.rml 里 button 的 background-color）。
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Threading;

public static class UiProbe
{
    [DllImport("user32.dll")]
    static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);

    const uint WM_MOUSEMOVE = 0x0200;
    const uint WM_LBUTTONDOWN = 0x0201;
    const uint WM_LBUTTONUP = 0x0202;
    const int MK_LBUTTON = 0x0001;

    /// <summary>按钮的客户区矩形（以填充区域为准）。</summary>
    public struct Box
    {
        public int X0;
        public int Y0;
        public int X1;
        public int Y1;

        public int CenterX { get { return (X0 + X1) / 2; } }
        public int CenterY { get { return (Y0 + Y1) / 2; } }

        public override string ToString()
        {
            return string.Format("x={0}..{1} y={2}..{3} 中心=({4},{5}) 宽={6} 高={7}",
                X0, X1, Y0, Y1, CenterX, CenterY, X1 - X0 + 1, Y1 - Y0 + 1);
        }
    }

    /// <summary>把整幅位图一次性读成 BGRA 字节数组：逐像素 GetPixel 是跨界调用，几百万次要跑几十秒。</summary>
    class Pixels
    {
        public readonly int Width;
        public readonly int Height;
        readonly byte[] buf;
        readonly int stride;

        public Pixels(string path)
        {
            using (Bitmap bmp = new Bitmap(path))
            {
                Width = bmp.Width;
                Height = bmp.Height;
                BitmapData data = bmp.LockBits(new Rectangle(0, 0, Width, Height),
                    ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                stride = data.Stride;
                buf = new byte[stride * Height];
                Marshal.Copy(data.Scan0, buf, 0, buf.Length);
                bmp.UnlockBits(data);
            }
        }

        /// <summary>按钮填充色 #26303f（RCSS 里 .toolbar 内 button 的 background-color）。</summary>
        public bool IsButtonFill(int x, int y)
        {
            int o = y * stride + x * 4;
            int b = buf[o];
            int g = buf[o + 1];
            int r = buf[o + 2];
            return Math.Abs(r - 38) <= 7 && Math.Abs(g - 48) <= 7 && Math.Abs(b - 63) <= 7;
        }

        public string Describe(int x, int y)
        {
            int o = y * stride + x * 4;
            return string.Format("({0},{1},{2})", buf[o + 2], buf[o + 1], buf[o]);
        }
    }

    /// <summary>
    /// 找出所有按钮。两步：
    ///   1) 逐列找竖直方向够长的填充段（按钮高约 29px），用上下边界的中位数定出「按钮带」——
    ///      这样能排除只有几像素高的分隔线/边框形成的假带；
    ///   2) 带内逐列统计填充像素**数量**（而不是找连续段）：按钮上的文字笔画会把连续段打断，
    ///      「归零」这类中文按钮用连续段判定会漏检，用计数则只要求该列有若干填充像素。
    /// </summary>
    public static Box[] LocateButtons(string bitmapPath)
    {
        return LocateButtons(bitmapPath, 40, 20);
    }

    public static Box[] LocateButtons(string bitmapPath, int minWidth, int minHeight)
    {
        Pixels px = new Pixels(bitmapPath);
        int w = px.Width;
        int h = px.Height;

        List<int> tops = new List<int>();
        List<int> bottoms = new List<int>();
        for (int x = 0; x < w; x += 2)
        {
            int start = -1;
            for (int y = 0; y <= h; y++)
            {
                bool hit = y < h && px.IsButtonFill(x, y);
                if (hit && start < 0)
                {
                    start = y;
                }
                else if (!hit && start >= 0)
                {
                    if (y - start >= minHeight) { tops.Add(start); bottoms.Add(y - 1); }
                    start = -1;
                }
            }
        }
        if (tops.Count == 0) return new Box[0];

        tops.Sort();
        bottoms.Sort();
        int bandTop = tops[tops.Count / 2];
        int bandBottom = bottoms[bottoms.Count / 2];

        int minPixels = 3;
        List<Box> boxes = new List<Box>();
        int runStart = -1;
        for (int x = 0; x <= w; x++)
        {
            bool inside = false;
            if (x < w)
            {
                int n = 0;
                for (int y = bandTop; y <= bandBottom; y++)
                {
                    if (px.IsButtonFill(x, y)) n++;
                }
                inside = n >= minPixels;
            }
            if (inside && runStart < 0)
            {
                runStart = x;
            }
            else if (!inside && runStart >= 0)
            {
                if (x - runStart >= minWidth)
                {
                    Box b = new Box();
                    b.X0 = runStart;
                    b.Y0 = bandTop;
                    b.X1 = x - 1;
                    b.Y1 = bandBottom;
                    boxes.Add(b);
                }
                runStart = -1;
            }
        }
        return boxes.ToArray();
    }

    /// <summary>诊断用：看某一列上按钮填充色的命中情况，以及几个采样点的实际颜色。</summary>
    public static string ColumnStats(string bitmapPath, int x)
    {
        Pixels px = new Pixels(bitmapPath);
        int count = 0;
        int minY = -1;
        int maxY = -1;
        for (int y = 0; y < px.Height; y++)
        {
            if (px.IsButtonFill(x, y))
            {
                count++;
                if (minY < 0) minY = y;
                maxY = y;
            }
        }
        return string.Format("x={0} 命中={1} y范围={2}..{3} 图像={4}x{5} 中点颜色={6}",
            x, count, minY, maxY, px.Width, px.Height, px.Describe(x, px.Height / 2));
    }

    /// <summary>按客户区坐标发一次左键点击（move → down → up）。</summary>
    public static void ClickClient(IntPtr hwnd, int x, int y)
    {
        IntPtr lp = (IntPtr)((y << 16) | (x & 0xFFFF));
        PostMessage(hwnd, WM_MOUSEMOVE, IntPtr.Zero, lp);
        Thread.Sleep(40);
        PostMessage(hwnd, WM_LBUTTONDOWN, (IntPtr)MK_LBUTTON, lp);
        Thread.Sleep(60);
        PostMessage(hwnd, WM_LBUTTONUP, IntPtr.Zero, lp);
        Thread.Sleep(120);
    }

    public static void ClickCenter(IntPtr hwnd, Box b, int times)
    {
        for (int i = 0; i < times; i++) ClickClient(hwnd, b.CenterX, b.CenterY);
    }
}
