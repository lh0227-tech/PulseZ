package cc.pulsez.app.media

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * 手写缩略图缓存（v0.5.0 方案 A+，`）。
 *
 * 为什么不引 Coil/Glide：图片本来就在 app 私有目录，库的核心价值（网络抓取、磁盘缓存、
 * 请求去重）用不上；Coil 2.7 实测 R8 增量 220–310KB，会吃掉 v0.5 的 3.6MB 预算
 * 。手写 80–120 行即可。
 *
 * 设计：
 * - 磁盘上已有原图/压缩图，**不做磁盘缓存**（纯冗余）；
 * - 内存 `LruCache`，按 Bitmap 字节数计，上限 = maxMemory/8（夹在 4–16MB）；
 * - key = `路径 + mtime + 目标边长`：文件被替换/删除后不会拿到旧图；
 * - 解码在 IO 线程由调用方负责（`suspend` 包装在 Compose `produceState` 里）；
 * - [install] 注册 `onTrimMemory`：系统内存吃紧/页面销毁时 `evictAll()`，避免多图历史 OOM。
 *
 * 失败（文件被清/损坏）返回 null；UI 显示「图片已删除」占位，不阻塞滚动。
 */
object ThumbnailCache {

    private const val MIN_BYTES: Long = 4L * 1024 * 1024
    private const val MAX_BYTES: Long = 16L * 1024 * 1024

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(capacityBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Volatile
    private var installed = false

    /**
     * 注册系统内存回调（幂等）。
     *
     * 生产入口：`PulsezApp.onCreate` 在**进程启动**就调一次（v0.5.0
     * ` ）——只在首次选图时注册的话，"冷启动后只浏览历史图"的会话
     * 收不到 `onTrimMemory`。只注册回调、不预解码，不拖慢冷启动；
     * `AppContainer.imagePipeline` 懒加载时还会幂等兜底一次（其它入口/测试）。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        runCatching {
            context.applicationContext.registerComponentCallbacks(
                object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) evictAll()
                    }

                    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

                    override fun onLowMemory() {
                        evictAll()
                    }
                },
            )
        }
    }

    /**
     * 读一张缩略图（同步；调用方保证在 IO 线程）。
     *
     * @param file 本地图片文件（附件私有目录）
     * @param targetEdge 目标长边（缩略图 256 / 大图预览 1280）
     * @return 解码失败/文件不在 → null
     */
    fun load(file: File, targetEdge: Int = ImageMath.THUMBNAIL_EDGE): Bitmap? {
        if (!file.isFile || targetEdge <= 0) return null
        val key = "${file.absolutePath}#${file.lastModified()}#$targetEdge"
        cache.get(key)?.let { if (!it.isRecycled) return it }
        val bitmap = decodeSampled(file, targetEdge) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** 清空（页面销毁/内存吃紧时调用；之后再显示会重新解码）。 */
    fun evictAll() {
        runCatching { cache.evictAll() }
    }

    /** 当前缓存占用字节（仅测试/诊断用；不打印路径）。 */
    internal fun sizeBytes(): Int = cache.size()

    private fun decodeSampled(file: File, targetEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.thumbnailSampleSize(longEdge, targetEdge)
            // 缩略图用 RGB_565 省内存；点开大图（targetEdge 较大）用 ARGB_8888 保画质。
            inPreferredConfig = if (targetEdge > 512) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val decodedLong = maxOf(bitmap.width, bitmap.height)
        if (decodedLong > targetEdge) {
            val scale = targetEdge.toDouble() / decodedLong
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        bitmap
    }.getOrNull()

    private fun capacityBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8).coerceIn(MIN_BYTES, MAX_BYTES).toInt()
    }
}
