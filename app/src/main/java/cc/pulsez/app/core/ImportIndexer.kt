package cc.pulsez.app.core

import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.ChunkTermEntity
import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.mem.BigramIndex
import cc.pulsez.app.mem.TokenEstimator
import cc.pulsez.app.mem.VectorMath
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Checkpoint storage failures stop import indexing before a potentially paid provider request. */
internal class ImportIndexCheckpointReadException(cause: Throwable) :
    IllegalStateException("unable to read import index checkpoint", cause)

/**
 * 导入消息的最小表示（本地索引 / 记忆整理的输入）。
 *
 * 刻意与 `data.db.MessageEntity` 解耦 只依赖这两个纯 Kotlin 类型，全部 IO 走注入
 * lambda，因此本包的两个类都能在纯 JVM 单测里跑（不启动 Android）。
 *
 * @property id `message.id`（导入时是确定性 id，见 `ImportRepository.messageIdFor`）
 * @property role `user` / `assistant`（导入往事只有这两种，见 映射表）
 * @property text 正文
 * @property at 源消息投递时间（毫秒；**禁止**用 `now` 顶替）
 */
data class ImportMessage(
    val id: String,
    val role: String,
    val text: String,
    val at: Long,
) {
    companion object {
        /** 用户消息 role（与 `MemMessage.ROLE_USER` 同值）。 */
        const val ROLE_USER: String = "user"

        /** TA 消息 role（与 `MemMessage.ROLE_ASSISTANT` 同值）。 */
        const val ROLE_ASSISTANT: String = "assistant"
    }
}

/**
 * 向量端口（v0.4.1「向量/embedding 正式化」：`）。
 *
 * 为什么单独抽一个窄接口：`ImportIndexer` 与记忆面板的「补建」都只依赖它，**不依赖 HTTP /
 * Android / AppContainer**，所以这两条花钱路径都能在纯 JVM 单测里用 fake 跑
 * （不用 MockWebServer、不联网）。生产实现由 `AppContainer` 用 `EmbeddingClient` +
 * 设置里的地址/模型 + Keystore 里的密钥构造。
 *
 * 契约（调用方 [ImportIndexer] 按此校验，任何一条不满足都按「这一批失败」处理）：
 * - 返回与 `texts` **等长**的浮点向量列表；
 * - 每条向量非空、且同批维度一致；
 * - 返回 `null` = 未配置 / 本次不可用（调用方直接跳过，不重试、不阻断导入）；
 * - 抛异常 = 调用失败（调用方整批重试 2 次，仍失败则跳过该批，chunk 仍按关键词索引写入）。
 *
 * 依据：`「批大小 16；失败重试 2 次，仍失败就跳过」、
 * ` （导入历史也建向量），向量量化见 `mem/VectorMath.quantizeInt8`。
 */
fun interface EmbedPort {
    suspend fun embed(texts: List<String>): List<FloatArray>?
}

/**
 * 导入往事的检索索引器（本地关键词 + 向量，v0.4.1）。
 *
 * 导入落库（`ImportRepository` 返回的 [ImportedSegment]）之后，把每个段里的消息按
 * 「用户一句 + TA 一句」打包成检索块，写 `chunk` + `chunk_term`：
 * - **关键词**：永远写（`chunk_term` 二元组倒排），没配置也能召回；
 * - **向量**：注入了可用的 [EmbedPort] 且调用方同意（[index] 的 `embedEnabled`）时，
 *   按批（[EMBED_BATCH_SIZE] ≤16）调用「找旧事」服务，`VectorMath.quantizeInt8` 后写
 *   `dim / vec / scale`、`model = 用户的 embedModel`，让导入的旧事能被**按意思**召回
 *   （` 查询侧沿用 `ChatEngine.embedQuery`，见 §15）。
 *
 * ## 与 `ChatEngine.indexTurn` 的相同与不同（都是故意的）
 * 1. **不写向量的分支仍然存在**：未配置「找旧事」/用户关掉「顺便按意思找旧事」时固定
 *    `dim=0 / vec=空 / scale=1f / model="none"`，行为与 v0.4.0 一致（关键词-only、零网络）；
 *    老数据的这类块可以在记忆面板「补建」（见 `MemoryViewModel`）。
 * 2. **`messageIds` 必须填**：`indexTurn` 旧重载写空串是反面教材；
 *    填了 `chunk.messageIds`，删除消息才能通过 `ChunkDao.invalidateByMessage` 传播。
 * 3. **确定性 `chunkId`**：重复导入同一份 ZIP 不能多出行；见 [chunkIdFor]。
 *    重跑时 `saveChunks` 走 REPLACE，向量也会被刷新成当前模型的结果。
 * 4. **时间用源消息时间**：`firstMessageAt/lastMessageAt/createdAt` 全部取源消息
 *    `createdAt`，不用 `now`（否则时间窗召回会把旧事当刚发生）。
 * 5. **不抛异常**：导入后的后处理不该打断用户；单块/单批失败只记 [Result.skipped] 后继续。
 *
 * ## 分块规则（「一来一回打包、≤300 token」的导入版）
 * - 相邻的「user 然后 assistant」打包成一块；
 * - 同一角色连续出现（例如连续两条 user）时**各自成块**，保证一条消息都不丢；
 * - 每块文本沿用 `indexTurn` 的格式（各截前 200 字）；
 * - 打包后超 300 token 就拆成两块单条块；单条块仍超 300 token 再用
 *   [TokenEstimator.truncateToTokens] 截到 300（保证 `chunk.text` 估算 ≤300）。
 *
 * 依据 §6（chunk 幂等/源时间）、§15 （导入也建向量、批 16、失败重试 2 次、
 * 剩余跳过、进度回调） 阶段 A（`messageIds` 必填、复用 `BigramIndex.terms`）；
 * （一来一回 ≤300 token、批 16 / 重试 2 次）。
 *
 * @param loadMessages 按 id 批量读导入消息（每批 ≤500 个 id；由调用方保证）
 * @param saveChunks 写一个块（chunk + 该块的 chunk_term）
 * @param embed 向量端口；默认返回 null（不联网），保持 v0.4.0 的旧调用兼容
 * @param embedModel 写进 `chunk.model` 的「找旧事」模型名；未配置时返回空串
 * @param embedConfigured 「找旧事」服务是否配置齐全（地址 + 模型 + 密钥）；
 *   未配置时**一次向量调用都不发**（验收 2：未配置 = 零网络）
 */
class ImportIndexer(
    private val loadMessages: suspend (ids: List<String>) -> List<ImportMessage>,
    private val saveChunks: suspend (chunks: List<ChunkEntity>, terms: List<ChunkTermEntity>) -> Unit,
    private val embed: EmbedPort = EmbedPort { null },
    private val embedModel: suspend () -> String = { "" },
    private val embedConfigured: suspend () -> Boolean = { false },
    /** SHA-256 identity of the non-secret provider endpoint and model for restart-safe block reuse. */
    private val embedConfigFingerprint: suspend () -> String = { "" },
    /** Loads only requested deterministic chunks while their batch and source are active. */
    private val loadBatchChunks: (suspend (batchId: String, segmentId: String, chunkIds: List<String>) -> List<ChunkEntity>)? = null,
    /**
     * Batch-only writer: validates the live batch and source segment in one Room transaction.
     * `sourceIndexComplete` is null for non-terminal provider batches, true when the terminal batch
     * completes the whole source, and false when that persisted terminal batch is incomplete.
     */
    private val saveBatchChunks: (suspend (
        batchId: String,
        chunks: List<ChunkEntity>,
        terms: List<ChunkTermEntity>,
        sourceIndexComplete: Boolean?,
    ) -> Boolean)? = null,
) {

    /**
     * 一次索引的结果。
     *
     * @property chunks 成功写入的块数
     * @property skipped 没能写入的块数（单块 `saveChunks` 失败；整段加载失败按 1 计）
     * @property embedded 其中**带向量**写成功的块数（0 = 关键词-only；< chunks 说明有批失败被跳过）
     */
    data class Result(val chunks: Int, val skipped: Int, val embedded: Int = 0)

    /**
     * 现在能不能建向量：服务配置齐全 + 有模型名。
     *
     * 导入编排（`DeepSeekImporter`）在导入确认屏之后用它决定**是否进入建向量阶段**，
     * 这样 UI 的「正在让 TA 按意思找旧事」进度文案只在真的会联网时出现。
     */
    suspend fun embeddingReady(): Boolean {
        currentCoroutineContext().ensureActive()
        val configured = try {
            embedConfigured()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            false
        }
        if (!configured) return false
        currentCoroutineContext().ensureActive()
        val model = try {
            embedModel()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            ""
        }
        return model.isNotBlank()
    }

    /**
     * 为导入的每个段建索引（关键词永远建；已配置且同意时同时建向量）。
     *
     * 向量阶段按 [EMBED_BATCH_SIZE]（≤16）分批：每批先整批调用 [EmbedPort]，成功后把整批块
     * 连同量化向量一起写库；整批失败重试 [EMBED_MAX_RETRIES] 次，仍失败则**跳过该批的向量**
     * （块仍以 `dim=0 / model="none"` 写入，关键词召回不受影响），不阻断导入。
     *
     * @param threadId 目标会话 id（写进 `chunk.threadId` / `chunk_term.threadId`）
     * @param segments 本次导入新建的段（`ImportSummary.segments`；幂等命中时为空）
     * @param embedEnabled 调用方是否同意建向量（导入确认屏的「顺便……」默认开、可关）；
     *   传 false 时行为与 v0.4.0 完全一致。真正是否联网还取决于 [embeddingReady]，
     *   本参数只是用户意愿，不改变任何数据语义。
     * @param onEmbedProgress 每批向量处理完回调 `(done, total)`：分子/分母都是**消息条数**
     *   （总消息数一开始就知道，不用先加载全部块；只有真的进入向量阶段才会回调，
     *   未配置/关闭时为 0 次）。
     * @param progress 每个段处理完回调 `(done, total)`；开始时会先回调一次 `(0, total)`，
     *   方便 UI 立刻显示总段数（与 `ImportRepository.importSessions` 的进度口径一致）
     */
    suspend fun index(
        threadId: String,
        segments: List<ImportedSegment>,
        embedEnabled: Boolean = true,
        onEmbedProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        batchId: String? = null,
        isCancelled: () -> Boolean = { false },
        // Keep the legacy progress callback last so existing trailing-lambda call sites remain source-compatible.
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        ensureIndexActive(isCancelled)
        val total = segments.size
        var chunks = 0
        var skipped = 0
        var embedded = 0
        progress(0, total)

        // 是否真的建向量：用户同意 + 服务配置齐全 + 有模型名。任一不满足都保持 v0.4.0 行为
        // （dim=0 / model="none"），且**一次网络调用都不发**（验收 2）。
        val vectorModel = if (embedEnabled && embeddingReady()) {
            try {
                embedModel()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        val embeddingOn = vectorModel.isNotBlank()
        val vectorConfigFingerprint = if (embeddingOn) {
            try {
                embedConfigFingerprint()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }

        // 进度口径：用**消息条数**做分子/分母，不用批数——总消息数一开始就知道
        // （`ImportedSegment.messageIds`），每批处理完把这一批覆盖的消息数累加上去即可；
        // 批数要先加载完全部消息才知道（导入包可能很大，不能为进度把整包读进内存）。
        val totalMessages = segments.sumOf { it.messageIds.size }
        var embeddedMessages = 0

        segments.forEachIndexed { index, segment ->
            ensureIndexActive(isCancelled)
            var segmentSkipped = 0
            var segmentEmbedded = 0
            // 整段读取/切块失败：只影响这一段，绝不打断用户（「导入后处理不应打断用户」）。
            val blocks = try {
                if (segment.messageIds.isEmpty()) {
                    emptyList()
                } else {
                    // 分批读（每批 ≤500）：一个 DeepSeek 会话可能有上千条消息，直接 IN (...) 会撞
                    // SQLite 变量上限（「每批 ≤500」）。
                    val loaded = ArrayList<ImportMessage>(segment.messageIds.size)
                    segment.messageIds.chunked(SQLITE_VARIABLE_BATCH).forEach { batch ->
                        ensureIndexActive(isCancelled)
                        loaded += loadMessages(batch)
                    }
                    buildBlocks(threadId, segment, loaded)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                skipped += 1
                segmentSkipped += 1
                emptyList()
            }

            var processedBlocks = 0
            blocks.chunked(EMBED_BATCH_SIZE).forEach { batch ->
                ensureIndexActive(isCancelled)
                val batchChunkIds = batch.map { block ->
                    chunkIdFor(threadId, block.firstMessageId, block.lastMessageId)
                }
                val cachedById = if (batchId != null && loadBatchChunks != null) {
                    try {
                        loadBatchChunks.invoke(batchId, segment.segmentId, batchChunkIds).associateBy { it.id }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Throwable) {
                        // Missing cache rows are a valid first-run state; a failed read is not.
                        // Fail before embedding so a storage fault cannot turn into duplicate paid work.
                        throw ImportIndexCheckpointReadException(failure)
                    }
                } else {
                    emptyMap()
                }
                val currentConfigCachedIds = batch.mapIndexedNotNull { position, block ->
                    val id = batchChunkIds[position]
                    val cached = cachedById[id]
                    if (embeddingOn && cached != null && canReuseVectorCheckpoint(
                            cached = cached,
                            threadId = threadId,
                            segment = segment,
                            block = block,
                            model = vectorModel,
                            configFingerprint = vectorConfigFingerprint,
                        )
                    ) {
                        id
                    } else {
                        null
                    }
                }.toSet()
                val blocksNeedingVector = if (embeddingOn) {
                    batch.filterIndexed { position, _ -> batchChunkIds[position] !in currentConfigCachedIds }
                } else {
                    emptyList()
                }
                // Requests are cancellable only at attempt boundaries. For batch imports, all vectors
                // returned by one request are committed together below before observing a stop request;
                // this avoids discarding already-received vectors between per-block writes.
                val vectors = if (blocksNeedingVector.isNotEmpty()) {
                    embedWithRetry(
                        embed,
                        blocksNeedingVector.map { it.text },
                        deferCancellationAfterResponse = batchId != null,
                        isCancelled = isCancelled,
                    )
                } else {
                    emptyList()
                }
                val vectorById = HashMap<String, FloatArray?>()
                blocksNeedingVector.forEachIndexed { position, block ->
                    val id = chunkIdFor(threadId, block.firstMessageId, block.lastMessageId)
                    vectorById[id] = vectors?.getOrNull(position)
                }

                val prepared = batch.mapIndexed { position, block ->
                    val chunkId = batchChunkIds[position]
                    val cached = cachedById[chunkId]?.takeIf {
                        matchesBlockContent(it, threadId, segment, block) && hasReusableStoredVector(it)
                    }
                    val currentVectorComplete = embeddingOn &&
                        (chunkId in currentConfigCachedIds || vectorById[chunkId] != null)
                    val vector = if (chunkId in currentConfigCachedIds) null else vectorById[chunkId]
                    PreparedChunk(
                        chunk = chunkForBlock(
                            threadId = threadId,
                            segment = segment,
                            block = block,
                            vector = vector,
                            model = vectorModel,
                            cachedChunk = cached,
                            configFingerprint = vectorConfigFingerprint,
                        ),
                        terms = termsForBlock(threadId, block),
                        vectorComplete = currentVectorComplete,
                    )
                }
                val isTerminalBatch = processedBlocks + batch.size == blocks.size
                val sourceIndexComplete = if (isTerminalBatch) {
                    val completeVectors = segmentEmbedded + prepared.count { it.vectorComplete }
                    if (embeddingOn) {
                        segmentSkipped == 0 && completeVectors == blocks.size
                    } else {
                        segmentSkipped == 0
                    }
                } else {
                    null
                }
                val saveResults = if (batchId != null) {
                    // A provider response is one checkpoint unit. Persist every block represented by
                    // that response, plus terminal source state, in one Room transaction. The short
                    // NonCancellable write also preserves a completed response if the coroutine is
                    // cancelled immediately after the provider returns (the import caller then
                    // observes cancellation below and does not issue another request).
                    val saved = try {
                        withContext(NonCancellable) {
                            saveBatchChunks?.invoke(
                                batchId,
                                prepared.map { it.chunk },
                                prepared.flatMap { it.terms },
                                sourceIndexComplete,
                            ) ?: false
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Throwable) {
                        false
                    }
                    List(batch.size) { saved }
                } else {
                    prepared.map { item ->
                        ensureIndexActive(isCancelled)
                        try {
                            saveChunks(listOf(item.chunk), item.terms)
                            true
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }
                prepared.forEachIndexed { position, item ->
                    if (saveResults[position]) {
                        chunks++
                        if (item.vectorComplete) {
                            embedded++
                            segmentEmbedded++
                        }
                    } else {
                        skipped++
                        segmentSkipped++
                    }
                    processedBlocks++
                }
                embeddedMessages += batch.sumOf { it.messages.size }
                if (embeddingOn && totalMessages > 0) {
                    onEmbedProgress(minOf(embeddedMessages, totalMessages), totalMessages)
                }
                // If the user requested a stop while this provider request was in flight, its returned
                // vectors have now been checkpointed; don't start the next request.
                ensureIndexActive(isCancelled)
            }
            progress(index + 1, total)
        }
        return Result(chunks = chunks, skipped = skipped, embedded = embedded)
    }

    /**
     * 写一个检索块（chunk + 该块的关键词 term）；带 [vector] 时量化后写 `dim/vec/scale/model`。
     *
     * `chunkId` 确定性（[chunkIdFor]）+ `ChunkDao.insertAll` 是 REPLACE：重跑幂等，
     * 且重跑时向量会刷新成当前模型的结果（`「换模型要重建索引」）。
     */
    private fun chunkForBlock(
        threadId: String,
        segment: ImportedSegment,
        block: ImportBlockDraft,
        vector: FloatArray?,
        model: String,
        cachedChunk: ChunkEntity?,
        configFingerprint: String,
    ): ChunkEntity {
        val chunkId = chunkIdFor(threadId, block.firstMessageId, block.lastMessageId)
        val quantized = vector?.takeIf { it.isNotEmpty() }?.let(VectorMath::quantizeInt8)
        return ChunkEntity(
            id = chunkId,
            threadId = threadId,
            segmentId = segment.segmentId,
            text = block.text,
            firstMessageAt = block.firstMessageAt,
            lastMessageAt = block.lastMessageAt,
            messageIds = block.messageIds,
            dim = quantized?.dim ?: cachedChunk?.dim ?: 0,
            vec = quantized?.bytes ?: cachedChunk?.vec ?: ByteArray(0),
            scale = quantized?.scale ?: cachedChunk?.scale ?: 1f,
            model = if (quantized != null) model else cachedChunk?.model ?: MODEL_NONE,
            createdAt = block.firstMessageAt,
            deletedAt = null,
            embeddingConfigFingerprint = if (quantized != null) {
                configFingerprint
            } else {
                cachedChunk?.embeddingConfigFingerprint.orEmpty()
            },
        )
    }

    private fun termsForBlock(threadId: String, block: ImportBlockDraft): List<ChunkTermEntity> =
        block.terms().map { term ->
            ChunkTermEntity(
                term = term,
                chunkId = chunkIdFor(threadId, block.firstMessageId, block.lastMessageId),
                threadId = threadId,
                weight = 1,
            )
        }

    private data class PreparedChunk(
        val chunk: ChunkEntity,
        val terms: List<ChunkTermEntity>,
        val vectorComplete: Boolean,
    )

    private suspend fun ensureIndexActive(isCancelled: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (isCancelled()) throw CancellationException("import indexing cancelled")
    }

    private fun canReuseVectorCheckpoint(
        cached: ChunkEntity,
        threadId: String,
        segment: ImportedSegment,
        block: ImportBlockDraft,
        model: String,
        configFingerprint: String,
    ): Boolean = configFingerprint.isNotBlank() &&
        matchesBlockContent(cached, threadId, segment, block) &&
        hasReusableStoredVector(cached) &&
        cached.model == model &&
        cached.embeddingConfigFingerprint == configFingerprint

    private fun matchesBlockContent(
        cached: ChunkEntity,
        threadId: String,
        segment: ImportedSegment,
        block: ImportBlockDraft,
    ): Boolean = cached.deletedAt == null &&
        cached.id == chunkIdFor(threadId, block.firstMessageId, block.lastMessageId) &&
        cached.threadId == threadId &&
        cached.segmentId == segment.segmentId &&
        cached.text == block.text &&
        cached.messageIds == block.messageIds &&
        cached.firstMessageAt == block.firstMessageAt &&
        cached.lastMessageAt == block.lastMessageAt

    private fun hasReusableStoredVector(cached: ChunkEntity): Boolean =
        cached.dim > 0 &&
            cached.vec.size == cached.dim &&
            // VectorMath.cosine returns 0 for a zero norm, so a zero checkpoint is not a completed vector.
            cached.vec.any { it.toInt() != 0 } &&
            cached.scale.isFinite() &&
            cached.scale > 0f &&
            cached.model.isNotBlank() &&
            cached.model != MODEL_NONE

    companion object {
        /** 单块 token 上限（一来一回打包 ≤300 token）。*/
        const val MAX_BLOCK_TOKENS: Int = 300

        /** 单条消息进块时的截断字数（与 `ChatEngine.indexTurn` 的 `take(200)` 一致）。 */
        const val MAX_MESSAGE_CHARS: Int = 200

        /** 每块最多写入的关键词 term 数（与 `ChatEngine.indexTurn` 的 `take(60)` 一致）。 */
        const val MAX_TERMS: Int = 60

        /** 没有向量的块用的占位模型名（关键词-only；老数据「补建」按它 + `dim=0` 识别）。 */
        const val MODEL_NONE: String = "none"

        /** 每批向量化的文本条数（批大小 16）。*/
        const val EMBED_BATCH_SIZE: Int = 16

        /** 一批失败后的重试次数（不含首次「失败重试 2 次，仍失败就跳过」）。*/
        const val EMBED_MAX_RETRIES: Int = 2

        /** `SELECT ... IN (...)` 每批参数上限（每批 ≤500）。*/
        const val SQLITE_VARIABLE_BATCH: Int = 500

        /**
         * 确定性 chunkId（原式）：
         * `UUID.nameUUIDFromBytes("deepseek-chunk:<threadId>:<firstMessageId>:<lastMessageId>")`。
         *
         * 同一段消息重复索引必然得到同一个 id（`ChunkDao.insertAll` 是 REPLACE），
         * 所以「重复导入 chunk 数不变」（验收）。
         */
        fun chunkIdFor(threadId: String, firstMessageId: String, lastMessageId: String): String =
            UUID.nameUUIDFromBytes(
                "deepseek-chunk:$threadId:$firstMessageId:$lastMessageId".toByteArray(Charsets.UTF_8),
            ).toString()
    }
}

/**
 * 一批文本的向量化 + 重试（`ImportIndexer` 与记忆面板「补建」共用）。
 *
 * 语义严格按 `**整批**调用；失败（抛异常 / 返回 null / 尺寸或维度不对）
 * 重试 [ImportIndexer.EMBED_MAX_RETRIES] 次（即最多 3 次尝试），仍失败返回 null，
 * 由调用方跳过该批（不阻断导入/补建，已成功的批保留）。取消会原样传播且在每次请求前检查，
 * 不会把取消误作可重试的服务失败。批量导入可延后成功响应后的协作停止检查，以便先原子落盘该响应。
 *
 * @return 与 [texts] 等长的向量列表；null = 这批最终失败或服务不可用
 */
internal suspend fun embedWithRetry(
    embed: EmbedPort,
    texts: List<String>,
    /** Import indexing commits a returned provider batch before observing cooperative stop. */
    deferCancellationAfterResponse: Boolean = false,
    isCancelled: () -> Boolean = { false },
): List<FloatArray>? {
    if (texts.isEmpty()) return emptyList()
    repeat(ImportIndexer.EMBED_MAX_RETRIES + 1) {
        // Check both coroutine and user cancellation before every paid request/retry.
        currentCoroutineContext().ensureActive()
        if (isCancelled()) throw CancellationException("import indexing cancelled")
        val vectors = try {
            embed.embed(texts)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }
        if (!deferCancellationAfterResponse) {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw CancellationException("import indexing cancelled")
        }
        if (isUsableVectors(vectors, texts.size)) return vectors
    }
    return null
}

/**
 * 校验一批向量是否可用：条数对、每条非空、同批维度一致。
 *
 * 维度不一致的向量写进 DB 会让召回侧 `VectorMath.cosine` 的 `require(dim)` 抛异常
 * （v0.4 阻断 的同类问题），所以这里当作「这批失败」跳过，而不是写入。
 */
private fun isUsableVectors(vectors: List<FloatArray>?, expectedSize: Int): Boolean {
    if (vectors == null || vectors.size != expectedSize) return false
    if (vectors.any { it.isEmpty() }) return false
    val dim = vectors.first().size
    return vectors.all { vector ->
        if (vector.size != dim || vector.any { !it.isFinite() } || vector.all { it == 0f }) return@all false
        val scale = vector.maxOf { abs(it) } / VectorMath.INT8_MAX.toFloat()
        scale.isFinite() && scale > 0f
    }
}

/**
 * 一个待写入的检索块草稿（纯逻辑，便于单测；不含 Room 依赖的构造）。
 *
 * @property text 打包后的 chunk 正文（已按 [ImportIndexer.MAX_BLOCK_TOKENS] 截断）
 * @property messages 构成该块的消息（按源顺序；1 条 = 单条块，2 条 = 一来一回）
 */
internal data class ImportBlockDraft(
    val text: String,
    val messages: List<ImportMessage>,
) {
    val firstMessageId: String get() = messages.first().id

    val lastMessageId: String get() = messages.last().id

    val firstMessageAt: Long get() = messages.first().at

    val lastMessageAt: Long get() = messages.last().at

    /** 按源顺序逗号连接（`ChunkDao.invalidateByMessage` 用 LIKE 匹配，顺序不影响命中）。 */
    val messageIds: String get() = messages.joinToString(",") { it.id }

    /** 关键词倒排 term（去重、保持首次出现顺序，最多 [ImportIndexer.MAX_TERMS] 个）。 */
    fun terms(): List<String> = BigramIndex.terms(text).take(ImportIndexer.MAX_TERMS)
}

/**
 * 把一段消息切成检索块（纯函数，无 IO）。
 *
 * 顺序保证：按 `segment.messageIds` 的写入顺序（= 源顺序）来，不按文本重排；
 * 覆盖保证：每条能读到的消息恰好出现在一个块里（同角色连续时各自成块）。
 *
 * @param threadId 目标会话 id（仅为与 契约签名一致；chunkId 在 [ImportIndexer.index] 里算）
 * @param segment 导入段；`messageIds` 同时是顺序真相源
 * @param loaded 从 DB 读回的消息（顺序任意；缺行=并发被删，直接跳过不炸整段）
 */
@Suppress("UNUSED_PARAMETER")
internal fun buildBlocks(
    threadId: String,
    segment: ImportedSegment,
    loaded: List<ImportMessage>,
): List<ImportBlockDraft> {
    val messages = orderMessagesBySegment(segment, loaded)
    if (messages.isEmpty()) return emptyList()

    val blocks = ArrayList<ImportBlockDraft>(messages.size)
    var index = 0
    while (index < messages.size) {
        val current = messages[index]
        val next = messages.getOrNull(index + 1)
        if (current.role == ImportMessage.ROLE_USER && next?.role == ImportMessage.ROLE_ASSISTANT) {
            blocks += pairBlock(current, next)
            index += 2
        } else {
            blocks += singleBlock(current)
            index += 1
        }
    }
    return blocks
}

/** 按 `segment.messageIds` 的顺序重排消息；DB 里已不存在的 id 直接跳过。 */
internal fun orderMessagesBySegment(
    segment: ImportedSegment,
    loaded: List<ImportMessage>,
): List<ImportMessage> {
    if (loaded.isEmpty()) return emptyList()
    val byId = HashMap<String, ImportMessage>(loaded.size)
    loaded.forEach { byId[it.id] = it }
    return segment.messageIds.mapNotNull { byId[it] }
}

/**
 * 一来一回打包。超 [ImportIndexer.MAX_BLOCK_TOKENS] 时拆成两块单条块
 * （拆完单条仍超限的再截断），保证每块 ≤300 token。
 */
private fun pairBlock(user: ImportMessage, assistant: ImportMessage): List<ImportBlockDraft> {
    val text = buildString {
        append(USER_PREFIX).append(user.text.take(ImportIndexer.MAX_MESSAGE_CHARS))
        append('\n')
        append(ASSISTANT_PREFIX).append(assistant.text.take(ImportIndexer.MAX_MESSAGE_CHARS))
    }
    if (TokenEstimator.estimate(text) <= ImportIndexer.MAX_BLOCK_TOKENS) {
        return listOf(ImportBlockDraft(text = text, messages = listOf(user, assistant)))
    }
    // 超限就拆：两条各自成块（顺序不变），比截断丢掉 TA 的回应更保真。
    return listOf(singleBlock(user), singleBlock(assistant))
}

/** 单条块：按角色给前缀；超过 300 token 时截断到 300。 */
private fun singleBlock(message: ImportMessage): ImportBlockDraft {
    val prefix = when (message.role) {
        ImportMessage.ROLE_USER -> USER_PREFIX
        else -> ASSISTANT_PREFIX
    }
    val text = prefix + message.text.take(ImportIndexer.MAX_MESSAGE_CHARS)
    return ImportBlockDraft(
        text = TokenEstimator.truncateToTokens(text, ImportIndexer.MAX_BLOCK_TOKENS),
        messages = listOf(message),
    )
}

/** 用户一行的前缀（格式沿用 `ChatEngine.indexTurn`）。 */
private const val USER_PREFIX: String = "你说："

/** TA 一行的前缀（格式沿用 `ChatEngine.indexTurn`）。 */
private const val ASSISTANT_PREFIX: String = "TA 说："
