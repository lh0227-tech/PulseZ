package cc.pulsez.app.data.repo

import androidx.room.withTransaction
import cc.pulsez.app.data.attachments.AttachmentErrorCodes
import cc.pulsez.app.data.attachments.AttachmentException
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.util.Ids
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 附件仓储：把「文件落盘」（[AttachmentStore]）与「DB 元数据」（`attachment` 表）绑成一套
 * 可测、错误不吞的 API（v0.5.0）。
 *
 * 分工与不变量：
 * - **文件先落盘、DB 行后写**：DB 写失败会把刚落的文件删掉（避免「库里有行、盘上没文件」）；
 * - **软删只标记 DB**：`softDeleteForMessage` 返回被软删行的 `localPath`，文件由调用方
 *   `store.delete(...)` 删除，或交给启动/仓储层的孤儿清理回收——不在 DB 事务里做文件 IO；
 * - **清空**：`clearAll(store)` 先软删所有行、再清目录；目录清不掉会抛 `STORE_IO`（不静默）；
 * - **孤儿清理**：DB 里 `deletedAt IS NULL` 的 id 是 keep 集合，其余文件（含 `.part`）删掉；
 *   **陈旧草稿/失败态/未绑定 READY**（删草稿、绑定失败、选图后进程被杀等留下的活行）由
 *   [sweepStaleDrafts] 按 24h TTL 先软删，再回收文件（v0.5.0 `
 *   扩面 ` ）；
 * - 所有失败都抛 [AttachmentException]（带 [AttachmentErrorCodes] 码），不返回 null 吞掉。
 *
 * 线程：本类是 `suspend`，文件 IO 都在 `Dispatchers.IO`；Room 的 suspend DAO 自己调度。
 */
class AttachmentRepository(private val db: PulsezDatabase) {

    private val remoteFileCleanup = RemoteFileCleanupRepository(db)

    /**
     * 从流建草稿附件：文件落盘 → 写 `status=DRAFT、messageId=null` 的行。
     *
     * @param kind [AttachmentKind] 之一（v0.5.0 只有 IMAGE）
     * @param mime 如 `image/jpeg`；只用于记录与推导扩展名，**不信任其内容**
     * @param stream 调用方负责关闭（本方法不关闭）
     * @param fileName 用户可见的原始文件名（仅展示，不参与落盘路径）
     * @param maxBytes 单文件上限（默认 10MB；测试可注入小上限验证拒绝路径）
     * @param id 附件 id（默认生成 UUID；测试可指定以便断言失败后无残留）
     * @throws AttachmentException `INVALID_ARGUMENT` / `TOO_LARGE` / `STORE_IO` / `DB`
     */
    suspend fun createDraft(
        store: AttachmentStore,
        kind: String,
        mime: String,
        stream: InputStream,
        fileName: String? = null,
        width: Int? = null,
        height: Int? = null,
        maxBytes: Long = AttachmentStore.DEFAULT_MAX_BYTES,
        id: String = Ids.new(),
        createdAt: Long = System.currentTimeMillis(),
        ownerThreadId: String? = null,
    ): AttachmentEntity {
        requireDraftArgs(kind = kind, mime = mime, width = width, height = height)
        val ext = extForMime(mime)
        val localPath = withContext(Dispatchers.IO) {
            store.save(stream, id, ext, maxBytes)
        }
        val entity = AttachmentEntity(
            id = id,
            messageId = null,
            kind = kind,
            mime = mime,
            localPath = localPath,
            fileName = fileName?.trim()?.takeIf { it.isNotEmpty() },
            sizeBytes = withContext(Dispatchers.IO) { store.sizeOf(localPath) },
            width = width,
            height = height,
            status = AttachmentStatus.DRAFT,
            errorKind = null,
            attempts = 0,
            remoteFileId = null,
            expiresAt = null,
            deletedAt = null,
            createdAt = createdAt,
            ownerThreadId = ownerThreadId,
        )
        try {
            db.attachmentDao().upsert(entity)
        } catch (t: Throwable) {
            // 库写不进去就把文件删掉，别留下「盘上有、库里没有」的孤儿。
            withContext(Dispatchers.IO) { runCatching { store.delete(localPath) } }
            throw AttachmentException(AttachmentErrorCodes.DB, "附件记录写入失败", t)
        }
        return entity
    }

    /**
     * 从文件流建通用文件草稿（`kind = AttachmentKind.FILE`，跳过图片宽高校验）。
     */
    suspend fun createFileDraft(
        store: AttachmentStore,
        mime: String,
        stream: InputStream,
        fileName: String?,
        maxBytes: Long = DEFAULT_FILE_MAX_BYTES,
        id: String = Ids.new(),
        createdAt: Long = System.currentTimeMillis(),
        ownerThreadId: String? = null,
    ): AttachmentEntity {
        requireDraftArgs(kind = AttachmentKind.FILE, mime = mime, width = null, height = null)
        val ext = extForFile(fileName, mime)
        val localPath = withContext(Dispatchers.IO) {
            store.save(stream, id, ext, maxBytes)
        }
        val entity = AttachmentEntity(
            id = id,
            messageId = null,
            kind = AttachmentKind.FILE,
            mime = mime,
            localPath = localPath,
            fileName = fileName?.trim()?.takeIf { it.isNotEmpty() },
            sizeBytes = withContext(Dispatchers.IO) { store.sizeOf(localPath) },
            width = null,
            height = null,
            status = AttachmentStatus.DRAFT,
            errorKind = null,
            attempts = 0,
            remoteFileId = null,
            expiresAt = null,
            deletedAt = null,
            createdAt = createdAt,
            ownerThreadId = ownerThreadId,
        )
        try {
            db.attachmentDao().upsert(entity)
        } catch (t: Throwable) {
            withContext(Dispatchers.IO) { runCatching { store.delete(localPath) } }
            throw AttachmentException(AttachmentErrorCodes.DB, "附件记录写入失败", t)
        }
        return entity
    }

    /** 回写服务端文件句柄与过期时间。 */
    suspend fun setRemoteFile(
        id: String,
        remoteFileId: String?,
        expiresAt: Long?,
        remoteCredentialBindingId: String? = null,
    ): Int {
        if (id.isBlank()) return 0
        return db.attachmentDao().updateRemoteFile(id, remoteFileId, expiresAt, remoteCredentialBindingId)
    }

    /**
     * 字节数组版 [createDraft]（图片压缩流水线内存里已有 JPEG 字节时用）。
     *
     * 注意：字节数组会整块进内存，大文件请用流版本。
     */
    suspend fun createDraft(
        store: AttachmentStore,
        kind: String,
        mime: String,
        bytes: ByteArray,
        fileName: String? = null,
        width: Int? = null,
        height: Int? = null,
        maxBytes: Long = AttachmentStore.DEFAULT_MAX_BYTES,
        id: String = Ids.new(),
        createdAt: Long = System.currentTimeMillis(),
        ownerThreadId: String? = null,
    ): AttachmentEntity = createDraft(
        store = store,
        kind = kind,
        mime = mime,
        stream = ByteArrayInputStream(bytes),
        fileName = fileName,
        width = width,
        height = height,
        maxBytes = maxBytes,
        id = id,
        createdAt = createdAt,
        ownerThreadId = ownerThreadId,
    )

    /**
     * 草稿 → READY（本地文件已备好、可发送）。
     *
     * @throws AttachmentException `NOT_FOUND`（附件不存在或已软删）
     */
    suspend fun markReady(id: String): AttachmentEntity {
        val updated = db.attachmentDao().updateStatus(id, AttachmentStatus.READY, null)
        if (updated == 0) {
            throw AttachmentException(AttachmentErrorCodes.NOT_FOUND, "附件不存在或已删除")
        }
        return db.attachmentDao().byId(id)
            ?: throw AttachmentException(AttachmentErrorCodes.NOT_FOUND, "附件不存在或已删除")
    }

    /**
     * 把一批草稿绑定到刚落的用户消息（`messageId`），整个事务：任一 id 不存在/已删则全部回滚。
     *
     * v0.5.0 绑定成功同时把状态推进到 [AttachmentStatus.SENT]（这一轮已经发出去了）。
     * 状态推进放在同一事务里：UI 的"图片已发送/还能重发"与 DB 不会出现"绑定了但还是 DRAFT"。
     *
     * @return 绑定后的附件行（顺序与 [ids] 去重后一致）
     * @throws AttachmentException `INVALID_ARGUMENT`（空列表/空 messageId）/ `NOT_FOUND`
     */
    suspend fun bindToMessage(ids: List<String>, messageId: String): List<AttachmentEntity> {
        if (ids.isEmpty()) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "没有要绑定的附件")
        }
        if (messageId.isBlank()) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "消息 id 为空")
        }
        return db.withTransaction {
            ids.distinct().map { id ->
                val updated = db.attachmentDao().bindToMessage(id, messageId)
                if (updated == 0) {
                    // 抛出去让 withTransaction 回滚：不允许「一半绑定成功」。
                    throw AttachmentException(AttachmentErrorCodes.NOT_FOUND, "附件不存在或已删除：$id")
                }
                db.attachmentDao().updateStatus(id, AttachmentStatus.SENT, null)
                db.attachmentDao().byId(id)
                    ?: throw AttachmentException(AttachmentErrorCodes.NOT_FOUND, "附件不存在或已删除：$id")
            }
        }
    }

    /**
     * 丢弃一个还没发送的草稿（v0.5.0 草稿行上点删除/导入失败回滚用）。
     *
     * 顺序：先软删 DB（即使文件删失败，也不会再被当成"在用"），再删文件；
     * 文件删不掉不抛（下次启动孤儿清理会回收）。
     *
     * @return true = 这次真的软删了行；false = 行不存在/已删（幂等）
     */
    suspend fun discardDraft(id: String, store: AttachmentStore): Boolean {
        if (id.isBlank()) return false
        val row = db.withTransaction {
            val existing = db.attachmentDao().byId(id) ?: return@withTransaction null
            val updated = db.attachmentDao().softDeleteById(id, System.currentTimeMillis())
            if (updated == 0) return@withTransaction null
            remoteFileCleanup.enqueueInCurrentTransaction(
                existing.remoteFileId,
                existing.remoteCredentialBindingId,
            )
            existing
        } ?: return false
        withContext(Dispatchers.IO) { runCatching { store.delete(row.localPath) } }
        return true
    }

    /** 某条消息当前的附件（未软删、时间正序）。 */
    suspend fun listForMessage(messageId: String): List<AttachmentEntity> {
        if (messageId.isBlank()) return emptyList()
        return db.attachmentDao().byMessageId(messageId)
    }

    /**
     * 消息删除时软删它的全部附件（` 规则 6「删除要传播」）。
     *
     * 只改 DB 标记；返回被软删行的 `localPath`，调用方按需删文件（或留给孤儿清理）。
     *
     * @return 被软删附件的 localPath（用于调用方删文件）
     */
    suspend fun softDeleteForMessage(
        messageId: String,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        if (messageId.isBlank()) return emptyList()
        return db.withTransaction {
            val rows = db.attachmentDao().byMessageId(messageId)
            if (rows.isNotEmpty() && db.attachmentDao().softDeleteByMessageId(messageId, now) > 0) {
                rows.forEach { row ->
                    remoteFileCleanup.enqueueInCurrentTransaction(
                        row.remoteFileId,
                        row.remoteCredentialBindingId,
                    )
                }
            }
            rows.map { it.localPath }
        }
    }

    /**
     * 清空附件：DB 全部软删 + 附件目录清空（用户「清空所有数据」时用；`ExportService.clearAll`
     * 直接调 [AttachmentStore.clearAll]，本方法给仓储层单测/以后 UI 用）。
     *
     * 顺序：先软删 DB（即使随后文件删失败，也不会再被当成「在用」），再清目录；
     * 目录清失败会抛 `STORE_IO`，不静默。
     *
     * @return 被软删的行数
     */
    suspend fun clearAll(store: AttachmentStore): Int {
        val now = System.currentTimeMillis()
        val softDeleted = db.withTransaction {
            val remoteFiles = db.attachmentDao().liveRemoteFileReferences()
            val count = db.attachmentDao().softDeleteAll(now)
            if (count > 0) {
                remoteFiles.forEach { row ->
                    remoteFileCleanup.enqueueInCurrentTransaction(
                        row.remoteFileId,
                        row.remoteCredentialBindingId,
                    )
                }
            }
            count
        }
        withContext(Dispatchers.IO) { store.clearAll() }
        return softDeleted
    }

    /**
     * 孤儿文件清理：删掉本地不存在对应「未软删附件行」的文件（含半成品 `.part`）。
     *
     * 启动时由 `PulsezApp` 在后台调用（失败静默，不影响启动），也可由维护动作手动跑。
     *
     * 注意：keep 集合是 `deletedAt IS NULL` 的**活行**，所以它本身回收不了「用户删掉草稿 /
     * 绑定失败 / 选图后进程被杀」留下的未绑定活行；那类行由 [sweepStaleDrafts] 先按 TTL 软删
     * 。启动维护里两者要一起跑。
     *
     * @return 删掉的文件数
     */
    suspend fun cleanupOrphans(store: AttachmentStore): Int {
        val keepIds = db.attachmentDao().idsInUse().toSet()
        return withContext(Dispatchers.IO) { store.cleanupOrphans(keepIds) }
    }

    /**
     * 回收「陈旧且未绑定」的草稿/失败态/READY 附件
     * （v0.5.0 ` 扩面，`）。
     *
     * 为什么需要：删草稿、绑定失败、导入中删除、选图后没发就离开/进程被杀等路径留下的行在
     * DB 里是**活行**（`deletedAt` 为 null），启动孤儿文件清理的 keep 集合包含它们 →
     * 文件永远不会被回收（永久孤儿）。这条 TTL 兜底把「超过 [STALE_DRAFT_TTL_MS] 还没绑定到
     * 任何消息」的 DRAFT、FAILED、READY 行软删，并顺手删文件；下一轮（或同一轮后面的）
     * [cleanupOrphans] 也能兜住删失败的文件。
     *
     * **为什么必须包含 READY（依据：`）**：生产代码从不写
     * `AttachmentStatus.FAILED`（全仓只有读取过滤），绑定失败时 [bindToMessage] 的整批事务回滚，
     * DB 行仍是 READY；「选图成功即 READY、没发就进程被杀」同样留下 READY 未绑定行。
     * 旧条件（只认 DRAFT/FAILED）永远扫不到它们 → 永久 DB 行 + 永久文件。
     *
     * 安全性（不误删）：
     * - 只碰 `messageId IS NULL`、`status IN (DRAFT, FAILED, READY)`、`createdAt` 早于 cutoff
     *   的行（DAO 查询与软删 UPDATE 都带同一组条件；UPDATE 带条件可避免"查询后被绑定"的竞态）；
     * - 近期草稿一律保留：用户可能正在选图/打字，或导入流水线刚写完；24h TTL 远大于正常
     *   选图→发送间隔；
     * - 本方法只在**启动**维护（`PulsezApp.onCreate`）调用；草稿只存在于内存、跨进程不恢复
     *   （`ChatViewModel.load()` 不恢复草稿、`listDraft()` 无生产调用），所以启动时刻不存在
     *   「马上就要发的 READY 内存草稿」；
     * - **不删** SENT/SENDING 与已绑定的行（`messageId IS NULL` 已排除已绑定；READY 必须未绑定才清）。
     *
     * 线程：软删走 Room，删文件在 `Dispatchers.IO`；文件删失败不抛（best-effort，下次启动再试）。
     * 不打印路径/正文（隐私纪律）。
     *
     * @param store 附件文件仓库
     * @param now 当前时间（毫秒；测试可注入）
     * @param ttlMs TTL；<=0 表示**关闭**本次回收（不删任何行，测试/维护动作可显式关闭）
     * @return 本次软删并回收的行数
     */
    suspend fun sweepStaleDrafts(
        store: AttachmentStore,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = STALE_DRAFT_TTL_MS,
    ): Int {
        if (ttlMs <= 0L) return 0
        val cutoff = now - ttlMs
        val candidates = db.attachmentDao().staleUnboundDrafts(cutoff)
        if (candidates.isEmpty()) return 0
        val softDeleted = db.withTransaction {
            buildList {
                candidates.forEach { row ->
                    val current = db.attachmentDao().byId(row.id) ?: return@forEach
                    val updated = db.attachmentDao().softDeleteStaleUnboundDraft(row.id, now, cutoff)
                    if (updated > 0) {
                        remoteFileCleanup.enqueueInCurrentTransaction(
                            current.remoteFileId,
                            current.remoteCredentialBindingId,
                        )
                        add(current.localPath)
                    }
                }
            }
        }
        if (softDeleted.isEmpty()) return 0
        withContext(Dispatchers.IO) {
            softDeleted.forEach { localPath -> runCatching { store.delete(localPath) } }
        }
        return softDeleted.size
    }

    companion object {
        /**
         * 陈旧草稿/失败态/未绑定 READY 附件的回收 TTL：24 小时
         * （v0.5.0 ` 扩面，`）。
         *
         * 取值依据：草稿只存在于当前页面（不跨进程恢复），一天足够覆盖"选中图后离开/杀进程/
         * 绑定失败"等各种停顿；再久就是既不再被引用、也不可能被用户恢复的垃圾。
         * 与 `ImagePipeline.TEMP_TTL_MS` / `PulsezApp` 的导入残留清理同为 24h，口径一致。
         */
        const val STALE_DRAFT_TTL_MS: Long = 24 * 60 * 60 * 1000L

        /** 文档文件本地草稿默认体积上限：100 MiB（与服务端 document_parsing 上限对齐）。 */
        const val DEFAULT_FILE_MAX_BYTES: Long = 100L * 1024 * 1024

        private val FILE_EXT_PATTERN = Regex("[a-z0-9]{1,8}")
    }

    // ---------------------------------------------------------------- 内部

    private fun requireDraftArgs(kind: String, mime: String, width: Int?, height: Int?) {
        if (kind.isBlank()) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "附件类型为空")
        }
        if (mime.isBlank()) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "附件 mime 为空")
        }
        if ((width != null && width <= 0) || (height != null && height <= 0)) {
            throw AttachmentException(AttachmentErrorCodes.INVALID_ARGUMENT, "附件尺寸不合法")
        }
    }

    private fun extForFile(fileName: String?, mime: String): String {
        val fromName = fileName?.trim()
            ?.takeIf { '.' in it }
            ?.substringAfterLast('.')
            ?.lowercase()
            ?.takeIf { FILE_EXT_PATTERN.matches(it) }
        return fromName ?: extForMime(mime)
    }

    /**
     * 由 mime 推导落盘扩展名（**不接受用户输入**，防止路径穿越）。
     *
     * 未知 mime 落 `bin`：v0.5.0 只有 IMAGE，调用方应先做能力门禁；这里只保证文件名安全。
     */
    private fun extForMime(mime: String): String =
        when (mime.lowercase().substringBefore(';').trim()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            "image/gif" -> "gif"
            else -> "bin"
        }
}
