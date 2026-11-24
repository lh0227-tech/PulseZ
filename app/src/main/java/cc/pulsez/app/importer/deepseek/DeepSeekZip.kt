package cc.pulsez.app.importer.deepseek

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * ZIP 读取限额（`「限额」行）。
 *
 * 注意：条目上限以 的 `100_000` 为准；**不采用** ` 里的
 * `2_000`（「依据」明确要求以计划为准）。
 *
 * @property entries 中央目录条目总数上限（含目录条目）
 * @property entryBytes 单条解压后字节上限
 * @property totalBytes 本读取器累计实际读取字节上限（含 manifest / index）
 * @property maxRatio 单条「实际读取字节 / 中央目录声明压缩大小」上限
 * @property zipBytes ZIP 文件本身大小上限
 */
data class ZipLimits(
    val entries: Int = 100_000,
    val entryBytes: Long = 16L * 1024 * 1024,
    val totalBytes: Long = 512L * 1024 * 1024,
    val maxRatio: Long = 2_000L,
    val zipBytes: Long = 300L * 1024 * 1024,
) {
    companion object {
        val DEFAULT: ZipLimits = ZipLimits()
    }
}

/**
 * 导入失败原因码。UI 层按码给「人话」，不展示任何正文（` 错误码表）。
 *
 * 与计划 §5 表格的映射：
 * - `NOT_ZIP`：魔数不对
 * - `BAD_ZIP`：打开期平台异常（坏格式 / 重名 / 平台路径校验 / JDK 17 对加密条目的 CEN 拒绝）
 * - `ENCRYPTED_OR_DAMAGED`：读取期平台异常（加密流、坏 deflate 数据、截断条目）
 * - `TOO_LARGE` / `TOO_MANY_ENTRIES` / `ENTRY_TOO_LARGE`：限额
 * - `BAD_ENCODING`：内容不是严格 UTF-8
 * - `CRC_MISMATCH`：CRC32 或声明大小对不上
 * - `LOW_STORAGE`：cache 拷贝/空间预检失败
 * - `NOT_FOUND`：文件或条目缺失
 */
object ZipErrorCodes {
    const val NOT_ZIP = "NOT_ZIP"
    const val BAD_ZIP = "BAD_ZIP"
    const val ENCRYPTED_OR_DAMAGED = "ENCRYPTED_OR_DAMAGED"
    const val TOO_LARGE = "TOO_LARGE"
    const val TOO_MANY_ENTRIES = "TOO_MANY_ENTRIES"
    const val ENTRY_TOO_LARGE = "ENTRY_TOO_LARGE"
    const val BAD_ENCODING = "BAD_ENCODING"
    const val CRC_MISMATCH = "CRC_MISMATCH"
    const val LOW_STORAGE = "LOW_STORAGE"
    const val NOT_FOUND = "NOT_FOUND"
}

/**
 * 导入失败异常。**不吞异常变成空结果**：调用方按 [code] 跳过整包或整会话并计数。
 *
 * @property code [ZipErrorCodes] 之一
 */
class ZipImportException(
    val code: String,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(if (detail.isNullOrBlank()) code else "$code: $detail", cause)

/**
 * 条目名规则（纯函数，独立可测）。
 *
 * 依据：`「条目名」行、`（Zip Slip：
 * `java.util.zip` 不检查 `../`，必须自己做）。本项目**不把 ZIP 解压到磁盘**，校验仍必须做：
 * 条目名参与白名单判断与后续会话 id 推导，脏名字不应流进下游。
 */
object ZipEntryNames {

    /** 包根清单。 */
    const val MANIFEST = "manifest.json"

    /** 会话索引（顶层数组）。 */
    const val SESSION_INDEX = "chat_session_index.json"

    /** 会话目录前缀。 */
    const val SESSION_DIR = "chat_sessions/"

    /** 只认 `chat_sessions/<id>.json`（id 不含 `/`，因此 `_files/` 与嵌套目录天然排除）。 */
    private val SESSION_FILE = Regex("^chat_sessions/[^/]+\\.json$")

    /**
     * 文件条目名是否安全（目录条目由调用方跳过，不经过本函数）。
     *
     * 拒绝：空名、NUL、`\`、绝对路径（`/` 开头）、盘符（`C:`）、`.` / `..` / 空段。
     */
    fun isSafeFileEntryName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.indexOf('\u0000') >= 0) return false
        if (name.indexOf('\\') >= 0) return false
        if (name.startsWith('/')) return false
        var start = 0
        while (true) {
            val slash = name.indexOf('/', start)
            val end = if (slash < 0) name.length else slash
            // 空段：开头 / 中间连续 / 结尾斜杠都会命中
            if (end == start) return false
            val length = end - start
            // 盘符：C:/foo、D:/foo …；逐段检查，`a/C:/x` 这种中段盘符也拒
            if (length >= 2 && name[start + 1] == ':' && name[start].isLetter()) return false
            if (length == 1 && name[start] == '.') return false
            if (length == 2 && name[start] == '.' && name[start + 1] == '.') return false
            if (slash < 0) break
            start = slash + 1
        }
        return true
    }

    /** 是否是白名单里的会话条目（规范化后的名字，即已剥公共前缀）。 */
    fun isSessionFile(name: String): Boolean = SESSION_FILE.matches(name)
}

/**
 * DeepSeek 导出 ZIP 的安全只读封装（` 契约、§5 安全与限额）。
 *
 * 设计要点：
 * 1. **不落盘解压**：条目内容只读进内存，按条处理；`_files/` 图片等不在白名单内，不读。
 * 2. **不自己判断 ZIP64 / 加密**：ZIP64 由平台 `ZipFile` 透明处理；加密与坏格式由平台抛
 *    `ZipException`，这里统一映射错误码（打开期 → `BAD_ZIP`，读取期 → `ENCRYPTED_OR_DAMAGED`）。
 * 3. **声明大小不可信**：以实际读出字节数为准（`ZipEntry.getSize()` 未知时为 -1，官方文档）；
 *    CRC32 与「声明大小 == 实际字节数」双重对账。
 * 4. **一层公共前缀**：根上没有 `manifest.json`、但唯一顶层目录下有（第三方文档画成
 *    `deepseek_data-YYYY-MM-DD/` 目录树）时剥掉前缀，后续条目名统一规范化
 *    （` 未核实项 2 的防御性处理）。
 * 5. **严格 UTF-8**：`CodingErrorAction.REPORT`，只容忍并剥掉 UTF-8 BOM；不回退 GB18030
 *    （`GB18030 回退会把坏字节静默解成乱码，脏数据比失败更糟）。
 *
 * 线程模型：实例非线程安全（内部有累计读取计数），按会话串行使用；`close()` 后释放文件句柄。
 */
class DeepSeekZip private constructor(
    private val zip: ZipFile,
    private val limits: ZipLimits,
    /** 规范化名字（已剥公共前缀、已过白名单）→ 中央目录条目；顺序为 ZIP 内顺序。 */
    private val entries: Map<String, ZipEntry>,
) : Closeable {

    /** 会话条目（[EntryInfo] 的尺寸来自中央目录，不 inflate）。 */
    data class EntryInfo(
        val name: String,
        val compressedSize: Long,
        val declaredSize: Long,
    )

    private var totalReadBytes: Long = 0L

    private val sessionNames: List<String> =
        entries.keys.filter(ZipEntryNames::isSessionFile)

    // ---- 对外读取入口 ----

    /** 根 `manifest.json`（已剥一层公共前缀、已剥 BOM）。缺失抛 `NOT_FOUND`。 */
    fun manifestText(): String {
        val entry = entries[ZipEntryNames.MANIFEST]
            ?: throw ZipImportException(ZipErrorCodes.NOT_FOUND, "包根目录没有 manifest.json")
        return readEntryText(entry)
    }

    /** `chat_session_index.json`；包内没有则返回 null（预检仍可继续，只是没有时间范围）。 */
    fun indexText(): String? {
        val entry = entries[ZipEntryNames.SESSION_INDEX] ?: return null
        return readEntryText(entry)
    }

    /** 规范化后的 `chat_sessions/<id>.json` 列表（ZIP 顺序；已排除 `_files/` 与嵌套目录）。 */
    fun sessionEntryNames(): List<String> = sessionNames

    /**
     * 只读中央目录的会话尺寸（不 inflate），供确认屏粗估费用。
     *
     * `declaredSize` 为 -1 表示平台没给声明大小（未知）；调用方按「未知」处理。
     */
    fun sessionSizes(): List<EntryInfo> = sessionNames.map { name ->
        val entry = entries.getValue(name)
        EntryInfo(
            name = name,
            compressedSize = entry.compressedSize,
            declaredSize = entry.size,
        )
    }

    /**
     * 读一个会话条目：严格 UTF-8 + BOM 容忍 + CRC32 对账 + 声明大小对账 + 四项限额。
     *
     * @param normalizedName [sessionEntryNames] 里的名字（已规范化）
     * @throws ZipImportException `NOT_FOUND` / `ENTRY_TOO_LARGE` / `TOO_LARGE` /
     *   `ENCRYPTED_OR_DAMAGED` / `CRC_MISMATCH` / `BAD_ENCODING`
     */
    fun sessionText(normalizedName: String): String {
        val entry = entries[normalizedName]
        if (entry == null || !ZipEntryNames.isSessionFile(normalizedName)) {
            throw ZipImportException(ZipErrorCodes.NOT_FOUND, "没有这个会话条目")
        }
        return readEntryText(entry)
    }

    /** 释放底层文件句柄（幂等）。 */
    override fun close() {
        zip.close()
    }

    // ---- 内部：单条读取与对账 ----

    private fun readEntryText(entry: ZipEntry): String {
        val declaredSize = entry.size
        val compressedSize = entry.compressedSize
        // 声明大小不可信的兜底：先按声明值拒超大，再在读取过程中按实际字节重复检查。
        if (declaredSize > limits.entryBytes) throw entryTooLarge()
        if (declaredSize > 0 && exceedsRatio(declaredSize, compressedSize)) throw tooLarge("压缩比超过上限")

        val buffer = ByteArray(READ_CHUNK)
        val initialCapacity = if (declaredSize in 1..PREALLOC_CAP) declaredSize.toInt() else READ_CHUNK
        val bytes = ByteArrayOutputStream(initialCapacity)
        val crc = CRC32()
        var entryRead = 0L
        try {
            // JDK/Android 的 ZipFile#getInputStream 理论上可对未知条目返回 null（平台类型）。
            val input: InputStream = zip.getInputStream(entry)
                ?: throw ZipImportException(ZipErrorCodes.NOT_FOUND, "读不到条目")
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    entryRead += read
                    if (entryRead > limits.entryBytes) throw entryTooLarge()
                    if (totalReadBytes + entryRead > limits.totalBytes) throw tooLarge("累计读取超过上限")
                    if (exceedsRatio(entryRead, compressedSize)) throw tooLarge("压缩比超过上限")
                    bytes.write(buffer, 0, read)
                    crc.update(buffer, 0, read)
                }
            }
        } catch (e: ZipException) {
            // 读取期平台异常：加密流或坏 deflate 数据。不自己判加密（计划 §5「平台异常」行）。
            throw ZipImportException(ZipErrorCodes.ENCRYPTED_OR_DAMAGED, "条目读取失败", e)
        } catch (e: IOException) {
            throw ZipImportException(ZipErrorCodes.ENCRYPTED_OR_DAMAGED, "条目读取失败", e)
        }

        if (declaredSize >= 0 && entryRead != declaredSize) {
            throw ZipImportException(ZipErrorCodes.CRC_MISMATCH, "声明大小与实际字节数不符")
        }
        if (entry.crc >= 0 && crc.value != entry.crc) {
            throw ZipImportException(ZipErrorCodes.CRC_MISMATCH, "CRC32 对不上")
        }
        totalReadBytes += entryRead
        return decodeStrictUtf8(bytes.toByteArray())
    }

    /** 实际读取字节 / 声明压缩大小 是否超过上限（防 Long 溢出）。 */
    private fun exceedsRatio(actual: Long, compressed: Long): Boolean {
        if (actual <= 0 || compressed <= 0 || limits.maxRatio <= 0) return false
        if (compressed > Long.MAX_VALUE / limits.maxRatio) return false
        return actual > compressed * limits.maxRatio
    }

    private fun entryTooLarge(): ZipImportException =
        ZipImportException(ZipErrorCodes.ENTRY_TOO_LARGE, "单条解压后超过上限")

    private fun tooLarge(detail: String): ZipImportException =
        ZipImportException(ZipErrorCodes.TOO_LARGE, detail)

    private fun decodeStrictUtf8(bytes: ByteArray): String {
        val offset = if (hasUtf8Bom(bytes)) UTF8_BOM_SIZE else 0
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: CharacterCodingException) {
            throw ZipImportException(ZipErrorCodes.BAD_ENCODING, "内容不是合法 UTF-8", e)
        }
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= UTF8_BOM_SIZE &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF

    // ---- companion：打开、扫描、白名单与公共前缀 ----

    companion object {

        /** 读块 64 KiB：与 `「8 KB–64 KB 块」一致。*/
        private const val READ_CHUNK = 64 * 1024

        /** 预分配 BAOS 容量的上限（默认单条上限 16 MiB，正常条目一次分配）。 */
        private const val PREALLOC_CAP = 16L * 1024 * 1024

        private const val UTF8_BOM_SIZE = 3

        /**
         * 打开 ZIP 并建好白名单索引。**构造期异常统一 catch 成人话错误码**（计划 §5）。
         *
         * @throws ZipImportException `NOT_FOUND` / `NOT_ZIP` / `TOO_LARGE` /
         *   `TOO_MANY_ENTRIES` / `BAD_ZIP`
         */
        fun open(file: File, limits: ZipLimits = ZipLimits.DEFAULT): DeepSeekZip {
            if (!file.isFile) throw ZipImportException(ZipErrorCodes.NOT_FOUND, "找不到压缩包文件")
            if (file.length() > limits.zipBytes) {
                throw ZipImportException(ZipErrorCodes.TOO_LARGE, "压缩包超过大小上限")
            }
            if (!ZipMagic.isZip(ImportCache.snapshotHead(file))) {
                throw ZipImportException(ZipErrorCodes.NOT_ZIP, "这不是压缩包")
            }
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw ZipImportException(ZipErrorCodes.BAD_ZIP, "压缩包无法打开", e)
            } catch (e: IOException) {
                throw ZipImportException(ZipErrorCodes.BAD_ZIP, "压缩包无法打开", e)
            }
            return try {
                DeepSeekZip(zip, limits, scan(zip, limits))
            } catch (t: Throwable) {
                // 扫描失败（条目超限/名字不安全）时不能漏掉文件句柄。
                runCatching { zip.close() }
                throw t
            }
        }

        /** 扫中央目录：条目数上限 → 名字安全 → 剥一层公共前缀 → 白名单。 */
        private fun scan(zip: ZipFile, limits: ZipLimits): Map<String, ZipEntry> {
            val fileEntries = ArrayList<ZipEntry>()
            var count = 0
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                count++
                if (count > limits.entries) {
                    throw ZipImportException(ZipErrorCodes.TOO_MANY_ENTRIES, "条目数超过上限")
                }
                if (entry.isDirectory) continue
                if (!ZipEntryNames.isSafeFileEntryName(entry.name)) {
                    throw ZipImportException(ZipErrorCodes.BAD_ZIP, "条目名不安全")
                }
                fileEntries += entry
            }

            val prefix = detectCommonPrefix(fileEntries)
            val result = LinkedHashMap<String, ZipEntry>()
            for (entry in fileEntries) {
                val normalized = if (prefix.isEmpty()) {
                    entry.name
                } else {
                    if (!entry.name.startsWith(prefix)) continue
                    entry.name.substring(prefix.length)
                }
                if (normalized == ZipEntryNames.MANIFEST ||
                    normalized == ZipEntryNames.SESSION_INDEX ||
                    ZipEntryNames.isSessionFile(normalized)
                ) {
                    // 重名取第一条（` 对真实世界重复条目的处理口径）。
                    result.putIfAbsent(normalized, entry)
                }
            }
            return result
        }

        /**
         * 根上没有 manifest 时，找唯一顶层目录：若 `<dir>/manifest.json` 存在则剥 `<dir>/`。
         * 找不到唯一顶层目录、或目录下也没有 manifest，就不剥（保持原样，由 [manifestText] 报 NOT_FOUND）。
         */
        private fun detectCommonPrefix(fileEntries: List<ZipEntry>): String {
            val names = HashSet<String>(fileEntries.size)
            for (entry in fileEntries) names += entry.name
            if (ZipEntryNames.MANIFEST in names) return ""

            val topLevelDirs = HashSet<String>()
            for (name in names) {
                val slash = name.indexOf('/')
                if (slash > 0) topLevelDirs += name.substring(0, slash)
            }
            if (topLevelDirs.size != 1) return ""
            val prefix = topLevelDirs.first() + "/"
            return if ((prefix + ZipEntryNames.MANIFEST) in names) prefix else ""
        }
    }
}
