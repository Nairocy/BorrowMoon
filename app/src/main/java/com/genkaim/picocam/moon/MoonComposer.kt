package com.genkaim.picocam.moon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.genkaim.picocam.R
import com.genkaim.picocam.camera.PhotoStorage
import java.io.File

/**
 * 拍照后的离线合成：把月亮烧进照片。
 *
 * 顺序：EXIF 方向纠正 → 画月亮（SCREEN 混合）→ 写回文件 → 存系统相册。
 * 与预览保持同一套参数（alpha 0.85 / 直径 = 2.2r / SCREEN），保证所见即所得。
 */
object MoonComposer {

    private const val ALPHA = 0.88f

    /** 月亮直径 = 检测半径 × SIZE_FACTOR（真实月亮只有 8px，必须夸张）。 */
    const val SIZE_FACTOR = 2.8f

    @Volatile
    private var cached: Bitmap? = null

    /** 读月亮贴图（单例缓存）。调用方不要 recycle 它。 */
    fun texture(context: Context): Bitmap {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.moon_full)
                ?: throw IllegalStateException("moon texture missing")
            cached = bmp
            return bmp
        }
    }

    /** 阻塞调用，请在 IO 线程执行。 */
    fun compose(context: Context, file: File, moons: List<Moon>) {
        val bmp = decodeUpright(file)
        if (bmp == null) {
            Log.w("MoonComposer", "decode failed: ${file.name}")
            return
        }
        Log.w("MoonComposer", "compose moons=${moons.size} bmp=${bmp.width}x${bmp.height} file=${file.name}")
        try {
            if (moons.isNotEmpty()) {
                val tex = texture(context)
                val canvas = Canvas(bmp)
                val dst = RectF()
                val glow = Paint(Paint.ANTI_ALIAS_FLAG)
                val moonPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                moonPaint.alpha = (ALPHA * 255f).toInt()
                for (m in moons) {
                    val cx = m.x * bmp.width
                    val cy = m.y * bmp.height
                    val rad = m.r * bmp.width * (SIZE_FACTOR / 2f)
                    // 月晕：与预览层同一套参数
                    glow.shader = android.graphics.RadialGradient(
                        cx, cy, rad * 2.0f,
                        intArrayOf(0x59FFFFFF.toInt(), 0x26FFF3D6, 0x00FFFFFF),
                        floatArrayOf(0f, 0.55f, 1f),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                    canvas.drawCircle(cx, cy, rad * 2.0f, glow)
                    dst.set(cx - rad, cy - rad, cx + rad, cy + rad)
                    canvas.drawBitmap(tex, null, dst, moonPaint)
                }
                glow.shader = null
            }
            PhotoStorage.overwriteFileWithBitmap(file, bmp, null)
            PhotoStorage.saveToGalleryReturnUri(context, file)
        } finally {
            bmp.recycle()
        }
    }

    private fun decodeUpright(file: File): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        val src = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val ori = runCatching {
            ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val deg = when (ori) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)
    }.getOrNull()
}
