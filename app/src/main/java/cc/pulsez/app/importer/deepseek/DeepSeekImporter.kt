package cc.pulsez.app.importer.deepseek

import cc.pulsez.app.core.ConsolidatePlan
import cc.pulsez.app.core.ConsolidateRange
import cc.pulsez.app.core.ConsolidateResult
import cc.pulsez.app.core.ImportIndexCheckpointReadException
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.core.ImportMemoryConsolidator
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.ImportException
import cc.pulsez.app.data.repo.ImportBatchGroupPlan
import cc.pulsez.app.data.repo.ImportBatchPlan
import cc.pulsez.app.data.repo.ImportBatchResult
import cc.pulsez.app.data.repo.ImportBatchGroupResult
import cc.pulsez.app.data.repo.ImportBatchWindowPlan
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.repo.ImportSummary
import cc.pulsez.app.data.repo.ImportTarget
import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.data.settings.ImportPrefs
import cc.pulsez.app.data.settings.isFullyConsolidated
import cc.pulsez.app.ui.importer.ImportRunner
import cc.pulsez.app.ui.importer.BatchImportRunner
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 导入编排里不在 `ZipErrorCodes` 内的原因码（` 错误码表 / §2）。
 *
 * UI/Store 只按码给「人话」，不展示正文。
 */
object ImportErrorCodes {

    /** 压缩包里一个 `chat_sessions/<id>.json` 都没有（§5 `NO_SESSIONS`）。 */
    const val NO_SESSIONS: String = "NO_SESSIONS"

    /** 用户主动停止（§5 `CANCELLED`）。 */
    const val CANCELLED: String = "CANCELLED"

    /** 单会话里出现未归类异常（已跳过该会话，继续导入其它会话）。 */
    const val IMPORT_FAILED: String = "IMPORT_FAILED"
}

/**
 * 预检粗估结果（纯函数产物）。
 *
 * @property shards 按声明大小估算的分片数（40k token/片）
 * @property estimatedYuan 按分片数 × ¥0.096/片 的粗估（不是账单）
 */
internal data class ConsolidationEstimate(
    val shards: Int,
    val estimatedYuan: Double,
)

/**
 * 确认屏的费用粗估（`「粗估」行、§2）：
 * 只读中央目录的声明大小，`tokens ≈ Σ字节 / 4`，`分片 = ⌈tokens / 40000⌉`，`¥0.096/片`。
 *
 * 语义：
 * - **任何一条声明大小未知（`< 0`）→ 整体返回 null**（UI 显示「未知」，不编数字）；
 * - 空列表返回 null（没有可估的内容）；
 * - 声明大小为 0 的条目按 0 计，不凑 1 片。
 *
 * 尺寸未知时宁可说「未知」也不猜——（禁止猜）。
 */
internal fun estimateFromDeclaredSizes(declaredSizes: List<Long>): ConsolidationEstimate? {
    if (declaredSizes.isEmpty()) return null
    if (declaredSizes.any { it < 0 }) return null
    val totalBytes = declaredSizes.sum()
    val tokens = totalBytes.toDouble() / DeepSeekImporter.BYTES_PER_TOKEN
    val shards = ceil(tokens / DeepSeekImporter.SHARD_TOKENS.toDouble()).toInt().coerceAtLeast(0)
    return ConsolidationEstimate(
        shards = shards,
        estimatedYuan = shards * DeepSeekImporter.YUAN_PER_SHARD,
    )
}

/**
 * DeepSeek v3 导出 ZIP 的导入编排：预检、逐会话解析/落库、重建索引、可选记忆整理。
 *
 * 分层：**不碰 UI / Compose / Context**。ZIP 读取交给 [DeepSeekZip]、解析交给
 * [DeepSeekParser]、落库交给 [ImportRepository]、索引/记忆整理交给 的
 * [ImportIndexer] / [ImportMemoryConsolidator]；本类只负责顺序、进度、取消、错误码与收尾。
 *
 * ## 关键设计
 * 1. **逐会话入库**：一个会话一次 `repo.importSessions(listOf(session))`，避免把整包会话同时读进
 *    内存（`「阶段 2」、` 内存策略）；第一个会话用调用方传入的
 *    `resumeCharacterId`（续导），之后用上一次返回的 `characterId`。
 * 2. **会话级失败不阻断**：解析失败 / 单条损坏记 issue + `sessionsFailed++` 继续（`
 *    §5.3「单段损坏：已跳过，其它照常导入」、` 三类计数）。
 *    只有「累计读取超限」这种归档级错误才向上抛（` 归档级错误中止）。
 * 3. **索引按 DB 重建段**：续导时 `summary.segments` 为空，但库里已有 IMPORT 段；收尾统一查
 *    `segmentDao.forThread(threadId).filter { batonReason == "IMPORT" }` 重建，保证「继续上次导入」
 *    后索引仍然完整（` chunk 确定性 ID，重复索引幂等）。
 * 4. **记忆整理先算计划再跑**：`estimatedYuan > ¥1`（或调用方额外规则）时返回 `consentPending`，
 *    此时**已经完成索引、但没花整理费用**；上下文保存在 [pendingConsolidation]，由
 *    [resumeConsolidation] 在用户确认后继续（` §3 阶段 2）。
 *    「最近 90 天」范围内 0 片时自动回退「全部」重算（建议）；
 *    回退后仍 0 片返回 [ImportOutcome.consolidation] = `shardsTotal == 0` 的结果（不标记、不花钱）。
 * 5. **不删除 ZIP**：cache 清理是 UI/任务层的职责（`「finally：删除 cache ZIP」）。
 * 6. **导入历史也建向量（v0.4.1）**：用户同意（[embedConsent]）且「找旧事」配置齐全时，
 *    索引阶段按批（≤16）给导入 chunk 建向量，失败重试 2 次、仍失败跳过该批（关键词仍可召回），
 *    不阻断导入；未配置/用户关掉时保持 v0.4.0 的关键词-only。
 *
 * @param database 可选：用于「从 DB 重建 IMPORT 段」与测试。为 null 时退化为只用本次
 *   `summary.segments`（续导且没有新段时索引会拿不到旧段——生产必须传，见 [AppContainer]）。
 *
 * 实现 [ImportRunner]（v0.4 建议）：只为让 `ImportTaskStore` 的状态机可注入 fake 做
 * 离线测试；接口定义在任务层 `ui/importer/ImportTaskStore.kt`，方法签名与本类逐字一致，
 * 业务语义、回调与可空性完全不变。
 */
class DeepSeekImporter(
    private val repo: ImportRepository,
    /** 导入偏好：读整理断点、写「已整理」标记（` 不重复收费 / §6 续跑）。*/
    private val prefs: ImportPrefs,
    private val indexer: ImportIndexer,
    private val consolidator: ImportMemoryConsolidator,
    private val database: PulsezDatabase? = null,
    /**
     * 用户是否同意「顺便让 TA 以后能按意思找到这些旧事」（导入确认屏复选框，默认开、可关）。
     *
     * 只表达**用户意愿**：真正是否联网还由 `ImportIndexer.embeddingReady()` 决定
     * （「找旧事」服务没配好时一次都不调用）；默认 `{ false }` = 保持 v0.4.0 行为
     * （只建关键词索引），老调用方/测试不用改。
     */
    private val embedConsent: () -> Boolean = { false },
    /** Test seam for verifying that a failed status write still closes the batch as resumable PARTIAL. */
    private val batchSourceIndexStatusWriter: suspend (batchId: String, segmentId: String, status: String) -> Boolean =
        { batchId, segmentId, status -> repo.setBatchSourceIndexStatus(batchId, segmentId, status) },
) : ImportRunner, BatchImportRunner {

    /**
     * 是否正在为导入的旧事建向量。
     *
     * 导入页据此把 `Running(INDEX)` 的阶段文案从「正在整理」换成
     * 「正在让 TA 以后能按意思找到这些旧事…」——这是**另一件花钱的事**（用用户的找旧事服务），
     * 不能和「整理成记忆」混成一句话（` 的知情口径）。
     *
     * 状态机 `ImportTaskStore` 不在 写作用域，所以不新增 phase；用这个窄信号补充语义。
     */
    private val _semanticIndexing = MutableStateFlow(false)
    val semanticIndexing: StateFlow<Boolean> = _semanticIndexing.asStateFlow()

    /**
     * 预检结果（` 阶段 1、§4.7）。只读中央目录，不 inflate 会话。
     *
     * @property sessions `chat_sessions/` 下的会话文件条数（`<id>.json`）
     * @property timeRange 索引里的最小/最大时间（无索引或解析不出为 null）
     * @property zipSize 文件实际字节数
     * @property fingerprint `"$zipName:$zipSize"`，续导识别用（§6）
     * @property estimatedYuan 整理费用粗估（声明大小未知为 null）
     * @property estimatedShards 整理分片数粗估（声明大小未知为 null）
     */
    data class Inspect(
        val sessions: Int,
        val timeRange: LongRange?,
        val zipSize: Long,
        val fingerprint: String,
        val estimatedYuan: Double?,
        val estimatedShards: Int?,
        val sourceWindows: List<DeepSeekSessionPreview> = emptyList(),
        /** Content hash used to resume a persisted grouping plan without trusting filename/size alone. */
        val contentFingerprint: String = fingerprint,
    )

    /** 导入进度：已处理会话数 / 总会话数 / 累计新写入消息数。 */
    data class Progress(val done: Int, val total: Int, val messages: Int)

    /**
     * 一次导入的结果。
     *
     * @property summary 三类计数汇总（已导入 / 之前导过 / 读不出来）
     * @property indexed 本地索引是否成功（失败不阻断导入）
     * @property consolidation 本次真正跑完的整理结果（跳过/待确认/取消为 null）；
     *   `shardsTotal == 0` 表示两个范围都没有可整理的内容（[NOTHING_TO_CONSOLIDATE]）
     * @property consolidationPlan 解析完成后的精确整理计划（供确认屏/续跑；回退时是 ALL 的计划）
     * @property consentPending true = 计划超过阈值，需要 UI 确认；此时**尚未花整理费用**，
     *   调用方确认后走 [resumeConsolidation]（上下文见 [pendingConsolidation]）
     */
    data class ImportOutcome(
        val summary: ImportSummary,
        val indexed: Boolean,
        val consolidation: ConsolidateResult?,
        val consolidationPlan: ConsolidatePlan?,
        val consentPending: Boolean,
        val batch: ImportBatchResult? = null,
    )

    /**
     * `consentPending` 时保存的续跑上下文（`确认后继续整理）。
     *
     * 进程内对象（`ImportTaskStore` 是进程级单例）；进程被杀后不恢复——重开页面可从
     * `ImportPrefs.consolidation` 的进度重新整理（`「整理续跑」）。
     */
    data class PendingConsolidation(
        val threadId: String,
        val segments: List<ImportedSegment>,
        val range: ConsolidateRange,
        val plan: ConsolidatePlan,
    )

    /** 待用户确认的整理上下文；没有则 null。写入后由 [resumeConsolidation] 消费。 */
    @Volatile
    override var pendingConsolidation: PendingConsolidation? = null
        private set

    // ------------------------------------------------------------------ 预检

    /**
     * 阶段 1 预检：只读中央目录与索引，**不解压任何会话**（目标 ≤2s）。
     *
     * @throws ZipImportException `NOT_FOUND` / `NOT_ZIP` / `BAD_ZIP` / `TOO_LARGE` /
     *   `TOO_MANY_ENTRIES` / [ImportErrorCodes.NO_SESSIONS] 等
     * @throws DeepSeekParseException `MALFORMED_JSON` / `VERSION_UNSUPPORTED`
     */
    override suspend fun inspect(zip: File, zipName: String): Inspect = withContext(Dispatchers.IO) {
        val zipSize = zip.length()
        val archiveFingerprint = contentFingerprint(zip)
        DeepSeekZip.open(zip).use { reader ->
            // 版本校验：不是 v3 直接失败。manifest 缺失 → NOT_FOUND。
            DeepSeekParser.parseManifest(reader.manifestText())
            val names = reader.sessionEntryNames()
            if (names.isEmpty()) {
                throw ZipImportException(
                    ImportErrorCodes.NO_SESSIONS,
                    "压缩包里没有找到对话",
                )
            }
            // 索引用于时间范围和 start-time 兜底。坏索引不阻断预览，但不伪造会话归属。
            val indexRows = runCatching {
                reader.indexText()?.let { text -> DeepSeekParser.parseIndex(text) }
            }.getOrNull().orEmpty()
            val indexById = indexRows.associateBy { it.id }
            val previews = names.map { entryName ->
                val sourceId = sessionIdOf(entryName)
                try {
                    DeepSeekParser.previewSession(
                        text = reader.sessionText(entryName),
                        expectedSessionId = sourceId,
                        fallbackUpdatedAt = indexById[sourceId]?.updatedAt,
                        fallbackStartedAt = indexById[sourceId]?.insertedAt,
                    )
                } catch (e: ZipImportException) {
                    if (e.code == ZipErrorCodes.TOO_LARGE) throw e
                    DeepSeekSessionPreview(
                        entryName = entryName,
                        sourceSessionId = sourceId,
                        title = null,
                        sourceStartedAt = indexById[sourceId]?.insertedAt,
                        messageCount = 0,
                        damageCode = e.code,
                    )
                }
            }.sortedWith(
                compareBy<DeepSeekSessionPreview> { it.sourceStartedAt == null }
                    .thenBy { it.sourceStartedAt ?: Long.MAX_VALUE }
                    .thenBy { it.sourceSessionId },
            )
            val timeRange = timeRangeOf(indexRows)
            if (contentFingerprint(zip) != archiveFingerprint) {
                throw ZipImportException(ZipErrorCodes.BAD_ZIP, "文件在预览期间发生变化")
            }
            val estimate = estimateFromDeclaredSizes(reader.sessionSizes().map { it.declaredSize })
            Inspect(
                sessions = names.size,
                timeRange = timeRange,
                zipSize = zipSize,
                fingerprint = fingerprintOf(zipName, zipSize),
                estimatedYuan = estimate?.estimatedYuan,
                estimatedShards = estimate?.shards,
                sourceWindows = previews,
                contentFingerprint = archiveFingerprint,
            )
        }
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 阶段 2 导入：逐会话解析 → 落库 → 重建索引 → 可选记忆整理。
     *
     * 运行在 `Dispatchers.IO`（解压/JSON 都是 CPU 活，`「CPU 卡顿」）。
     *
     * @param onProgress 每处理完一个会话回调（含失败会话）
     * @param onIndexProgress 索引进度
     * @param onConsolidateProgress 记忆整理进度
     * @param isCancelled 协作式取消；已提交会话保留，返回当前 summary
     * @param requiresConsent 调用方额外确认规则（例如「超过当日剩余额度」）；返回 true 时需要
     *   用户确认。**¥1 规则由 importer 自己判定**，两者任一成立就 `consentPending`。
     */
    override suspend fun import(
        zip: File,
        zipName: String,
        name: String,
        persona: String?,
        consolidate: Boolean,
        range: ConsolidateRange,
        resumeCharacterId: String?,
        onProgress: (Progress) -> Unit,
        onIndexProgress: (Int, Int) -> Unit,
        onConsolidateProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        requiresConsent: suspend (ConsolidatePlan) -> Boolean,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        pendingConsolidation = null

        val issues = mutableListOf<Issue>()
        val newSegments = mutableListOf<ImportedSegment>()
        var sessionsImported = 0
        var sessionsSkippedExisting = 0
        var sessionsFailed = 0
        var messagesInserted = 0
        var messagesExisting = 0
        var lastImportedAt: Long? = null
        var characterId: String? = resumeCharacterId
        var threadId: String? = null
        var cancelled = false

        DeepSeekZip.open(zip).use { reader ->
            // 再校验一次：阶段 1 与阶段 2 之间文件可能被替换（cache 清理/并发）。
            DeepSeekParser.parseManifest(reader.manifestText())
            val names = reader.sessionEntryNames()
            if (names.isEmpty()) {
                throw ZipImportException(
                    ImportErrorCodes.NO_SESSIONS,
                    "压缩包里没有找到对话",
                )
            }
            // 索引行的 updated_at 只作会话时间戳的兜底（§4.2/§4.3）；索引坏不影响导入。
            val indexById = runCatching {
                reader.indexText()?.let { text -> DeepSeekParser.parseIndex(text) }
            }.getOrNull()?.associateBy { it.id }.orEmpty()

            val total = names.size
            onProgress(Progress(done = 0, total = total, messages = 0))

            for ((position, entryName) in names.withIndex()) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                val sessionId = sessionIdOf(entryName)
                try {
                    val text = reader.sessionText(entryName)
                    val session = DeepSeekParser.parseSession(
                        text = text,
                        expectedSessionId = sessionId,
                        fallbackUpdatedAt = indexById[sessionId]?.updatedAt,
                        fallbackStartedAt = indexById[sessionId]?.insertedAt,
                    )
                    // 逐会话入库：第一个会话沿用调用方的续导目标，之后固定到本次新建的 TA。
                    val summary = repo.importSessions(
                        target = ImportTarget.NewCharacter(
                            name = name,
                            persona = persona,
                            zipName = zipName,
                            resumeCharacterId = characterId,
                        ),
                        sessions = listOf(session),
                    )
                    characterId = summary.characterId
                    threadId = summary.threadId
                    sessionsImported += summary.sessionsImported
                    sessionsSkippedExisting += summary.sessionsSkippedExisting
                    sessionsFailed += summary.sessionsFailed
                    messagesInserted += summary.messagesInserted
                    messagesExisting += summary.messagesExisting
                    issues += summary.issues
                    newSegments += summary.segments
                    val last = summary.lastImportedAt
                    if (last != null) {
                        lastImportedAt = lastImportedAt?.let { maxOf(it, last) } ?: last
                    }
                } catch (e: DeepSeekParseException) {
                    // 会话级：读不出来就跳过，结果页计入「读不出来 Y 段」。
                    sessionsFailed++
                    issues += Issue(e.code)
                } catch (e: ZipImportException) {
                    // 累计读取超限是归档级错误，继续读只会更糟；其余按单段损坏跳过。
                    if (e.code == ZipErrorCodes.TOO_LARGE) throw e
                    sessionsFailed++
                    issues += Issue(e.code)
                } catch (e: ImportException) {
                    // 续导目标不存在等全局契约错误：不能当单会话失败吞掉。
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sessionsFailed++
                    issues += Issue(ImportErrorCodes.IMPORT_FAILED)
                }
                onProgress(
                    Progress(
                        done = position + 1,
                        total = total,
                        messages = messagesInserted,
                    ),
                )
            }
        }

        // 取消可能在最后一个会话之后、索引之前到达：统一按「已停止」处理，不再花整理费用。
        if (isCancelled()) cancelled = true
        if (cancelled && characterId == null) {
            // 会话一个都没提交：没有 TA/thread 可供返回，按「已停止」上报（store → Failed(CANCELLED)）。
            throw ZipImportException(ImportErrorCodes.CANCELLED, "已停止导入")
        }
        if (characterId == null || threadId == null) {
            // 所有会话都读不出来：不新建空 TA（避免首页多一个空壳），把第一类原因码报给 UI。
            throw DeepSeekParseException(
                issues.firstOrNull()?.code ?: DeepSeekParseException.EMPTY_SESSION,
                "所有对话都读不出来",
            )
        }

        // 收尾：从 DB 重建该 thread 的全部 IMPORT 段（续导时 summary.segments 可能为空，§3）。
        val targetThreadId = threadId!!
        val targetCharacterId = characterId!!
        val segments = rebuildImportedSegments(targetThreadId, newSegments)

        // 本次确实写进了新往事（续导/补导）：thread 的历史变了，旧的「已整理」标记必须失效，
        // 否则记忆面板会藏掉「整理往事」入口，新往事永远没机会被整理。
        // 若下面这次整理整段成功，会在收尾处重新标记；没勾整理/失败/取消则保持未标记（面板给入口）。
        if (newSegments.isNotEmpty()) {
            try {
                prefs.clearConsolidated(targetThreadId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Preference cleanup is best-effort; preserve its ordinary failure semantics.
            }
        }

        // 本地索引（关键词永远建用户同意且「找旧事」配置齐全时同时建向量）。
        // 失败不阻断导入，只是一次性召回质量下降（验收 1：失败跳过不阻断）。
        val consent = try {
            embedConsent()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            false
        }
        val semanticRequested = consent && indexer.embeddingReady()
        // 先置 true 再进索引阶段：UI 用这个信号区分「正在整理（关键词）」与「正在让 TA 按意思找旧事」。
        _semanticIndexing.value = semanticRequested
        val indexed = try {
            try {
                // 建向量阶段的进度口径 = 消息条数（indexer 的 embed 回调）；
                // 关键词索引阶段仍按「段」报进度。两种口径不混着用，避免进度条来回跳。
                if (semanticRequested) {
                    val totalMessages = segments.sumOf { it.messageIds.size }
                    if (totalMessages > 0) onIndexProgress(0, totalMessages)
                }
                indexer.index(
                    threadId = targetThreadId,
                    segments = segments,
                    embedEnabled = semanticRequested,
                    isCancelled = isCancelled,
                    onEmbedProgress = { done, total -> onIndexProgress(done, total) },
                    progress = { done, total ->
                        if (!semanticRequested) onIndexProgress(done, total)
                    },
                )
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                false
            }
        } finally {
            // 无论成功/失败/取消都复位：否则失败后 UI 会一直显示「正在按意思找」。
            _semanticIndexing.value = false
        }

        // 记忆整理（显式选择 + 费用确认；取消时不整理，进度由 prefs 保留）。
        var consolidation: ConsolidateResult? = null
        var consolidationPlan: ConsolidatePlan? = null
        if (consolidate && !cancelled) {
            val planned = planConsolidation(targetThreadId, segments, range)
            if (planned != null) {
                consolidationPlan = planned.plan
                val plan = planned.plan
                if (plan.shards == 0) {
                    // 全部范围也没有可整理的内容：正常返回、**不写「已整理」标记**
                    // （建议 标成已整理会永久藏掉入口）。
                    // 结果页据 shardsTotal==0 显示「没有可整理的旧事。」；见 DeepSeekImportScreen。
                    consolidation = NOTHING_TO_CONSOLIDATE
                } else {
                    // ¥1 规则由 importer 判定；调用方规则用于「超当日剩余额度」等外部条件。
                    // 调用方规则读偏好失败不能把整次导入判失败——数据已经写完了，退化为只看 ¥1。
                    val callerWantsConsent = try {
                        requiresConsent(plan)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Throwable) {
                        false
                    }
                    val needsConsent = plan.estimatedYuan > CONSENT_YUAN_THRESHOLD || callerWantsConsent
                    if (needsConsent) {
                        pendingConsolidation = PendingConsolidation(
                            threadId = targetThreadId,
                            segments = segments,
                            // 用回退后的范围：确认后真正跑的是这个范围（90 天空时已放宽到全部）。
                            range = planned.range,
                            plan = plan,
                        )
                    } else {
                        consolidation = try {
                            consolidator.run(
                                threadId = targetThreadId,
                                segments = segments,
                                range = planned.range,
                                plan = plan,
                                progress = { done, total -> onConsolidateProgress(done, total) },
                                isCancelled = isCancelled,
                            )
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
            }
        }

        // 整段整理成功 → 记「已整理」标记（` 「不重复收费」）：
        // 记忆面板据此收起「整理往事」入口，避免用户再点一次从 0 重跑全部分片。
        // 判定口径：`!cancelled && failed == 0 && done == total`
        // 且总分片数 > 0（0 片不标记——否则「全部早于 90 天」的档案会被永久标成已整理）。
        // 失败片/取消不标记——断点（doneShards/failedShards）在 ImportPrefs.consolidation 里，
        // 下次续跑只跑未完成片（§6）。
        if (consolidation?.isFullyConsolidated() == true) {
            try {
                prefs.markConsolidated(targetThreadId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Marking is best-effort; preserve ordinary failure semantics.
            }
        }

        val summary = ImportSummary(
            characterId = targetCharacterId,
            threadId = targetThreadId,
            sessionsImported = sessionsImported,
            sessionsSkippedExisting = sessionsSkippedExisting,
            sessionsFailed = sessionsFailed,
            messagesInserted = messagesInserted,
            messagesExisting = messagesExisting,
            segments = newSegments,
            issues = issues,
            lastImportedAt = lastImportedAt,
            // 整次导入是否新建了 TA：由**调用方传入的续导目标**决定，不能看每个会话的 summary
            // ——第一个会话新建后，后续会话都是用同一个 characterId 续导（会各自报 false）。
            // 结果页的「撤销这次导入」只对本次新建的 TA 提供。
            createdNewCharacter = resumeCharacterId.isNullOrBlank(),
        )
        ImportOutcome(
            summary = summary,
            indexed = indexed,
            consolidation = consolidation,
            consolidationPlan = consolidationPlan,
            consentPending = pendingConsolidation != null,
        )
    }

    /**
     * Multi-window import: persist the assignment first, then commit each TA group atomically.
     * Memory consolidation is intentionally omitted because current memory writes replace a whole owner
     * set; the batch remains undoable only while its saved memory fingerprint is unchanged.
     */
    override suspend fun importBatch(
        zip: File,
        plan: ImportBatchPlan,
        onProgress: (Progress) -> Unit,
        onIndexProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        if (contentFingerprint(zip) != plan.fingerprint) {
            repo.finishBatch(plan.id, ImportRepository.BATCH_PARTIAL, ImportRepository.POST_PROCESS_PENDING)
            throw ZipImportException(ZipErrorCodes.BAD_ZIP, "文件和预览时不一致")
        }
        repo.beginBatch(plan)
        val total = plan.windows.count { it.groupId != null }
        var done = 0
        var messagesInserted = 0
        var cancelled = false
        var indexed = true
        var postProcessStatus = ImportRepository.POST_PROCESS_DONE
        var checkpointReadFailed = false
        val groupResults = mutableListOf<ImportBatchGroupResult>()
        val issues = mutableListOf<Issue>()
        onProgress(Progress(0, total, 0))
        try {
            DeepSeekZip.open(zip).use { reader ->
                DeepSeekParser.parseManifest(reader.manifestText())
                val entries = reader.sessionEntryNames().toSet()
                val indexById = runCatching {
                    reader.indexText()?.let { DeepSeekParser.parseIndex(it) }
                }.getOrNull().orEmpty().associateBy { it.id }
                for (target in plan.groups) {
                    val windows = plan.windows.filter { it.groupId == target.id }
                    if (windows.isEmpty()) continue
                    if (isCancelled()) {
                        cancelled = true
                        repo.markBatchGroupPending(plan.id, target.id)
                        break
                    }
                    try {
                        val sessions = ArrayList<ParsedDeepSeekSession>(windows.size)
                        for (window in windows) {
                            if (isCancelled()) throw CancellationException("cancelled")
                            val preview = window.preview
                            preview.damageCode?.let { throw DeepSeekParseException(it) }
                            if (preview.entryName !in entries) {
                                throw ZipImportException(ZipErrorCodes.NOT_FOUND, "会话条目已不存在")
                            }
                            val index = indexById[preview.sourceSessionId]
                            sessions += DeepSeekParser.parseSession(
                                text = reader.sessionText(preview.entryName),
                                expectedSessionId = preview.sourceSessionId,
                                fallbackUpdatedAt = index?.updatedAt,
                                fallbackStartedAt = index?.insertedAt,
                            )
                        }
                        val result = repo.commitBatchGroup(
                            batchId = plan.id,
                            groupId = target.id,
                            sessions = sessions,
                            isCancelled = isCancelled,
                            userName = target.userName,
                            styleSamples = target.styleSamples,
                        )
                        groupResults += result
                        messagesInserted += result.messagesInserted
                    } catch (cancel: CancellationException) {
                        repo.markBatchGroupPending(plan.id, target.id)
                        cancelled = true
                        break
                    } catch (failure: Exception) {
                        val code = when (failure) {
                            is DeepSeekParseException -> failure.code
                            is ZipImportException -> failure.code
                            is ImportException -> failure.code
                            else -> ImportErrorCodes.IMPORT_FAILED
                        }
                        repo.markBatchGroupFailed(plan.id, target.id, code)
                        issues += Issue(code)
                        groupResults += ImportBatchGroupResult(
                            groupId = target.id,
                            characterId = target.targetCharacterId,
                            threadId = null,
                            sourceSessionsImported = 0,
                            sourceSessionsSkipped = 0,
                            messagesInserted = 0,
                            errorCode = code,
                        )
                    }
                    done += windows.size
                    onProgress(Progress(done, total, messagesInserted))
                }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                repo.finishBatch(plan.id, ImportRepository.BATCH_CANCELLED, ImportRepository.POST_PROCESS_PENDING)
            }
            throw cancel
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                repo.finishBatch(plan.id, ImportRepository.BATCH_PARTIAL, ImportRepository.POST_PROCESS_PENDING)
            }
            throw failure
        }

        // Per-group indexing happens only after chat commits. A failed index is reported separately and
        // never changes a successful group's chat state. Each chunk+terms write checks batch and segment
        // liveness in one Room transaction, closing the undo/index race.
        val successfulThreads = groupResults.filter { it.errorCode == null && it.threadId != null }
            .mapNotNull { it.threadId }.distinct()
        try {
            if (!cancelled) {
                val totalIndexMessages = successfulThreads.sumOf { threadId ->
                    repo.importSegmentsNeedingIndexForBatch(plan.id, threadId).sumOf { it.messageIds.size }
                }
                var indexDone = 0
                if (totalIndexMessages > 0) onIndexProgress(0, totalIndexMessages)
                val semanticRequested = try {
                    val consent = try {
                        embedConsent()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Throwable) {
                        false
                    }
                    consent && indexer.embeddingReady()
                } catch (_: CancellationException) {
                    cancelled = true
                    postProcessStatus = ImportRepository.POST_PROCESS_PARTIAL
                    false
                }
                _semanticIndexing.value = semanticRequested
                try {
                    if (cancelled) indexed = false
                    for (threadId in successfulThreads) {
                        if (cancelled || checkpointReadFailed) break
                        if (isCancelled()) {
                            cancelled = true
                            indexed = false
                            postProcessStatus = ImportRepository.POST_PROCESS_PARTIAL
                            break
                        }
                        val segments = repo.importSegmentsNeedingIndexForBatch(plan.id, threadId)
                        if (segments.isEmpty()) continue
                        for (segment in segments) {
                            if (isCancelled()) {
                                cancelled = true
                                indexed = false
                                postProcessStatus = ImportRepository.POST_PROCESS_PARTIAL
                                break
                            }
                            var lastSegmentProgress = 0
                            val indexResult = try {
                                // Index one source at a time so each completed source is durably
                                // checkpointed before a later source can be cancelled.
                                indexer.index(
                                    threadId = threadId,
                                    segments = listOf(segment),
                                    embedEnabled = semanticRequested,
                                    batchId = plan.id,
                                    isCancelled = isCancelled,
                                    onEmbedProgress = { count, _ ->
                                        indexDone += (count - lastSegmentProgress).coerceAtLeast(0)
                                        lastSegmentProgress = count
                                        onIndexProgress(minOf(indexDone, totalIndexMessages), totalIndexMessages)
                                    },
                                )
                            } catch (cancel: CancellationException) {
                                cancelled = true
                                indexed = false
                                postProcessStatus = ImportRepository.POST_PROCESS_PARTIAL
                                break
                            } catch (_: ImportIndexCheckpointReadException) {
                                // Keep this source retryable and stop this batch's remaining index work.
                                checkpointReadFailed = true
                                null
                            } catch (_: Exception) {
                                null
                            }
                            // `skipped == 0` only means keyword chunks were saved: failed embedding batches
                            // persist keyword-only chunks. Keep that source resumable. For non-empty sources,
                            // ImportIndexer marks the terminal response batch and source status in one Room transaction.
                            val status = when {
                                indexResult == null -> ImportRepository.INDEX_FAILED
                                indexResult.skipped == 0 &&
                                    (!semanticRequested || indexResult.embedded == indexResult.chunks) -> ImportRepository.INDEX_DONE
                                else -> ImportRepository.INDEX_PARTIAL
                            }
                            // Empty/no-chunk source has no terminal response batch with which to atomically
                            // checkpoint. If embedding returned but its local write failed or the process died
                            // before the transaction, retry may call the provider again; that remote/local
                            // crash window cannot be closed by a Room transaction.
                            if (!batchSourceIndexStatusWriter(plan.id, segment.segmentId, status)) {
                                indexed = false
                                postProcessStatus = ImportRepository.POST_PROCESS_PARTIAL
                            }
                            if (status != ImportRepository.INDEX_DONE) {
                                indexed = false
                                postProcessStatus = ImportRepository.POST_PROCESS_PARTIAL
                            }
                            if (indexResult != null && !semanticRequested) {
                                indexDone += segment.messageIds.size
                                onIndexProgress(minOf(indexDone, totalIndexMessages), totalIndexMessages)
                            }
                            if (checkpointReadFailed) break
                        }
                        if (cancelled || checkpointReadFailed) break
                    }
                } finally {
                    _semanticIndexing.value = false
                }
            } else {
                indexed = false
                postProcessStatus = ImportRepository.POST_PROCESS_PENDING
            }
        } catch (_: CancellationException) {
            cancelled = true
            indexed = false
            postProcessStatus = ImportRepository.POST_PROCESS_PARTIAL
        } catch (_: Throwable) {
            // Post-processing failure must not strand an otherwise committed batch as RUNNING.
            // Keep exact chunk/source checkpoints and let the durable PARTIAL batch resume them.
            indexed = false
            postProcessStatus = ImportRepository.POST_PROCESS_PARTIAL
        } finally {
            _semanticIndexing.value = false
        }

        val failedGroups = groupResults.any { it.errorCode != null }
        val incompleteGroups = groupResults.size < plan.groups.count { group -> plan.windows.any { it.groupId == group.id } }
        val terminalStatus = when {
            cancelled -> ImportRepository.BATCH_CANCELLED
            failedGroups || incompleteGroups || postProcessStatus != ImportRepository.POST_PROCESS_DONE ->
                ImportRepository.BATCH_PARTIAL
            else -> ImportRepository.BATCH_COMPLETE
        }
        // A real coroutine cancellation keeps the Job cancelled; persist terminal/resumable batch state anyway.
        withContext(NonCancellable) {
            repo.finishBatch(plan.id, terminalStatus, postProcessStatus)
        }
        val firstTarget = groupResults.firstOrNull { it.errorCode == null && it.characterId != null }
        val aggregate = ImportSummary(
            characterId = firstTarget?.characterId.orEmpty(),
            threadId = firstTarget?.threadId.orEmpty(),
            sessionsImported = groupResults.sumOf { it.sourceSessionsImported },
            sessionsSkippedExisting = groupResults.sumOf { it.sourceSessionsSkipped },
            sessionsFailed = groupResults.filter { it.errorCode != null }
                .sumOf { result -> plan.windows.count { it.groupId == result.groupId } },
            messagesInserted = groupResults.sumOf { it.messagesInserted },
            messagesExisting = groupResults.sumOf { it.messagesExisting },
            segments = emptyList(),
            issues = issues,
            lastImportedAt = null,
            createdNewCharacter = groupResults.any { result ->
                result.errorCode == null && plan.groups.firstOrNull { it.id == result.groupId }?.targetCharacterId == null
            },
            batchId = plan.id,
        )
        val affectedCharacterIds = groupResults
            .filter { it.errorCode == null && it.characterId != null && it.messagesInserted > 0 }
            .mapNotNull { it.characterId }
            .distinct()
        ImportOutcome(
            summary = aggregate,
            indexed = indexed,
            consolidation = null,
            consolidationPlan = null,
            consentPending = false,
            batch = ImportBatchResult(
                batchId = plan.id,
                groups = groupResults,
                skippedUnassigned = plan.windows.count { it.groupId == null },
                postProcessStatus = postProcessStatus,
                createdOrUpdatedCharacterIds = affectedCharacterIds,
                awakenOnImport = plan.awakenOnImport,
            ),
        )
    }

    // ------------------------------------------------------------------ 确认后继续整理

    /**
     * 用户确认后继续记忆整理（`confirmConsolidation(true)` 调用），上下文来自 [pendingConsolidation]。
     *
     * @param isCancelled 协作式取消；取消时 返回 `cancelled=true` 并保留续跑进度（§6）。
     */
    override suspend fun resumeConsolidation(
        threadId: String,
        segments: List<ImportedSegment>,
        range: ConsolidateRange,
        plan: ConsolidatePlan,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
    ): ConsolidateResult = withContext(Dispatchers.IO) {
        try {
            val result = consolidator.run(
                threadId = threadId,
                segments = segments,
                range = range,
                plan = plan,
                progress = onProgress,
                isCancelled = isCancelled,
            )
            // 同 import()：只有整段成功（未取消 / 0 失败 / 全部分片）才写「已整理」标记；
            // 失败片或取消保留断点，下次从续跑进度接着来。
            if (result.isFullyConsolidated()) {
                try {
                    prefs.markConsolidated(threadId)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    // Marking is best-effort; preserve ordinary failure semantics.
                }
            }
            result
        } finally {
            if (pendingConsolidation?.threadId == threadId) {
                pendingConsolidation = null
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 一次整理计划 + 实际生效的范围（90 天空时会回退到全部）。 */
    private data class PlannedConsolidation(
        val plan: ConsolidatePlan,
        val range: ConsolidateRange,
    )

    /**
     * 算整理计划；`LAST_90_DAYS` 范围内 0 片时**自动回退到 ALL 重算一次**。
     *
     * 为什么回退（建议）：默认范围是最近 90 天，而导入的 ZIP 可能
     * 「全部早于 90 天」——此时 90 天计划是 0 片，修复前会被标记成「已整理」，记忆面板入口
     * 也被藏掉，用户再也没有入口整理全部范围。回退后若 ALL 仍 0 片（范围内确实没有可整理的
     * 往事），调用方不标记、只给一行「没有可整理的旧事」。
     *
     * @return null = 计划本身算不出来（读取/估价异常），调用方按「这次没整理」处理；
     *   [PlannedConsolidation.plan].shards == 0 表示两个范围都没有可整理的内容。
     */
    private suspend fun planConsolidation(
        threadId: String,
        segments: List<ImportedSegment>,
        range: ConsolidateRange,
    ): PlannedConsolidation? {
        val first = try {
            consolidator.plan(threadId, segments, range)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }
            ?: return null
        if (first.shards > 0 || range != ConsolidateRange.LAST_90_DAYS) {
            return PlannedConsolidation(first, range)
        }
        val all = try {
            consolidator.plan(threadId, segments, ConsolidateRange.ALL)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        } ?: return null
        return PlannedConsolidation(all, ConsolidateRange.ALL)
    }

    /**
     * 从数据库重建 `ImportedSegment` 列表（`「重建 List<ImportedSegment>」）。
     *
     * - 只取 `batonReason="IMPORT"` 的段（§4.5：仅标注，不驱动别的逻辑）；
     * - `messageIds` 用 `bySegment` 的写入顺序（导入段内时间严格递增，查询按 createdAt ASC）；
     * - DB 不存 `sourceSessionId`，这里优先用本次 summary 里的映射，续导拿不到时填空串
     *   （的 index/plan/run 都不读它）。
     */
    private suspend fun rebuildImportedSegments(
        threadId: String,
        fromThisRun: List<ImportedSegment>,
    ): List<ImportedSegment> {
        val db = database ?: return fromThisRun
        val known = fromThisRun.associateBy { it.segmentId }
        return db.segmentDao().forThread(threadId)
            .filter { it.batonReason == ImportRepository.BATON_REASON_IMPORT }
            .mapNotNull { segment ->
                val messages = db.messageDao().bySegment(segment.id)
                if (messages.isEmpty()) return@mapNotNull null
                ImportedSegment(
                    sourceSessionId = known[segment.id]?.sourceSessionId.orEmpty(),
                    segmentId = segment.id,
                    messageIds = messages.map { it.id },
                    firstMessageAt = messages.first().createdAt,
                    lastMessageAt = messages.last().createdAt,
                )
            }
    }

    companion object {

        /** 费用粗估口径（§7「粗估」行）：tokens ≈ 字节 / 4。 */
        const val BYTES_PER_TOKEN: Double = 4.0

        /** 整理分片预算（§7：40k token/片；与 `BatonPolicy.shard` 的 40_000 一致）。 */
        const val SHARD_TOKENS: Int = 40_000

        /** 每片估算价（§7：¥0.096/片，按 40k 输入 + 2k 输出估；不是账单）。 */
        const val YUAN_PER_SHARD: Double = 0.096

        /** 超过这个金额要二次确认（§2 >¥1）。*/
        const val CONSENT_YUAN_THRESHOLD: Double = 1.0

        /** 续导指纹口径（§6）：`"zip 文件名:文件字节数"`。 */
        fun fingerprintOf(zipName: String, zipSize: Long): String = "$zipName:$zipSize"

        /** Local archive identity for multi-window recovery; content, not name/size, determines resume. */
        fun contentFingerprint(zip: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(zip).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * 「范围内没有可整理的往事」的结果标记（0 片、未调用模型、未记账、不写「已整理」标记）。
         *
         * 结果页据 `consolidation.shardsTotal == 0` 给一行「没有可整理的旧事。」；
         * 用非 null 结果（而不是 null）是为了和「用户选择先不整理」「计划超阈值待确认」
         * 区分开——那两种 null 不代表「没东西可整理」。
         */
        val NOTHING_TO_CONSOLIDATE: ConsolidateResult = ConsolidateResult(
            shardsTotal = 0,
            shardsDone = 0,
            failed = 0,
            estimatedYuan = 0.0,
            cancelled = false,
        )
    }
}

/** 把一个会话条目名（`chat_sessions/<id>.json`）还原成源会话 id。 */
internal fun sessionIdOf(entryName: String): String =
    entryName.substringAfterLast('/').removeSuffix(".json")

/** 索引行的最小/最大时间（含 inserted_at 与 updated_at），全为空返回 null。 */
internal fun timeRangeOf(rows: List<SessionIndexRow>): LongRange? {
    var min: Long? = null
    var max: Long? = null
    for (row in rows) {
        for (at in listOfNotNull(row.insertedAt, row.updatedAt)) {
            min = min?.let { minOf(it, at) } ?: at
            max = max?.let { maxOf(it, at) } ?: at
        }
    }
    val from = min ?: return null
    val to = max ?: return null
    return from..to
}
