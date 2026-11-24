package cc.pulsez.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片压缩与缩略图的纯决策。
 *
 * `BitmapFactory`/`Bitmap.compress` 的真实行为在纯 JVM 里不可靠，所以这里只测
 * 「目标尺寸 / 采样率 / 是否还要缩 / 透明铺白 / 发送前上限 / EXIF 转正操作序列」这些纯函数；
 * 真正的解码压缩标"真机待验"。
 */
class ImageMathTest {

    @Test
    fun `长边超过 1536 时缩到 1536 并保持比例`() {
        val plan = ImageMath.plan(4000, 3000)!!
        assertEquals(1_536, plan.targetLongEdge)
        assertEquals(1_536, plan.targetWidth)
        // 3000 / 4000 * 1536 = 1152
        assertEquals(1_152, plan.targetHeight)
        assertEquals(2, plan.sampleSize)
        assertTrue("解码后 2000 仍大于 1536，需要再缩一次", plan.needsResizeAfterDecode)
    }

    @Test
    fun `超大的图用 2 的幂采样先降下来`() {
        val plan = ImageMath.plan(8_000, 6_000)!!
        // 8000/2=4000>=1536 → 2；8000/4=2000>=1536 → 4；8000/8=1000<1536 → 停。
        assertEquals(4, plan.sampleSize)
        assertEquals(2_000, plan.decodedLongEdge)
        assertEquals(1_536, plan.targetWidth)
        assertEquals(1_152, plan.targetHeight)
    }

    @Test
    fun `小图不放大不压缩`() {
        val plan = ImageMath.plan(1000, 800)!!
        assertEquals(1, plan.sampleSize)
        assertEquals(1000, plan.targetWidth)
        assertEquals(800, plan.targetHeight)
        assertFalse(plan.needsResizeAfterDecode)
    }

    @Test
    fun `非法尺寸返回 null`() {
        assertNull(ImageMath.plan(0, 100))
        assertNull(ImageMath.plan(100, -1))
        assertNull(ImageMath.plan(100, 100, maxEdge = 0))
    }

    @Test
    fun `inSampleSize 始终是 2 的幂且不会采样过度`() {
        assertEquals(1, ImageMath.inSampleSize(1_536, 1_536))
        // 3000/2=1500 < 1536：采样到 1 就够了，解码后再等比缩。
        assertEquals(1, ImageMath.inSampleSize(3_000, 1_536))
        // 6000/2=3000 >= 1536、6000/4=1500 < 1536 → 采样 2。
        assertEquals(2, ImageMath.inSampleSize(6_000, 1_536))
        listOf(1_537, 2_048, 4_096, 8_192, 16_384).forEach { longEdge ->
            val sample = ImageMath.inSampleSize(longEdge, 1_536)
            assertEquals("$longEdge 的采样必须是 2 的幂", 0, sample and (sample - 1))
            assertTrue("$longEdge 采样后不能小于目标", longEdge / sample >= 1_536)
        }
    }

    @Test
    fun `缩略图采样把长边降到接近 256`() {
        assertEquals(1, ImageMath.thumbnailSampleSize(200, 256))
        val sample = ImageMath.thumbnailSampleSize(4_000, 256)
        assertTrue("$sample 采样后应接近 256", 4_000 / sample in 128..256)
    }

    @Test
    fun `透明底才需要铺白`() {
        assertTrue(ImageMath.needsWhiteBackground(hasAlpha = true))
        assertFalse(ImageMath.needsWhiteBackground(hasAlpha = false))
    }

    @Test
    fun `发送前上限口径 单图 2MB 合计 8MB`() {
        assertNull("单张 1.5MB 没问题", ImageMath.sendLimitProblem(listOf(1_500_000)))
        assertEquals(
            ImageMath.SendLimitProblem.SINGLE_TOO_LARGE,
            ImageMath.sendLimitProblem(listOf(ImageMath.SINGLE_MAX_BYTES + 1)),
        )
        // 3 张 × 1.5MB = 4.5MB，没到 8MB。
        assertNull(
            ImageMath.sendLimitProblem(
                listOf(1_500_000L, 1_500_000L, 1_500_000L),
            ),
        )
        // 合计逻辑单独兜底（最多 3 张时不会触发，保留口径防未来放宽张数）。
        assertEquals(
            ImageMath.SendLimitProblem.TOTAL_TOO_LARGE,
            ImageMath.sendLimitProblem(
                listOf(1_900_000L, 1_900_000L, 1_900_000L, 1_900_000L, 1_900_000L),
            ),
        )
        assertNull("没有图就没有上限问题", ImageMath.sendLimitProblem(emptyList()))
    }

    @Test
    fun `EXIF 八种方向的转正操作`() {
        assertEquals(emptyList<ImageMath.OrientationOp>(), ImageMath.orientationTransform(1))
        assertEquals(
            listOf(ImageMath.OrientationOp.FLIP_HORIZONTAL),
            ImageMath.orientationTransform(2),
        )
        assertEquals(listOf(ImageMath.OrientationOp.ROTATE_180), ImageMath.orientationTransform(3))
        assertEquals(
            listOf(ImageMath.OrientationOp.FLIP_VERTICAL),
            ImageMath.orientationTransform(4),
        )
        assertEquals(
            listOf(ImageMath.OrientationOp.ROTATE_90, ImageMath.OrientationOp.FLIP_HORIZONTAL),
            ImageMath.orientationTransform(5),
        )
        assertEquals(listOf(ImageMath.OrientationOp.ROTATE_90), ImageMath.orientationTransform(6))
        assertEquals(
            listOf(ImageMath.OrientationOp.ROTATE_270, ImageMath.OrientationOp.FLIP_HORIZONTAL),
            ImageMath.orientationTransform(7),
        )
        assertEquals(listOf(ImageMath.OrientationOp.ROTATE_270), ImageMath.orientationTransform(8))
        assertEquals("未知方向不动", emptyList<ImageMath.OrientationOp>(), ImageMath.orientationTransform(0))
    }

    @Test
    fun `GIF 按 MIME 判定`() {
        assertTrue(ImageMath.isAnimatedMime("image/gif"))
        assertTrue(ImageMath.isAnimatedMime("IMAGE/GIF; charset=binary"))
        assertFalse(ImageMath.isAnimatedMime("image/jpeg"))
        assertFalse(ImageMath.isAnimatedMime(null))
    }

    @Test
    fun `常量锁死文档口径`() {
        assertEquals(1_536, ImageMath.MAX_EDGE)
        assertEquals(80, ImageMath.JPEG_QUALITY)
        assertEquals(3, ImageMath.MAX_IMAGES)
        assertEquals(10L * 1024 * 1024, ImageMath.MAX_INPUT_BYTES)
    }
}
