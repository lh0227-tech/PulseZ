package cc.pulsez.app.core

import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.data.settings.Consolidation
import cc.pulsez.app.mem.BatonPolicy
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.mem.MemoryExtractionPrompt
import cc.pulsez.app.mem.TokenEstimator

/**
 * 记忆整理的范围（默认只整理**最近 90 天**）。
 *
 * - [LAST_90_DAYS] 只取源时间在「now − 90 天」之后（含边界）的消息；
 * - [ALL] 全部导入往事。
 *
 * 90 天默认的理由：旧创伤被整理放大是明确风险（「旧创伤被整理放大」），
 * 且越老的往事与「现在这个人」的相关性越低。
 */
enum class ConsolidateRange {
    LAST_90_DAYS,
    ALL,
}

/**
 * 整理前的「精确确认」数据（阶段 2、§7「精确确认」）。
 *
 * 确认屏在解析完成后、首次模型调用前展示，让用户知道要发多少、花多少；>¥1 或超日上限
 * 时由调用方决定是否二次确认。
 *
 * @property shards 分片数（`BatonPolicy.shard` 的结果）
 * @property inputTokens 本次会发送的输入 token 合计（**含片间重叠**，按 [TokenEstimator] 估算）
 * @property messageCount 落在范围内的消息条数（用于「整理 N 条往事」这类文案）
 * @property estimatedYuan 预估花费（元）：对每片调用 `costOf(片 token, 2000)` 求和；
 *   2000 是输出上限假设（[OUTPUT_TOKEN_ASSUMPTION]）。**是估算，不是账单**
 */
data class ConsolidatePlan(
    val shards: Int,
    val inputTokens: Int,
    val messageCount: Int,
    val estimatedYuan: Double,
)

/**
 * 一次整理的结果（验收：分片/失败/费用/可停止）。
 *
 * @property shardsTotal 本次数据算出的总分片数
 * @property shardsDone 本次运行结束后**已成功完成**的分片数（全量口径：续跑时此前已成功的片也计入，
 *   所以「上一次 6/8 成功、这次补完剩下 2 片」返回 8）。只有 `shardsDone == shardsTotal`
 *   才代表整段成功；UI 的进度分子也用这个数。
 * @property failed **本次运行中**失败的分片数（`completeJson` 返回 null / 抛异常）。
 *   续跑时上次遗留的失败片会重试：重试成功则不再计入；重试再失败才计入本次。失败片索引保留在
 *   [cc.pulsez.app.data.settings.Consolidation.failedShards]（下次续跑只重试它们）。
 * @property estimatedYuan 按**本次运行实际成功的分片**折算的预估花费（续跑跳过的片不重复记账，
 *   与 ` 「不重复收费」一致）。估价口径见 [ImportMemoryConsolidator.plan]，不是账单。
 * @property cancelled 是否因为 `isCancelled` 提前停止（true 时进度保留，下次可续跑）
 */
data class ConsolidateResult(
    val shardsTotal: Int,
    val shardsDone: Int,
    val failed: Int,
    val estimatedYuan: Double,
    val cancelled: Boolean,
)

/**
 * 导入专用记忆整理（阶段 B：需要用户同意 + 已配置 AI 才跑）。
 *
 * 和聊天中的增量抽取（`ChatEngine.maintenance`）**不是一条路径**：
 * 导入的消息是旧时间戳且一次可能上万条，`after(lastExtractAt)` + `takeLast(40)` 的组合永远
 * 抽不到；这里改成「按时间正序、40k 一片、片间重叠 2 条、一次跑完」，
 * 并对每片单独记账/记进度，失败不阻断。
 *
 * ## 依赖全部注入，纯 JVM 可测
 * 本类不直接碰 Android / Room / HTTP：
 * - [loadMessages]：按 id 批量读消息（正式实现 = `MessageDao.byIds`）；
 * - [applyPatch]：写回记忆补丁（正式实现 = `MemoryRepository.applyPatch`，lambda 里固定
 *   `segmentId = null`、`ownerId = null`：导入整理写 thread 维度记忆，与单聊一致）；
 * - [completeJson]：非流式 JSON 补全（正式实现 = `LlmClient.completeJson` + `asInternalRequest`；
 *   **null=未配置/失败**）；
 * - [recordUsage] / [costOf]：记账与估价（`Pricing.costOf` 的估算口径，界面标「预估，以账单为准」）；
 * - [loadProgress] / [saveProgress] / [clearProgress]：整理续跑（正式实现 = `ImportPrefs.consolidation`
 *   / `ImportPrefs.setConsolidation` / `ImportPrefs.clearConsolidation`）。
 *
 * ## 失败与续跑策略（ 重写；`「整理续跑：重跑只处理未完成分片，
 * 不重复记账」、§2 「不重复收费」）
 * - 进度口径是「**已成功片索引集合**」+「失败片索引集合」+「总分片数」（[Consolidation]）；
 * - `completeJson` 返回 null 或抛异常：该片进 `failedShards`，**不写记忆、不记账**，继续下一片；
 * - 续跑时 `doneShards` 里的片**直接跳过，绝不重调 `completeJson`**（这是「不重复收费」的核心）；
 * - `failedShards` 与未尝试片按索引正序重试；成功 → 加 `doneShards`、从 `failedShards` 移除；
 *   再失败 → 留在 `failedShards` 里等下次；
 * - 每尝试一片后 `saveProgress`（脏/抖动用 `runCatching` 兜住，不能中断已花钱的整理）；
 * - `isCancelled` 为 true：立即 break，**不** `clearProgress`，返回 `cancelled = true`，下次续跑；
 * - **全部分片成功**（`doneShards.size == totalShards`）才 `clearProgress`；有失败片/取消都保留断点；
 * - 旧进度的 `totalShards` 与本次算出的片数不一致时**不复用**（消息集合变了，旧索引不再对应新分片，
 *   硬续会漏片/跳片），本次从头整理——UI 的费用确认按全部片估价，不会少报。
 *
 * 依据 §3 阶段 2、§6「整理续跑」、§7（成本/记账）、§8
 * 阶段 B（40k 分片、每片一次 `completeJson`、失败不阻断）；
 * （分片 40k / 重叠 2）与 §6.6（失败降级矩阵）。
 */
class ImportMemoryConsolidator(
    private val loadMessages: suspend (ids: List<String>) -> List<ImportMessage>,
    private val applyPatch: suspend (threadId: String, patchJson: String) -> Boolean,
    private val completeJson: suspend (system: String, user: String) -> String?,
    private val recordUsage: suspend (inputTokens: Int, outputTokens: Int) -> Unit,
    private val costOf: (inputTokens: Int, outputTokens: Int) -> Double,
    private val loadProgress: suspend (threadId: String) -> Consolidation?,
    private val saveProgress: suspend (Consolidation) -> Unit,
    private val clearProgress: suspend (threadId: String) -> Unit,
) {

    /**
     * 估算「整理这次导入往事」的分片数、输入 token 与费用（**不调用模型**）。
     *
     * 估价口径是**范围内全部分片**，不扣除续跑断点（`确认屏给的是这次整理的上限，
     * 估少了会变成「事后加价」；实际续跑时只跑剩余片，`run` 的 `estimatedYuan` 才是真花的部分）。
     *
     * @param segments 本次导入新建的段（`ImportSummary.segments`）
     * @param range 整理范围（默认 UI 选「最近 90 天」）
     * @param now 90 天窗口的基准时刻（测试可固定；正式调用用默认值）
     */
    suspend fun plan(
        threadId: String,
        segments: List<ImportedSegment>,
        range: ConsolidateRange,
        now: Long = System.currentTimeMillis(),
    ): ConsolidatePlan {
        val messages = loadMemMessages(segments, range, now)
        val shards = BatonPolicy.shard(
            messages = messages,
            shardTokens = SHARD_TOKENS,
            overlap = SHARD_OVERLAP,
        )
        val shardTokens = shards.map { TokenEstimator.estimateMessages(it) }
        // 费用是「估算」：输出按上限 2000 token 假设（40k 输入 + 2k 输出的示例价口径）。
        val estimatedYuan = shardTokens.sumOf { tokens ->
            costOf(tokens, OUTPUT_TOKEN_ASSUMPTION)
        }
        return ConsolidatePlan(
            shards = shards.size,
            inputTokens = shardTokens.sum(),
            messageCount = messages.size,
            estimatedYuan = estimatedYuan,
        )
    }

    /**
     * 跑整理（可能产生 API 费用；调用方必须先拿到用户同意，并在未配置 AI 时不要调用）。
     *
     * 每片的顺序：`MemoryExtractionPrompt.buildSystem/buildUser` → [completeJson] →
     * 非 null 才 [applyPatch] → [recordUsage] → [saveProgress] → [progress] 回调。
     * `doneShards` 里的片**不调用模型、直接跳过**（续跑不重复收费，见类注释「失败与续跑策略」）。
     *
     * @param threadId 目标会话（记忆按 thread 维度写；进度也按 threadId 记）
     * @param plan `plan()` 的产物（只作参考：实际分片以本次读取到的消息为准，
     *   90 天窗口以本次开始时刻重算，数据变动时分片数可能与旧 plan 差 1）
     * @param progress 每处理/跳过一片后回调 `(已成功片数, 总数)`；开始时会先回调一次
     *   `(续跑已成功片数, 总数)`（续跑时第一帧就是断点，不是 0）
     * @param isCancelled 协作式取消（UI 点「停止」）；true 时保留进度、下次续跑
     */
    suspend fun run(
        threadId: String,
        segments: List<ImportedSegment>,
        range: ConsolidateRange,
        plan: ConsolidatePlan,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ConsolidateResult {
        // 90 天窗口以 run 开始时刻为准（与 plan 可能差几分钟；边界消息极端情况下会差 1 条）。
        val messages = loadMemMessages(segments, range, System.currentTimeMillis())
        val shards = BatonPolicy.shard(
            messages = messages,
            shardTokens = SHARD_TOKENS,
            overlap = SHARD_OVERLAP,
        )
        val total = shards.size
        if (total == 0) {
            // 没有可整理的消息：顺手清掉可能残留的旧进度（换 TA/重导后旧进度已无意义）。
            runCatching { clearProgress(threadId) }
            progress(0, 0)
            return ConsolidateResult(
                shardsTotal = 0,
                shardsDone = 0,
                failed = 0,
                estimatedYuan = 0.0,
                cancelled = false,
            )
        }

        // 续跑断点：只认「同一个 thread + 同一批消息（total 相同）」的旧进度。
        // 分片数变了说明消息集合变了（续导/90 天窗口滑动），旧索引不再对应新分片，
        // 硬续会漏片/跳片——宁可本次从头整理（费用确认按全部片估价，不会少报）。
        val stored = runCatching { loadProgress(threadId) }.getOrNull()
            ?.takeIf { it.threadId == threadId && it.totalShards == total }
        val done = (stored?.doneShards.orEmpty())
            .filterTo(LinkedHashSet()) { it in 0 until total }
        val failed = (stored?.failedShards.orEmpty())
            // 成功过的片绝不能再当失败重跑（脏数据防御：done 优先）。
            .filterTo(LinkedHashSet()) { it in 0 until total && it !in done }

        var yuan = 0.0
        var failedThisRun = 0
        var cancelled = false
        progress(done.size, total)

        for (index in 0 until total) {
            // 已成功的片绝不重调 completeJson（「不重复收费」）。
            if (index in done) continue
            if (isCancelled()) {
                cancelled = true
                break
            }
            val shard = shards[index]
            val inputTokens = TokenEstimator.estimateMessages(shard)
            val json = runCatching {
                completeJson(MemoryExtractionPrompt.buildSystem(), MemoryExtractionPrompt.buildUser(shard))
            }.getOrNull()
            if (json == null) {
                // 未配置 / 临时失败：该片进 failedShards（下次续跑重试），不写记忆、不记账，继续下一片。
                failed += index
                failedThisRun++
            } else {
                // applyPatch 失败（坏 JSON / DB 写失败）不阻断整理：这一片没写成，继续下一片。
                runCatching { applyPatch(threadId, json) }
                runCatching { recordUsage(inputTokens, TokenEstimator.estimate(json)) }
                yuan += costOf(inputTokens, OUTPUT_TOKEN_ASSUMPTION)
                done += index
                failed -= index
            }
            // 每片后落一次断点：进度写失败（DataStore 抖动）不能中断已花钱的整理。
            runCatching {
                saveProgress(
                    Consolidation(
                        threadId = threadId,
                        doneShards = done.toSet(),
                        failedShards = failed.toSet(),
                        totalShards = total,
                    ),
                )
            }
            progress(done.size, total)
        }

        // 只有「全部分片成功」才清进度；有失败片或取消都保留断点，下次只跑未完成/失败的片。
        val allDone = !cancelled && done.size >= total
        if (allDone) {
            runCatching { clearProgress(threadId) }
        }
        return ConsolidateResult(
            shardsTotal = total,
            shardsDone = done.size,
            failed = failedThisRun,
            estimatedYuan = yuan,
            cancelled = cancelled,
        )
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 读回所有段的消息 → 按范围过滤 → 时间正序 → 转 [MemMessage]。
     *
     * - 分批读（每批 ≤ [SQLITE_VARIABLE_BATCH]）：避开 SQLite `IN (...)` 变量上限；
     * - [ConsolidateRange.LAST_90_DAYS] 用 `at >= now − 90 天`（**含边界**）；
     * - DB 里已不存在的消息直接跳过（导入后被删）；不按文本重排，同毫秒保持原顺序。
     */
    private suspend fun loadMemMessages(
        segments: List<ImportedSegment>,
        range: ConsolidateRange,
        now: Long,
    ): List<MemMessage> {
        val ids = LinkedHashSet<String>()
        segments.forEach { segment -> ids.addAll(segment.messageIds) }
        if (ids.isEmpty()) return emptyList()

        val byId = HashMap<String, ImportMessage>(ids.size)
        ids.toList().chunked(SQLITE_VARIABLE_BATCH).forEach { batch ->
            loadMessages(batch).forEach { byId[it.id] = it }
        }
        val cutoff = now - NINETY_DAYS_MILLIS
        return ids.asSequence()
            .mapNotNull { byId[it] }
            .filter { message -> range == ConsolidateRange.ALL || message.at >= cutoff }
            .sortedBy { it.at }
            .map { MemMessage(role = it.role, content = it.text, createdAt = it.at) }
            .toList()
    }

    companion object {
        /** 分片大小（40k token 一片）。*/
        const val SHARD_TOKENS: Int = 40_000

        /** 片间重叠消息数（2 条）。*/
        const val SHARD_OVERLAP: Int = 2

        /**
         * 输出 token 上限假设（估算费用用）。
         *
         * 抽取调用统一 `maxTokens = 2000`（`ChatEngine.extractIfDue` / `BatonPolicy` 分片抽取），
         * 所以按「每片最多 2000 输出」估是保守口径；**是估算，不是账单**。
         */
        const val OUTPUT_TOKEN_ASSUMPTION: Int = 2_000

        /** 90 天（毫秒）：`90 × 24h`。 */
        const val NINETY_DAYS_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

        /** `SELECT ... IN (...)` 每批参数上限（每批 ≤500）。*/
        const val SQLITE_VARIABLE_BATCH: Int = 500
    }
}
