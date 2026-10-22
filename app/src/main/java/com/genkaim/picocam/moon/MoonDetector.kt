package com.genkaim.picocam.moon

import androidx.camera.core.ImageProxy
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 圆形发光物检测器（纯 Kotlin，无第三方依赖）。
 *
 * 思路：夜景里发光体是过曝团，边缘被光晕污染，霍夫圆提不出闭合轮廓。
 * 改用「亮度连通域 + 形状统计 + 径向衰减」：
 *
 *   Y 平面降采样 → 3x3 模糊 → 直方图 P99.2 自适应阈值 → 开运算
 *   → 连通域 → 面积/宽高比/填充率/各向同性过滤 → 中心 vs 环带亮度比 → 输出
 *
 * 第 5 条（径向衰减）是区分"真在发光"与"白墙上的圆洞"的关键。
 */
object MoonDetector {

    /** 最近一帧的诊断信息，供调试日志读取。 */
    @Volatile
    var lastDebug: String = ""
        private set

    /** 分析帧目标宽度，越小越快。320 在夜景下对 ≥6px 的亮斑有效。 */
    private const val TARGET_W = 320

    private const val MIN_AREA = 9
    /**
     * 单个亮斑面积上限（占分析画面）。超过视为整片过曝（天空/白墙），丢弃。
     * 放大到 3x 时路灯能占到画面 10%+，贴脸拍甚至 40%，所以放到 45%。
     */
    private const val MAX_AREA_FRAC = 0.45f
    /** 最多同时输出几个目标。 */
    private const val MAX_TARGETS = 4

    fun detect(image: ImageProxy): List<Moon> {
        val srcW = image.width
        val srcH = image.height
        if (srcW < 64 || srcH < 64) return emptyList()

        val step = max(1, srcW / TARGET_W)
        val w = srcW / step
        val h = srcH / step
        if (w < 32 || h < 32) return emptyList()

        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride

        // —— 1. 读 Y 平面（rowStride 常大于 width，必须绝对定位）——
        val raw = IntArray(w * h)
        var n = 0
        var y = 0
        while (y < h) {
            var idx = (y * step) * rowStride
            var x = 0
            while (x < w) {
                raw[n++] = buf.get(idx).toInt() and 0xFF
                idx += step * pixStride
                x++
            }
            y++
        }

        // —— 2. 模糊 ——
        val gray = boxBlur(raw, w, h)
        val total = w * h

        // —— 3. 自适应阈值 ——
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val p50 = percentile(hist, total, 0.50f)
        val p992 = percentile(hist, total, 0.992f)
        // 画面太平 = 白天 / 无光源，直接放弃
        if (p992 - p50 < 25) return emptyList()
        // 白天户外中位亮度很高，这时全是误检；夜景/室内灯场景 p50 一般 < 110
        if (p50 > 135) return emptyList()

        // 固定相对阈值，不用百分位：
        // P99.2 会被「面积占比极小的远处路灯」顶到接近峰值，导致只抓到灯芯几个像素。
        // 夜景背景通常 < 30，所以「比中位数亮 55 且不低于 150」能稳定圈住整个亮斑。
        val thr = max(p50 + 55, 150)

        val bin = BooleanArray(total)
        for (i in 0 until total) if (gray[i] >= thr) bin[i] = true
        // 不做开运算：3x3 开运算会把 5~10px 的小路灯削成方块（fill 冲到 0.95 后被误杀），
        // 孤立噪点交给下面的面积阈值（MIN_AREA）处理。

        // —— 5. 连通域（8 邻域）——
        val labels = IntArray(total) { -1 }
        val stack = IntArray(total)
        val comps = ArrayList<Comp>()
        val maxArea = (total * MAX_AREA_FRAC).toInt().coerceAtLeast(MIN_AREA)

        var p = 0
        while (p < total) {
            if (!bin[p] || labels[p] >= 0) { p++; continue }
            val id = comps.size
            var sp = 0
            stack[sp++] = p
            labels[p] = id

            var area = 0
            var sumX = 0L
            var sumY = 0L
            var sumXX = 0L
            var sumYY = 0L
            var sumXY = 0L
            var sumL = 0L
            var minX = w; var maxX = 0; var minY = h; var maxY = 0

            while (sp > 0) {
                val q = stack[--sp]
                val qx = q % w
                val qy = q / w
                area++
                sumX += qx; sumY += qy
                sumXX += qx.toLong() * qx
                sumYY += qy.toLong() * qy
                sumXY += qx.toLong() * qy
                sumL += gray[q]
                if (qx < minX) minX = qx
                if (qx > maxX) maxX = qx
                if (qy < minY) minY = qy
                if (qy > maxY) maxY = qy

                for (dy in -1..1) {
                    val ny = qy + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = qx + dx
                        if (nx < 0 || nx >= w) continue
                        val ni = ny * w + nx
                        if (bin[ni] && labels[ni] < 0) {
                            labels[ni] = id
                            stack[sp++] = ni
                        }
                    }
                }
            }

            if (area < MIN_AREA || area > maxArea) continue
            comps.add(Comp(id, area, sumX, sumY, sumXX, sumYY, sumXY, sumL, minX, maxX, minY, maxY))
        }

        lastDebug = "p50=$p50 p992=$p992 thr=$thr comps=${comps.size}"
        if (comps.isEmpty()) return emptyList()

        // —— 6. 形状过滤 ——
        val out = ArrayList<Moon>(4)
        for (c in comps) {
            val bw = (c.maxX - c.minX + 1).toFloat()
            val bh = (c.maxY - c.minY + 1).toFloat()
            if (bw <= 0f || bh <= 0f) continue

            // 目标被画面边界裁掉时（放大到超出屏幕），形状统计全都不成立：
            // 只用亮度 + 边界内的可见高度估半径，否则「一放大月亮就消失」。
            val touchesBorder = c.minX <= 1 || c.maxX >= w - 2 || c.minY <= 1 || c.maxY >= h - 2

            val cx = c.sumX.toFloat() / c.area
            val cy = c.sumY.toFloat() / c.area

            if (!touchesBorder) {
                val aspect = bw / bh
                if (aspect < 0.55f || aspect > 1.8f) continue

                val fill = c.area / (bw * bh)
                // 圆在方框内的理想填充率 = π/4 ≈ 0.785；>0.93 更可能是方形亮块
                if (fill < 0.52f || fill > 0.93f) continue

                // 二阶矩各向同性
                val mx = c.sumXX.toFloat() / c.area - cx * cx
                val my = c.sumYY.toFloat() / c.area - cy * cy
                val mxy = c.sumXY.toFloat() / c.area - cx * cy
                val tr = mx + my
                val det = mx * my - mxy * mxy
                val disc = sqrt(max(0f, tr * tr / 4f - det))
                val l1 = tr / 2f + disc
                val l2 = tr / 2f - disc
                val iso = l1 / max(l2, 0.0001f)
                if (iso > 2.2f) continue
            }

            val rEq = if (touchesBorder) {
                min(bw, bh) / 2f
            } else {
                sqrt(c.area / PI).toFloat()
            }
            if (rEq < 2.5f) continue
            if (rEq > min(w, h) * 0.55f) continue

            // —— 7. 径向衰减：用「圆内平均」而不是「中心 3x3」——
            // 很多灯是环形/带暗心（LED 灯圈、吸顶灯），只看中心点会被误杀；
            // 圆内整体比 1.45r 环带明显亮就够了。
            val innerMean = meanInCircle(gray, w, h, cx, cy, rEq * 0.85f)
            val ringMean = ringMean(gray, w, h, cx, cy, rEq * 1.45f)
            if (innerMean < 100f) continue
            if (innerMean < ringMean * 1.12f) continue

            out.add(
                Moon(
                    x = cx / w,
                    y = cy / h,
                    r = rEq / w,
                    luma = (c.sumL.toFloat() / c.area / 255f).coerceIn(0f, 1f),
                )
            )
        }

        out.sortByDescending { it.r }
        return if (out.size > MAX_TARGETS) out.subList(0, MAX_TARGETS) else out
    }

    // ---------------------------------------------------------------- helpers

    private class Comp(
        val id: Int,
        val area: Int,
        val sumX: Long, val sumY: Long,
        val sumXX: Long, val sumYY: Long, val sumXY: Long,
        val sumL: Long,
        val minX: Int, val maxX: Int, val minY: Int, val maxY: Int,
    )

    private fun percentile(hist: IntArray, total: Int, p: Float): Int {
        val target = (total * p).toInt().coerceAtLeast(1)
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return 255
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0
                var c = 0
                for (k in -1..1) {
                    val xx = x + k
                    if (xx in 0 until w) { s += src[row + xx]; c++ }
                }
                tmp[row + x] = s / c
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                var c = 0
                for (k in -1..1) {
                    val yy = y + k
                    if (yy in 0 until h) { s += tmp[yy * w + x]; c++ }
                }
                out[y * w + x] = s / c
            }
        }
        return out
    }

    private fun open3x3(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val eroded = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (!src[i]) continue
                var all = true
                var dy = -1
                while (dy <= 1 && all) {
                    var dx = -1
                    while (dx <= 1) {
                        if (!src[(y + dy) * w + (x + dx)]) { all = false; break }
                        dx++
                    }
                    dy++
                }
                if (all) eroded[i] = true
            }
        }
        val out = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (!eroded[i]) continue
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        out[(y + dy) * w + (x + dx)] = true
                    }
                }
            }
        }
        return out
    }

    /** 圆内平均亮度（采样半径内的所有像素）。 */
    private fun meanInCircle(gray: IntArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float): Float {
        val x0 = max(0, (cx - radius).toInt())
        val x1 = min(w - 1, (cx + radius).toInt())
        val y0 = max(0, (cy - radius).toInt())
        val y1 = min(h - 1, (cy + radius).toInt())
        val r2 = radius * radius
        var s = 0
        var c = 0
        for (y in y0..y1) {
            val dy = y - cy
            val base = y * w
            for (x in x0..x1) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) {
                    s += gray[base + x]
                    c++
                }
            }
        }
        return if (c == 0) 0f else s.toFloat() / c
    }

    /** 在半径 [radius] 的圆周上取 16 个点求均值（环带亮度）。 */
    private fun ringMean(gray: IntArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float): Float {
        var s = 0
        var c = 0
        for (k in 0 until 16) {
            val a = (k / 16.0) * 2.0 * PI
            val x = (cx + (radius * kotlin.math.cos(a))).toInt()
            val y = (cy + (radius * kotlin.math.sin(a))).toInt()
            if (x < 0 || x >= w || y < 0 || y >= h) continue
            s += gray[y * w + x]
            c++
        }
        return if (c == 0) 0f else s.toFloat() / c
    }
}
