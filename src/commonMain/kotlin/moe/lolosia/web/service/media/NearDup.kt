package moe.lolosia.web.service.media

/**
 * 近重复分组用的感知哈希（`doc/vision-autotag-design.md` §3.2 杠杆①、§4 去重联动）。
 *
 * 哈希直接从 `media_asset.sample_rgb565`（缩略图生成时顺带产出的 **8×8** RGB565 采样，
 * 256 个十六进制字符）算出，**零解码、零读取**：连拍近似图在这种低分辨率下几乎一致，
 * 足够把「同一姿势的十几张」收敛成一张代表图外发，其余继承标签。
 *
 * 精度受限于 8×8（只有 56 位），只用于**明显近似**的合并；拿不准的一律照常外发（宁多花几分钱，
 * 不漏内容）。
 *
 * @author 洛洛希雅Lolosia
 */
object NearDup {

    private const val PIXELS: Int = 64
    private const val ROW: Int = 8

    /**
     * 从 8×8 采样算 56 位感知哈希；采样缺失 / 长度不足 / 解不出返回 `null`（调用方据此跳过去重）。
     *
     * 做法是 dHash 的变体：每行比较相邻像素的灰度，左亮于右记 1。
     */
    fun hash(sample: String?): Long? {
        if (sample == null || sample.length < PIXELS * 4) return null
        val luma = IntArray(PIXELS)
        for (i in 0 until PIXELS) {
            val px = sample.substring(i * 4, i * 4 + 4).toIntOrNull(16) ?: return null
            // RGB565 → 近似灰度（红 299 / 绿 587 / 蓝 114，权重足够区分明暗）
            val r = (px shr 11) and 0x1F
            val g = (px shr 5) and 0x3F
            val b = px and 0x1F
            luma[i] = (r shl 3) * 299 + (g shl 2) * 587 + (b shl 3) * 114
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until ROW) {
            for (x in 0 until ROW - 1) {
                if (luma[y * ROW + x] > luma[y * ROW + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** 两个哈希的汉明距离。 */
    fun distance(a: Long, b: Long): Int = (a xor b).countOneBits()
}
