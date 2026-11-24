package cc.pulsez.app.importer.deepseek

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入缓存：SAF 流 → `cacheDir/import/<uuid>.zip`、可用空间预检、残留清理
 * （` 阶段 1、§4.1、§5「文件 / 清理」行）。
 *
 * 分层：`ContentResolver` / `Uri` 胶水只出现在 [copyToCache]；拷贝、空间判断、清理都是
 * 可纯 JVM 测的函数（`纯 JVM 能覆盖绝大部分，不依赖 Robolectric）。
 *
 * 隐私与纪律：本文件不打印任何文件内容；临时文件只放 `cacheDir`（系统可清），
 * 失败/中断时由调用方或启动清理兜底删除。
 */
object ImportCache {

    /** `cacheDir` 下的导入临时目录名。 */
    const val IMPORT_DIR_NAME = "import"

    /** 拷贝块 64 KiB（`「8 KB–64 KB 块」）。*/
    private const val COPY_CHUNK = 64 * 1024

    /** 可用空间必须比所需文件多留 100 MiB（计划 §5「文件」行）。 */
    const val SAFETY_MARGIN_BYTES: Long = 100L * 1024 * 1024

    /** 残留清理年龄：24 小时（计划 §5「清理」行）。 */
    const val STALE_MAX_AGE_MS: Long = 24L * 60 * 60 * 1000

    /** 魔数嗅探默认读取字节数。 */
    private const val HEAD_BYTES = 64

    /** `cacheDir/import`（不创建；创建发生在 [copyToCache] / [checkSpace]）。 */
    fun importDir(cacheDir: File): File = File(cacheDir, IMPORT_DIR_NAME)

    // ---- 纯函数：限长拷贝 ----

    /**
     * 把 [input] 拷贝到 [output]，**最多写 [maxBytes] 字节**；超限立即中止并抛 `TOO_LARGE`。
     *
     * 语义：
     * - 返回实际拷贝字节数（≤ [maxBytes]）；
     * - 超限时**不写出**超出上限的那一块（先判断再写），输出文件不会超过上限；
     * - 不关闭 [input]/[output]，由调用方负责（便于 SAF 胶水层统一 finally 清理）；
     * - [onProgress] 每次成功写块后回调累计字节数（单调递增）。
     *
     * @throws ZipImportException `TOO_LARGE`（超限）
     */
    fun copyStreamLimited(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val buffer = ByteArray(COPY_CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (total + read > maxBytes) {
                throw ZipImportException(ZipErrorCodes.TOO_LARGE, "文件超过大小上限")
            }
            output.write(buffer, 0, read)
            total += read
            onProgress(total)
        }
        return total
    }

    // ---- SAF 胶水层（无法纯 JVM 测；逻辑全部委托上面的纯函数） ----

    /**
     * 把 SAF 选中的文件拷贝到 [dir] 下（UUID 命名），返回落盘文件。
     *
     * - 64 KiB 块、边拷边计数，超过 [ZipLimits.DEFAULT].zipBytes 立即中止；
     * - `OpenableColumns.SIZE` **只作提示**（预检空间 + 提前拒超大），丢文件时以实际拷贝字节为准；
     * - 任一步失败（含超限）删除半成品，不留垃圾。
     *
     * @throws ZipImportException `NOT_FOUND`（选不到流或文件已消失）/ `TOO_LARGE` / `LOW_STORAGE`
     */
    suspend fun copyToCache(resolver: ContentResolver, uri: Uri, dir: File): File =
        withContext(Dispatchers.IO) {
            val maxBytes = ZipLimits.DEFAULT.zipBytes
            val declared = declaredSizeOrNull(resolver, uri)
            if (declared != null && declared > maxBytes) {
                throw ZipImportException(ZipErrorCodes.TOO_LARGE, "文件超过大小上限")
            }
            // 空间预检：声明大小只作提示；拿不到就不预检，拷贝过程中的实际字节兜底。
            checkSpace(dir, declared ?: 0L)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw ZipImportException(ZipErrorCodes.LOW_STORAGE, "建不了临时目录")
            }
            val dest = File(dir, UUID.randomUUID().toString() + ".zip")
            try {
                val input = resolver.openInputStream(uri)
                    ?: throw ZipImportException(ZipErrorCodes.NOT_FOUND, "读不到选中的文件")
                input.use { source ->
                    FileOutputStream(dest).use { output ->
                        copyStreamLimited(source, output, maxBytes)
                    }
                }
                dest
            } catch (e: ZipImportException) {
                deleteQuietly(dest)
                throw e
            } catch (e: IOException) {
                // 读/写失败（文件被移走、cache 写不进）。映射到唯一的存储类错误码，调用方可重选文件。
                deleteQuietly(dest)
                throw ZipImportException(ZipErrorCodes.LOW_STORAGE, "拷贝到临时目录失败", e)
            }
        }

    /** 读 `OpenableColumns.SIZE`；拿不到（查询失败/无此列/空值）返回 null，只当提示。 */
    private fun declaredSizeOrNull(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        }
    } catch (e: Exception) {
        // 查询只是提示：任何异常都不阻断拷贝流程。
        null
    }

    // ---- 纯函数：可用空间 ----

    /**
     * 目录所在分区可用字节；目录不存在时尝试创建。
     *
     * 返回 null 表示**无法判断**（目录建不出来）；返回 0 表示平台报 0（已满或读不到，
     * [checkSpace] 会按「不够」处理，宁可误拒不可放过）。
     */
    fun usableSpaceOrNull(dir: File): Long? {
        val target = when {
            dir.isDirectory -> dir
            dir.mkdirs() -> dir
            else -> return null
        }
        return target.usableSpace
    }

    /**
     * 空间预检：要求可用空间 ≥ [requiredBytes] + [SAFETY_MARGIN_BYTES]，否则抛 `LOW_STORAGE`。
     *
     * [requiredBytes] ≤ 0（大小未知）时只检查目录可写（会尝试创建）。无法判断可用空间时不拦。
     *
     * @throws ZipImportException `LOW_STORAGE`
     */
    fun checkSpace(dir: File, requiredBytes: Long) {
        val usable = usableSpaceOrNull(dir)
        if (usable == null) {
            throw ZipImportException(ZipErrorCodes.LOW_STORAGE, "建不了临时目录")
        }
        if (requiredBytes <= 0L) return
        // 写成减法避免 requiredBytes 极大时加法溢出。
        if (usable - SAFETY_MARGIN_BYTES < requiredBytes) {
            throw ZipImportException(ZipErrorCodes.LOW_STORAGE, "剩余空间不足")
        }
    }

    // ---- 纯函数：魔数嗅探头 ----

    /**
     * 读文件开头最多 [maxBytes] 字节（用于 [ZipMagic.isZip] 嗅探）。
     *
     * 读失败（不存在/无权限）返回空数组，由调用方按「不是 ZIP」处理；不抛异常，不打印内容。
     */
    fun snapshotHead(file: File, maxBytes: Int = HEAD_BYTES): ByteArray {
        if (maxBytes <= 0 || !file.isFile) return ByteArray(0)
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(maxBytes)
                var read = 0
                while (read < maxBytes) {
                    val n = input.read(buffer, read, maxBytes - read)
                    if (n < 0) break
                    read += n
                }
                if (read == maxBytes) buffer else buffer.copyOf(read)
            }
        } catch (e: IOException) {
            ByteArray(0)
        }
    }

    // ---- 纯函数：残留清理 ----

    /**
     * 删除 [dir] 下修改时间早于 `now - maxAgeMs` 的**文件**（不删子目录），返回删除个数。
     *
     * 只用于启动时清 24h 前残留（计划 §5「清理」行）；年轻文件（正在导入/刚拷贝）一律不碰，
     * 避免清掉别的进程/上一轮任务正在用的包。
     */
    fun cleanupStale(
        dir: File,
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = STALE_MAX_AGE_MS,
    ): Int {
        if (!dir.isDirectory) return 0
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (!file.isFile) continue
            if (now - file.lastModified() > maxAgeMs && file.delete()) deleted++
        }
        return deleted
    }

    /** 删临时文件（失败不抛）：导入成功/失败/停止后的清理用。 */
    fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }
}
