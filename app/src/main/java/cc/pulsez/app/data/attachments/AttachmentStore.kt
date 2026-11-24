package cc.pulsez.app.data.attachments

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 附件相关的可读错误码（错误不吞，调用方按码换人话）。
 *
 * 与 `ZipErrorCodes`（`importer/deepseek/DeepSeekZip.kt`）同一风格：异常带稳定 code，
 * UI/日志层按 code 出文案，不把原始路径/正文透给用户。
 */
object AttachmentErrorCodes {
    /** 写入超过调用方给的字节上限（半成品已删）。 */
    const val TOO_LARGE = "TOO_LARGE"

    /** 磁盘/目录/重命名等 IO 失败（磁盘满、目录建不出来、文件被占用）。 */
    const val STORE_IO = "STORE_IO"

    /** 参数不合法：id 含路径分隔符、扩展名不合法、上限 ≤ 0、必填字段空白。 */
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"

    /** 要操作的行/文件不存在，或已软删。 */
    const val NOT_FOUND = "NOT_FOUND"

    /** DB 写入失败（文件已回滚删除）。 */
    const val DB = "DB"

    /** 进程级 `AttachmentStore` 还没安装（`PulsezApp.onCreate` 负责安装）。 */
    const val STORE_NOT_INSTALLED = "STORE_NOT_INSTALLED"
}

/**
 * 附件层可读异常：**不吞错**，仓储/UI 按 [code] 决定提示与重试。
 *
 * 依据：` 「错误映射要可测」。
 */
class AttachmentException(
    val code: String,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(if (detail.isNullOrBlank()) code else "$code: $detail", cause)

/**
 * 本地附件文件仓库：唯一负责 `filesDir/attachments/` 的读写与清理
 * （v0.5.0 附件基础设施，`清空/启动清理见 ` 隐私承诺）。
 *
 * 目录与命名：
 * - 根目录 `filesDir/attachments/`（app 私有，`allowBackup=false`，不进系统备份）；
 * - 文件名 `<id>.<ext>`：**只用 id**，不用用户原始文件名（防路径穿越 / 隐私泄漏）；
 *   DB 里存的是相对路径 `attachments/<id>.<ext>`（[path] 负责安全解析回绝对路径）。
 *
 * 线程与 IO 纪律：
 * - 本类方法是**同步阻塞 IO**，没有内部线程切换；调用方必须保证不在主线程调用
 *   （`AttachmentRepository` 已统一包 `Dispatchers.IO`；`PulsezApp` 在 `appScope`；
 *   `ExportService.clearAll` 在 `withContext(Dispatchers.IO)`）。
 * - 写入先落 `<id>.<ext>.part`，成功后原子改名；超限/IO 失败都会删半成品，不留垃圾。
 *
 * 错误：所有失败抛 [AttachmentException]（带 [AttachmentErrorCodes] 码），不返回 null 吞掉。
 *
 * @param filesDir app 的 `Context.filesDir`（测试可传临时目录，不依赖 Android 框架）。
 */
class AttachmentStore(filesDir: File) {

    private val filesRoot: File = filesDir

    /** 附件根目录 `filesDir/attachments`（不保证已创建；[save] 会建）。 */
    val dir: File
        get() = File(filesRoot, DIR_NAME)

    /**
     * 把 DB 里的相对路径（`attachments/<id>.<ext>`）安全解析成绝对文件。
     *
     * 拒绝绝对路径、`..`、URL scheme，并校验 canonical 路径仍在 **`filesDir/attachments/`** 内
     * （纵深防御：localPath 目前只由 [save] 生成、属 DB 内部数据，但仍不透给 filesDir 其它子目录）——
     * 即便 DB/调用方传进脏数据，也拿不到附件目录以外的文件。
     *
     * 依据：` 建议 5。
     *
     * @throws AttachmentException `INVALID_ARGUMENT`（非法路径）
     */
    fun path(localPath: String): File {
        val normalized = localPath.trim().replace('\\', '/')
        if (normalized.isEmpty() ||
            normalized.startsWith("/") ||
            normalized.contains("..") ||
            normalized.contains("://")
        ) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "附件路径不合法")
        }
        val root = try {
            filesRoot.canonicalFile
        } catch (e: IOException) {
            throw AttachmentException(AttachmentErrorCodes.STORE_IO, "附件目录不可用", e)
        }
        val attachmentRoot = try {
            File(root, DIR_NAME).canonicalFile
        } catch (e: IOException) {
            throw AttachmentException(AttachmentErrorCodes.STORE_IO, "附件目录不可用", e)
        }
        val target = try {
            File(root, normalized).canonicalFile
        } catch (e: IOException) {
            throw AttachmentException(AttachmentErrorCodes.STORE_IO, "附件路径不可用", e)
        }
        // 前缀必须带末尾分隔符：否则 `/files/attachments_evil/...` 会被当成在 `/files/attachments` 内。
        val prefix = attachmentRoot.path + File.separator
        if (!target.path.startsWith(prefix)) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "附件路径越界")
        }
        return target
    }

    /** 附件是否还在本地（相对路径非法时也返回 false，由调用方按「没了」处理）。 */
    fun exists(localPath: String): Boolean = runCatching { path(localPath).isFile }.getOrDefault(false)

    /**
     * 本地文件字节数；文件不存在返回 0（不抛——调用方按「没了」处理，
     * 真正需要严格性时用 [path] + `File.length()`）。
     */
    fun sizeOf(localPath: String): Long {
        val file = try {
            path(localPath)
        } catch (e: AttachmentException) {
            return 0L
        }
        return if (file.isFile) file.length() else 0L
    }

    /**
     * 把 [stream] 写进附件目录，**边写边计数**，超过 [maxBytes] 立即中止。
     *
     * 语义：
     * - 成功：返回 DB 里应存的相对路径（`attachments/<id>[.<ext>]`），文件已完整落盘；
     * - 超限：抛 `TOO_LARGE`，**半成品（.part）已删**，不会留下超限文件；
     * - IO 失败：抛 `STORE_IO`，同样删半成品；
     * - **不关闭** [stream]，由调用方负责（与 `ImportCache.copyStreamLimited` 一致）；
     * - [id] 只允许字母/数字/`_`/`-`（防路径穿越；`Ids.new()` 的 UUID 天然满足）；
     * - [ext] 只允许小写字母/数字（由 mime 推导，不接受用户输入）。
     *
     * @param maxBytes 单文件上限，必须 > 0（缺省 10MB，`「单图 ≤10MB」）
     * @throws AttachmentException `INVALID_ARGUMENT` / `TOO_LARGE` / `STORE_IO`
     */
    fun save(
        stream: InputStream,
        id: String,
        ext: String,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): String {
        if (!ID_PATTERN.matches(id)) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "附件 id 不合法")
        }
        if (maxBytes <= 0L) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "附件上限必须大于 0")
        }
        val safeExt = ext.trim().lowercase()
        if (safeExt.isNotEmpty() && !EXT_PATTERN.matches(safeExt)) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "附件扩展名不合法")
        }
        val targetDir = ensureDir()
        val name = if (safeExt.isEmpty()) id else "$id.$safeExt"
        val target = File(targetDir, name)
        val part = File(targetDir, "$name.part")
        var total = 0L
        try {
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(COPY_CHUNK)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (total + read > maxBytes) {
                        throw AttachmentException(
                            AttachmentErrorCodes.TOO_LARGE,
                            "附件超过大小上限",
                        )
                    }
                    output.write(buffer, 0, read)
                    total += read
                }
                output.flush()
            }
            if (!part.renameTo(target)) {
                throw AttachmentException(AttachmentErrorCodes.STORE_IO, "附件落盘改名失败")
            }
            return "$DIR_NAME/$name"
        } catch (e: AttachmentException) {
            deleteQuietly(part)
            throw e
        } catch (e: IOException) {
            deleteQuietly(part)
            throw AttachmentException(AttachmentErrorCodes.STORE_IO, "写附件失败", e)
        }
    }

    /**
     * 删除一个附件文件。
     *
     * @return true = 这次真的删掉了；false = 文件本来就不在（幂等）。
     * @throws AttachmentException `INVALID_ARGUMENT`（脏路径）/ `STORE_IO`（文件在但删不掉）
     */
    fun delete(localPath: String): Boolean {
        val file = path(localPath)
        if (!file.exists()) return false
        if (!file.delete()) {
            throw AttachmentException(AttachmentErrorCodes.STORE_IO, "删附件失败")
        }
        return true
    }

    /**
     * 清空整个附件目录（用户「清空所有数据」/仓储层 `clearAll` 用）。
     *
     * 只删文件（本类从不建子目录；真出现子目录一并删，避免隐私残留）。
     *
     * @return 删除的文件/目录项个数
     * @throws AttachmentException `STORE_IO`（有文件删不掉；此时已删的保持已删，可重试）
     */
    fun clearAll(): Int {
        val targetDir = dir
        if (!targetDir.isDirectory) return 0
        val children = targetDir.listFiles()
            ?: throw AttachmentException(AttachmentErrorCodes.STORE_IO, "读不了附件目录")
        var deleted = 0
        var failed: File? = null
        for (child in children) {
            val ok = if (child.isDirectory) child.deleteRecursively() else child.delete()
            if (ok) {
                deleted++
            } else {
                failed = child
            }
        }
        if (failed != null) {
            throw AttachmentException(AttachmentErrorCodes.STORE_IO, "有附件删不掉")
        }
        return deleted
    }

    /**
     * 回收孤儿文件：删掉「文件名对应的 id 不在 [keepIds] 里」的文件，以及所有 `.part` 半成品。
     *
     * 用于：启动时对账（`PulsezApp`，DB 里 `deletedAt IS NULL` 的 id 是 keep 集合）与
     * 仓储层 `cleanupOrphans`。文件名的 id 部分按 `<id>[.ext]` 反推（id 本身不含 `.`）。
     *
     * 注意（v0.5.0 `）：keep 集合是**活行**，所以 DRAFT/FAILED 活行
     * 对应的文件在这里永远不算孤儿；陈旧草稿/失败态由 `AttachmentRepository.sweepStaleDrafts`
     * 先按 TTL 软删（并从活行集合里移除），本方法才能回收它们的文件。
     *
     * best-effort：单个文件删不掉不抛（下次启动再试），避免启动清理把 App 弄崩。
     *
     * **并发兜底（v0.5.0 决策 `）**：跳过最近
     * [CLEANUP_RECENT_GUARD_MS]（10 分钟）内修改过的文件（含 `.part`）。
     * 写入时序是「先落盘、后写 DB」（`AttachmentRepository.createDraft`），若启动清理恰好与
     * 保存并发，刚写入、DB 行尚未提交的文件（或正在写的 `.part`）会被当成孤儿删掉；
     * 10 分钟窗口保证"正在保存/刚保存完"的文件一定不会被这次清理误删，
     * 真正崩溃残留的垃圾留到下次启动再回收。 path 收紧语义不受影响。
     *
     * @param keepIds 仍在使用的附件 id（`AttachmentDao.idsInUse()`）
     * @param now 判定"最近修改"的当前时间（毫秒；测试可注入）
     * @param recentGuardMs 近期文件保护窗口；<=0 表示**不保护**（旧行为，测试/维护动作可显式关闭）
     * @return 实际删掉的个数
     */
    fun cleanupOrphans(
        keepIds: Set<String>,
        now: Long = System.currentTimeMillis(),
        recentGuardMs: Long = CLEANUP_RECENT_GUARD_MS,
    ): Int {
        val targetDir = dir
        if (!targetDir.isDirectory) return 0
        val children = targetDir.listFiles() ?: return 0
        // 可空表达：null = 显式关闭保护（此时**不**做任何"近期"跳过）。
        // 不能用 `Long.MIN_VALUE` 兜底：任何真实 mtime 都大于它，会把"关闭保护"反变成"全部跳过"。
        val recentCutoff: Long? = if (recentGuardMs > 0) now - recentGuardMs else null
        var deleted = 0
        for (child in children) {
            if (!child.isFile) continue
            // 近期修改的文件一律跳过（含 .part）：可能正在写、或刚写完还没写进 DB。
            // 边界语义：只有 `mtime > cutoff`（距今**不足** recentGuardMs）才跳过；
            // mtime 恰好等于 cutoff（刚好满窗口）按旧文件处理——毫秒级文件时间下理论边界，不影响默认路径。
            if (recentCutoff != null && child.lastModified() > recentCutoff) continue
            val name = child.name
            val isPart = name.endsWith(PART_SUFFIX)
            val baseName = if (isPart) name.removeSuffix(PART_SUFFIX) else name
            val id = baseName.substringBeforeLast('.', baseName)
            if (isPart || id !in keepIds) {
                if (child.delete()) deleted++
            }
        }
        return deleted
    }

    // ---------------------------------------------------------------- 内部

    private fun ensureDir(): File {
        val targetDir = dir
        if (targetDir.isDirectory) return targetDir
        if (!targetDir.mkdirs()) {
            throw AttachmentException(AttachmentErrorCodes.STORE_IO, "建不了附件目录")
        }
        return targetDir
    }

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    companion object {
        /** `filesDir` 下的附件目录名。 */
        const val DIR_NAME = "attachments"

        /** 单附件上限 10MB（`「单图 ≤10MB」）。*/
        const val DEFAULT_MAX_BYTES: Long = 10L * 1024 * 1024

        /** 拷贝块 64 KiB（与 `ImportCache` 同口径）。 */
        private const val COPY_CHUNK = 64 * 1024

        /**
         * 孤儿清理的"近期文件保护窗口"：10 分钟（v0.5.0 决策）。
         *
         * 写入是"先落盘、后写 DB"，启动清理与保存并发时，刚写的文件/`.part` 可能 DB 里还没有行。
         * 10 分钟远大于一次图片压缩+落盘+写库的耗时，足够避开竞态；残留垃圾下次启动再清。
         * 依据：` 。
         */
        const val CLEANUP_RECENT_GUARD_MS: Long = 10 * 60 * 1000L

        private const val PART_SUFFIX = ".part"

        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val EXT_PATTERN = Regex("[a-z0-9]{1,8}")

        /**
         * 进程级实例（`PulsezApp.onCreate` 安装）。
         *
         * 为什么需要：`ExportService.clearAll(container)` 的签名只拿得到 [cc.pulsez.app.AppContainer]，
         * 而 `AppContainer` 不暴露 Context/filesDir（本写作用域不含 `AppContainer.kt`，
         * 不越界改它）。所以由 `PulsezApp` 在进程启动时把「按 `filesDir` 建好的实例」装到这里，
         * `ExportService` 清空时取用；测试里显式 [install] 自己临时目录的实例。
         */
        @Volatile
        private var processStore: AttachmentStore? = null

        /** 按 `Context.filesDir` 建实例（生产入口在 `PulsezApp`）。 */
        fun forContext(context: Context): AttachmentStore = AttachmentStore(context.filesDir)

        /** 安装进程级实例（`PulsezApp.onCreate` 调用；测试也用它注入临时目录）。 */
        fun install(store: AttachmentStore) {
            processStore = store
        }

        /**
         * **测试专用**：清掉进程级实例，恢复「未安装（null）」状态，供测试 `@After` 复原。
         *
         * 为什么需要：进程级单例在 JVM 内跨测试存活，测试装了临时目录的实例后若不复原，同一
         * sandbox 的后续测试可能拿到指向已删除临时目录的 store（生产无影响，纯测试卫生）。
         * **生产代码不得调用**。依据：` 建议 6。
         */
        fun resetForTest() {
            processStore = null
        }

        /** 取进程级实例；没装返回 null（调用方决定是否降级）。 */
        fun installedOrNull(): AttachmentStore? = processStore

        /**
         * 取进程级实例；没有就抛可读异常。
         *
         * 生产路径上 `PulsezApp.onCreate` 一定先于任何 UI 执行，所以这里失败代表
         * 初始化被绕过——宁可显式报错，也不静默跳过「清空附件」造成隐私残留。
         */
        fun required(): AttachmentStore = processStore
            ?: throw AttachmentException(
                AttachmentErrorCodes.STORE_NOT_INSTALLED,
                "附件目录还没准备好（PulsezApp.onCreate 应安装 AttachmentStore）",
            )
    }
}
