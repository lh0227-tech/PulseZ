package cc.pulsez.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** 图片处理失败的可读错误码（错误不吞，UI 按码换人话）。*/
object ImageErrorCodes {
    /** 解码/编码失败或不是图片（损坏文件、加密图、不支持的格式）。 */
    const val BAD_IMAGE = "BAD_IMAGE"

    /** 解码/缩放时内存不够（超大图）。 */
    const val OOM = "OOM"

    /** 读文件/写临时文件等 IO 失败。 */
    const val IO = "IO"

    /** 输入超过单图上限（10MB）。 */
    const val TOO_LARGE = "TOO_LARGE"
}

/** 图片处理异常：带 [ImageErrorCodes] 稳定码，UI 层按码换人话（不把异常文本给用户）。 */
class ImageProcessingException(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

/**
 * 手写压缩流水线（v0.5.0 方案 A+）。
 *
 * 顺序（每一步都为了一个具体问题）：
 * 1. `inJustDecodeBounds` 先读尺寸 → 算 `inSampleSize`（先解全图会 OOM）；
 * 2. 按采样解码（`ARGB_8888`：要能画透明通道，铺白时才有原色）；
 * 3. **EXIF 方向转正**（相机竖拍/相册旋转；平台 `BitmapFactory` 不自动应用方向，
 *    官方推荐用 AndroidX ExifInterface，见 `）；
 * 4. 等比缩到长边 ≤ [ImageMath.MAX_EDGE]（`createScaledBitmap`）；
 * 5. 透明底铺白（透明 PNG 直接转 JPEG 会变黑底）；
 * 6. `compress(JPEG, 80)` → 字节 + 宽高。
 *
 * 线程：同步阻塞；调用方必须在 `Dispatchers.IO`（`ImagePipeline` 已统一包住）。
 * 失败抛 [ImageProcessingException]，**不打印路径/内容**（隐私纪律）。
 */
class ImageCompressor(
    private val maxEdge: Int = ImageMath.MAX_EDGE,
    private val quality: Int = ImageMath.JPEG_QUALITY,
) {

    /** 压缩结果：JPEG 字节 + 压缩后宽高（落库记录用）。 */
    data class Result(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    /**
     * 压缩一个本地图片文件。
     *
     * @throws ImageProcessingException `BAD_IMAGE` / `OOM` / `IO`
     */
    fun compress(file: File): Result {
        if (!file.isFile) throw ImageProcessingException(ImageErrorCodes.IO)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
            .onFailure { throw ImageProcessingException(ImageErrorCodes.BAD_IMAGE, it) }
        val plan = ImageMath.plan(bounds.outWidth, bounds.outHeight, maxEdge)
            ?: throw ImageProcessingException(ImageErrorCodes.BAD_IMAGE)

        var bitmap: Bitmap? = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = plan.sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (oom: OutOfMemoryError) {
            throw ImageProcessingException(ImageErrorCodes.OOM, oom)
        }
        if (bitmap == null) throw ImageProcessingException(ImageErrorCodes.BAD_IMAGE)

        try {
            // 3. EXIF 转正
            val orientation = runCatching {
                ExifInterface(file).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            bitmap = applyOrientation(bitmap!!, orientation)

            // 4. 缩到长边 ≤ maxEdge
            bitmap = scaleDown(bitmap!!, maxEdge)

            // 5. 透明底铺白
            bitmap = flattenOnWhiteIfNeeded(bitmap!!)

            // 6. JPEG 0.8
            val output = ByteArrayOutputStream()
            val ok = bitmap!!.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (!ok) throw ImageProcessingException(ImageErrorCodes.BAD_IMAGE)
            return Result(
                bytes = output.toByteArray(),
                width = bitmap!!.width,
                height = bitmap!!.height,
            )
        } catch (oom: OutOfMemoryError) {
            throw ImageProcessingException(ImageErrorCodes.OOM, oom)
        } finally {
            runCatching { if (!bitmap!!.isRecycled) bitmap!!.recycle() }
        }
    }

    // ------------------------------------------------------------ 内部

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val ops = ImageMath.orientationTransform(orientation)
        if (ops.isEmpty()) return source
        val matrix = Matrix()
        ops.forEach { op ->
            when (op) {
                ImageMath.OrientationOp.FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ImageMath.OrientationOp.FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ImageMath.OrientationOp.ROTATE_90 -> matrix.postRotate(90f)
                ImageMath.OrientationOp.ROTATE_180 -> matrix.postRotate(180f)
                ImageMath.OrientationOp.ROTATE_270 -> matrix.postRotate(270f)
            }
        }
        val transformed = runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull() ?: return source
        if (transformed !== source) runCatching { source.recycle() }
        return transformed
    }

    private fun scaleDown(source: Bitmap, edge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= edge) return source
        val scale = edge.toDouble() / longEdge
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) runCatching { source.recycle() }
        return scaled
    }

    private fun flattenOnWhiteIfNeeded(source: Bitmap): Bitmap {
        if (!ImageMath.needsWhiteBackground(source.hasAlpha())) return source
        return try {
            val white = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(white).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, 0f, 0f, null)
            }
            runCatching { source.recycle() }
            white
        } catch (oom: OutOfMemoryError) {
            // 铺白失败不致命：退回原图（透明区域会黑，但至少图还能发出去）。
            source
        }
    }
}
