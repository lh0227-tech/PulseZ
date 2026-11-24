package cc.pulsez.app.media

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片压缩/缩略图/发送前上限的**纯决策**（v0.5.0 `）。
 *
 * 为什么把"算尺寸、算采样、判断要不要压缩、判断要不要铺白"抽成纯 Kotlin：
 * `BitmapFactory`/`Bitmap.compress` 在纯 JVM 单测里没有真实行为
 * ，所以只把可判定的部分做成纯函数用 JVM 测，
 * 真正的解码/压缩胶水层标"真机待验"。
 *
 * 依据：
 * - 长边 ≤1536、JPEG 0.8：
 * - 透明底铺白 （透明 PNG 直接转 JPEG 会变黑底）；
 * - 发送前上限口径：` 第 320 行——原来的"请求体 ≤48MiB"
 *   永远不会触发（1536px JPEG 只有几百 KB），改成「单图压缩后 >2MB 或 3 图合计 >8MB 才提示」；
 * - 单图输入上限 10MB 沿用 `AttachmentStore.DEFAULT_MAX_BYTES`。
 */
object ImageMath {

    /** 压缩后长边上限。*/
    const val MAX_EDGE: Int = 1_536

    /** JPEG 质量（0–100）。*/
    const val JPEG_QUALITY: Int = 80

    /** 列表/气泡缩略图解码目标边长（bench：256px 足够 132dp 显示，多图历史不 OOM）。 */
    const val THUMBNAIL_EDGE: Int = 256

    /** 点开大图时的解码目标边长（1280 足够手机屏，别解原图）。 */
    const val PREVIEW_EDGE: Int = 1_280

    /** 草稿最多几张。*/
    const val MAX_IMAGES: Int = 3

    /** 单图输入上限 10MB（与 `AttachmentStore.DEFAULT_MAX_BYTES` 同口径）。*/
    const val MAX_INPUT_BYTES: Long = 10L * 1024 * 1024

    /** 单图压缩后超过 2MB 才提示（的可触发口径）。*/
    const val SINGLE_MAX_BYTES: Long = 2L * 1024 * 1024

    /** 一轮合计超过 8MB 才提示（的可触发口径）。*/
    const val TOTAL_MAX_BYTES: Long = 8L * 1024 * 1024

    /** 解码采样方案：用 2 的幂 `inSampleSize` 先把超大图降到接近目标，避免先解全图 OOM。 */
    data class CompressPlan(
        /** 解码后长边（估算；实际由 `inSampleSize` 与图像尺寸决定）。 */
        val decodedLongEdge: Int,
        /** 目标长边（= min(原长边, [maxEdge])）。 */
        val targetLongEdge: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val sampleSize: Int,
        /** 解码后是否还要再缩一次（长边 > maxEdge）。 */
        val needsResizeAfterDecode: Boolean,
    )

    /**
     * 计算长边缩到 [maxEdge] 的方案。
     *
     * @return null = 尺寸非法（0/负数）
     */
    fun plan(width: Int, height: Int, maxEdge: Int = MAX_EDGE): CompressPlan? {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return null
        val longEdge = max(width, height)
        val sample = inSampleSize(longEdge, maxEdge)
        val decodedLong = (longEdge.toDouble() / sample).roundToInt().coerceAtLeast(1)
        val targetLong = minOf(longEdge, maxEdge)
        val scale = targetLong.toDouble() / longEdge
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return CompressPlan(
            decodedLongEdge = decodedLong,
            targetLongEdge = targetLong,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            sampleSize = sample,
            // 采样后仍比目标大 → 解码后还要 createScaledBitmap 一次。
            needsResizeAfterDecode = decodedLong > targetLong,
        )
    }

    /**
     * 计算 `BitmapFactory.Options.inSampleSize`（必须是 2 的幂）。
     *
     * 规则：选最大的 2 的幂，使 `长边 / sampleSize >= maxEdge`（保证解码后仍 ≥ 目标，
     * 再由 `createScaledBitmap` 缩到位，不会因为采样过度把图糊掉）。
     */
    fun inSampleSize(longEdge: Int, maxEdge: Int): Int {
        if (longEdge <= 0 || maxEdge <= 0) return 1
        if (longEdge <= maxEdge) return 1
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * 缩略图采样：目标是"尽量接近 [targetEdge]"，允许采样到略小于目标
     * （缩略图清晰度要求低，省内存优先）。
     */
    fun thumbnailSampleSize(longEdge: Int, targetEdge: Int = THUMBNAIL_EDGE): Int {
        if (longEdge <= 0 || targetEdge <= 0 || longEdge <= targetEdge) return 1
        var sample = 1
        while (longEdge / (sample * 2) >= targetEdge / 2) {
            sample *= 2
        }
        return sample
    }

    /** 是否需要把透明底铺白：有 alpha 通道的图（PNG/WebP/透明贴纸）转 JPEG 前必须铺白。 */
    fun needsWhiteBackground(hasAlpha: Boolean): Boolean = hasAlpha

    /** 发送前体积检查结果（`null` = 没问题）。 */
    enum class SendLimitProblem {
        /** 单图压缩后 >2MB。 */
        SINGLE_TOO_LARGE,

        /** 一轮合计 >8MB。 */
        TOTAL_TOO_LARGE,
    }

    /**
     * 发送前体积检查（口径：单图 >2MB 或合计 >8MB 才提示，而不是永不触发的 48MiB）。
     *
     * 注：v0.5 最多 3 张、单张又被 2MB 挡在前面，所以"合计 8MB"在当前参数下实际不会触发；
     * 保留这条口径是为了以后放宽张数时不用重定阈值（与 的写法一致）。
     *
     * @param sizesBytes 本轮流准备发送的图片压缩后字节数
     */
    fun sendLimitProblem(sizesBytes: List<Long>): SendLimitProblem? {
        if (sizesBytes.isEmpty()) return null
        if (sizesBytes.any { it > SINGLE_MAX_BYTES }) return SendLimitProblem.SINGLE_TOO_LARGE
        if (sizesBytes.sum() > TOTAL_MAX_BYTES) return SendLimitProblem.TOTAL_TOO_LARGE
        return null
    }

    /** EXIF 方向的矩阵操作（顺序即应用顺序）。 */
    enum class OrientationOp {
        FLIP_HORIZONTAL,
        FLIP_VERTICAL,
        ROTATE_90,
        ROTATE_180,
        ROTATE_270,
    }

    /**
     * EXIF `TAG_ORIENTATION` → 转正操作。
     *
     * 8 种取值的映射沿用成熟实现（AndroidX/Coil 的 `ExifUtils` 与官方示例同款）：
     * `2` 水平翻转、`3` 180°、`4` 垂直翻转、`5` 90°+水平翻转、`6` 90°、`7` 270°+水平翻转、`8` 270°，
     * `1`/未知 → 不动。
     *
     * 依据：https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
     * （`ORIENTATION_*` 常量语义）；成熟实现参考 Coil `decode/ExifUtils.kt`
     * （` 源码一手核对）。
     *
     * 注意：这些数字就是 `ExifInterface.ORIENTATION_*` 的取值，纯函数用 int 表达，
     * 不依赖 Android 类，方便 JVM 单测。
     */
    fun orientationTransform(orientation: Int): List<OrientationOp> = when (orientation) {
        2 -> listOf(OrientationOp.FLIP_HORIZONTAL)
        3 -> listOf(OrientationOp.ROTATE_180)
        4 -> listOf(OrientationOp.FLIP_VERTICAL)
        5 -> listOf(OrientationOp.ROTATE_90, OrientationOp.FLIP_HORIZONTAL)
        6 -> listOf(OrientationOp.ROTATE_90)
        7 -> listOf(OrientationOp.ROTATE_270, OrientationOp.FLIP_HORIZONTAL)
        8 -> listOf(OrientationOp.ROTATE_270)
        else -> emptyList()
    }

    /** 是否动图（GIF）：只发第一帧时 UI 要说明一句。*/
    fun isAnimatedMime(mime: String?): Boolean =
        mime?.lowercase()?.substringBefore(';')?.trim() == "image/gif"
}
