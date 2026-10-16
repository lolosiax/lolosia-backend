package moe.lolosia.web.ui.geometry

/**
 * 逻辑尺寸（像素）。
 *
 * 布局与控件只用它表示宽高，不区分设备像素比 —— 那是渲染层换算的事。
 *
 * @author 洛洛希雅Lolosia
 */
data class UiSize(val width: Int, val height: Int) {
    companion object {
        val Zero = UiSize(0, 0)
    }
}
