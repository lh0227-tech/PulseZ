package cc.pulsez.app.ui.importer

import cc.pulsez.app.core.ConsolidatePlan
import cc.pulsez.app.core.ConsolidateRange
import cc.pulsez.app.core.ConsolidateResult
import cc.pulsez.app.data.repo.ImportException
import cc.pulsez.app.data.repo.ImportBatchPlan
import cc.pulsez.app.data.repo.ImportBatchResult
import cc.pulsez.app.data.repo.ImportSummary
import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.data.settings.ImportPrefs
import cc.pulsez.app.importer.deepseek.DeepSeekImporter
import cc.pulsez.app.importer.deepseek.DeepSeekParseException
import cc.pulsez.app.importer.deepseek.ImportCache
import cc.pulsez.app.importer.deepseek.ImportErrorCodes
import cc.pulsez.app.importer.deepseek.ZipImportException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 导入任务执行器的抽象（依赖倒置，v0.4 建议）。
 *
 * 生产实现是 [DeepSeekImporter]；测试用 fake 实现即可在**不构造 Room/AppContainer** 的前提下
 * 驱动 [ImportTaskStore] 的完整状态机（预检/导入/确认/整理/取消/重置）。
 *
 * 为什么定义在这里（任务层）：调用方 [ImportTaskStore] 只依赖这个窄接口，`AppContainer` 不用改
 * （它继续传 `DeepSeekImporter` 子类型）；`DeepSeekImporter` 为了 `override` 反向 import 本接口，
 * 这是**刻意的一次性依赖**，只为让状态机可测，不改变导入业务语义。
 *
 * 签名必须与 [DeepSeekImporter] 的同名方法逐字一致（含回调与可空性），由编译期校验。
 */
interface ImportRunner {

    /** 阶段 1 预检（只读中央目录，见 `DeepSeekImporter.inspect`）。 */
    suspend fun inspect(zip: File, zipName: String): DeepSeekImporter.Inspect

    /** 阶段 2 导入（见 `DeepSeekImporter.import`；回调/取消/确认语义原样透传）。 */
    suspend fun import(
        zip: File,
        zipName: String,
        name: String,
        persona: String?,
        consolidate: Boolean,
        range: ConsolidateRange,
        resumeCharacterId: String?,
        onProgress: (DeepSeekImporter.Progress) -> Unit,
        onIndexProgress: (Int, Int) -> Unit,
        onConsolidateProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        requiresConsent: suspend (ConsolidatePlan) -> Boolean,
    ): DeepSeekImporter.ImportOutcome

    /** 用户确认后继续记忆整理（见 `DeepSeekImporter.resumeConsolidation`）。 */
    suspend fun resumeConsolidation(
        threadId: String,
        segments: List<ImportedSegment>,
        range: ConsolidateRange,
        plan: ConsolidatePlan,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
    ): ConsolidateResult

    /** `consentPending` 时保存的续跑上下文（importer 写入，store 确认时消费）。 */
    val pendingConsolidation: DeepSeekImporter.PendingConsolidation?
}

/** Production-only extension for grouped imports; legacy test runners remain source-compatible. */
interface BatchImportRunner {
    suspend fun importBatch(
        zip: File,
        plan: ImportBatchPlan,
        onProgress: (DeepSeekImporter.Progress) -> Unit,
        onIndexProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
    ): DeepSeekImporter.ImportOutcome
}

/**
 * 进程级导入任务状态（`「appScope 单例任务」、§4.7）。
 *
 * 语义（计划 §4.7「规则」）：
 * - 同一时刻只允许一个导入/预检任务（[busy] 单槽 + 协作式取消）；
 * - 离开页面任务继续跑；重进页面从 [state] 恢复（store 挂在 `AppContainer.appScope`）；
 * - 所有异常都变成 [ImportTaskState.Failed]（错误码 + 人话），不把异常抛给 UI；
 * - [ImportTaskState.Done] 保留状态供结果页读取，不自动 reset（`reset()` 回 Idle）；
 * - **不碰 Compose / Context**：只依赖作用域、importer、导入偏好。
 */
sealed interface ImportTaskState {

    /** 没有任务（初始/重置后）。 */
    data object Idle : ImportTaskState

    /** 正在做阶段 1 预检（只读中央目录，≤2s 目标）。 */
    data class Inspecting(val zipName: String) : ImportTaskState

    /** 预检完成，等确认屏读取。 */
    data class Ready(
        val zipName: String,
        val inspect: DeepSeekImporter.Inspect,
    ) : ImportTaskState

    /**
     * 导入/索引/整理进行中。
     *
     * @property messages 累计已写入的消息数（结果页/进度文案用）
     */
    data class Running(
        val phase: Phase,
        val done: Int,
        val total: Int,
        val messages: Int,
    ) : ImportTaskState {
        enum class Phase { IMPORT, INDEX, CONSOLIDATE }
    }

    /**
     * 费用计划超过阈值，等用户确认（` 「>¥1 或超日上限二次确认」）。
     *
     * 此时导入与索引已完成，**整理费用尚未发生**；确认后 store 用 importer 保存的上下文继续。
     */
    data class AwaitingConsent(
        val summary: ImportSummary,
        val plan: ConsolidatePlan,
    ) : ImportTaskState

    /** 导入成功（或用户取消整理后）的终态；保留供结果页/撤销读取。 */
    data class Done(
        val summary: ImportSummary,
        val indexed: Boolean,
        val consolidation: ConsolidateResult?,
        val batch: ImportBatchResult? = null,
    ) : ImportTaskState

    /** 失败终态。@property code 原因码（UI 按码给人话）；@property detail 人话说明。 */
    data class Failed(val code: String, val detail: String?) : ImportTaskState
}

/** 「开始导入」的参数（确认屏收集，` 确认屏）。*/
data class ImportStartParams(
    val zip: File,
    val zipName: String,
    val name: String,
    val persona: String?,
    val consolidate: Boolean,
    val range: ConsolidateRange,
    val resumeCharacterId: String?,
    val batchPlan: ImportBatchPlan? = null,
)

/**
 * 导入任务的进程级单例。
 *
 * 并发模型：`busy`（AtomicBoolean）相当于单槽 Mutex + 「job.isActive」的组合——预检/导入/
 * 确认后整理三者互斥；重复点击直接忽略（验收「双击不重复启动」）。
 * 取消是协作式的：`cancel()` 只置标志，importer 在会话/分片边界检查，已提交的数据保留。
 *
 * ## cache ZIP 生命周期（v0.4 建议 `「清理」行）
 * cache 里的临时 ZIP 由本类统一持有并在**终态**清理，不依赖页面 ViewModel 存活：
 * - [ImportTaskState.Done]（成功 / 用户放弃整理 / 停止整理后完成）→ 删；
 * - 用户取消（[ImportTaskState.Failed]，code=`CANCELLED`）→ 删（结果页不再提供「再试一次」）；
 * - [reset]（结果页「完成」/重新选包/离开页面）→ 删；
 * - **普通 [ImportTaskState.Failed]（可重试）保留 ZIP**，供 `retry()` 原样重跑（幂等，不重复写数据）；
 * - 导入中离开页面导致 ViewModel 销毁、无人调 [reset] 的场景，由 `PulsezApp` 启动时
 *   `ImportCache.cleanupStale`（24h 前残留）兜底。
 *
 * **顺序约束**：所有"删 ZIP"必须发生在发布对应终态**之前**（同一协程内先删文件、后写 StateFlow）。
 * 反过来写会留下竞态：`state.first { it is Done }` 的收集方可能在删除语句执行前就被唤醒，
 * 因此收集到 Done 时，临时 ZIP 必须已经删除。
 *
 * @param requiresConsent 调用方的额外确认规则（例如「计划金额超过当日剩余额度」）。
 *   **¥1 规则由 importer 判定**；这里返回 true 时同样进 [ImportTaskState.AwaitingConsent]。
 *   默认 `{ false }`，`AppContainer` 会接当日额度判断。
 */
class ImportTaskStore(
    private val scope: CoroutineScope,
    private val importer: ImportRunner,
    private val prefs: ImportPrefs,
    private val requiresConsent: suspend (ConsolidatePlan) -> Boolean = { false },
    private val saveLastImport: suspend (zipName: String, zipSize: Long, characterId: String, importedAt: Long) -> Unit =
        { zipName, zipSize, characterId, importedAt ->
            prefs.setLastImport(zipName, zipSize, characterId, importedAt)
        },
) {

    private val _state = MutableStateFlow<ImportTaskState>(ImportTaskState.Idle)

    /** 当前状态；UI 直接 collect（进程内共享）。 */
    val state: StateFlow<ImportTaskState> = _state.asStateFlow()

    /**
     * 当前任务对应的 cache ZIP（[inspect] / [start] 时记下，终态清理）。
     *
     * 进程级持有：即使导入页 ViewModel 被销毁（离开页面、任务继续跑），成功/取消后仍能删掉
     * 临时文件（v0.4 建议 旧实现只有 ViewModel 删，重进页面拿不到 File 就永远不删）。
     */
    @Volatile
    private var currentZip: File? = null

    /** 单槽互斥：预检/导入/确认后整理同一时刻只跑一个。 */
    private val busy = AtomicBoolean(false)

    /** 导入结束、等用户确认时用；`confirmConsolidation` 只允许消费一次。 */
    private val consolidationStarted = AtomicBoolean(false)

    private val cancelRequested = AtomicBoolean(false)

    /** `reset()` 后抑制在途 job 的结果写入（否则会被迟到的 Done/Failed 覆盖回旧状态）。 */
    @Volatile
    private var suppressResults = false

    @Volatile
    private var activeJob: Job? = null

    /** 最近一次导入进度里的累计消息数（索引/整理阶段的 Running 也要带上）。 */
    @Volatile
    private var lastMessages: Int = 0

    private val pendingLock = Any()
    private var pending: DeepSeekImporter.PendingConsolidation? = null
    private var pendingSummary: ImportSummary? = null
    private var pendingIndexed: Boolean = false

    // ------------------------------------------------------------------ 预检

    /**
     * 阶段 1 预检（选包后调用）。忙时直接忽略（防双入口/双击）。
     *
     * 成功 → [ImportTaskState.Ready]；失败 → [ImportTaskState.Failed]。
     */
    fun inspect(zip: File, zipName: String) {
        if (!beginTask()) return
        // 换包/重进页面后重新选包：顺手清掉上一个任务的临时文件（「下次选包覆盖」路径，
        // 覆盖「离开页面但没人 reset」时遗留的 ZIP）。同一个文件（重新预检）则保留。
        val previous = currentZip
        if (previous != null && previous.absolutePath != zip.absolutePath) {
            ImportCache.deleteQuietly(previous)
        }
        // 进程级持有 ZIP：即使页面 ViewModel 后来被销毁，终态（成功/取消/reset）也能删掉它。
        currentZip = zip
        _state.value = ImportTaskState.Inspecting(zipName)
        val job = scope.launch {
            try {
                val result = importer.inspect(zip, zipName)
                // 预检是短任务：先释放单槽、再发布 Ready（与 AwaitingConsent 同一写法），
                // 避免调用方看到 Ready 后立刻 start 撞上还没释放的单槽。
                finishTask()
                if (!suppressResults) {
                    _state.value = ImportTaskState.Ready(zipName, result)
                }
            } catch (e: CancellationException) {
                finishTask()
                if (!suppressResults) {
                    // 先删 ZIP、再发布终态：调用方看到 Failed(CANCELLED) 时文件必须已删，
                    // 不能先发布状态再删文件，否则异步收集方可能先收到终态、后看到文件删除。
                    if (cancelRequested.get()) deleteCurrentZip()
                    _state.value = if (cancelRequested.get()) {
                        cancelledState()
                    } else {
                        ImportTaskState.Failed(ImportErrorCodes.IMPORT_FAILED, ImportErrorText.of(ImportErrorCodes.IMPORT_FAILED))
                    }
                }
            } catch (t: Throwable) {
                finishTask()
                if (!suppressResults) _state.value = failedState(t)
            }
        }
        activeJob = job
    }

    // ------------------------------------------------------------------ 开始导入

    /**
     * 阶段 2 导入（确认屏点「开始导入」）。
     *
     * 流程：Running(IMPORT) → Running(INDEX) → （可选）Running(CONSOLIDATE) →
     * Done / AwaitingConsent / Failed。成功或部分成功都会写 `ImportPrefs.lastImport`，
     * 保证「继续上次导入」可用。
     */
    fun start(params: ImportStartParams) {
        if (!beginTask()) return
        currentZip = params.zip
        synchronized(pendingLock) {
            pending = null
            pendingSummary = null
            pendingIndexed = false
        }
        consolidationStarted.set(false)
        lastMessages = 0
        _state.value = ImportTaskState.Running(ImportTaskState.Running.Phase.IMPORT, done = 0, total = 0, messages = 0)

        val job = scope.launch {
            try {
                val onProgress: (DeepSeekImporter.Progress) -> Unit = { progress ->
                        lastMessages = progress.messages
                        if (!suppressResults) {
                            _state.value = ImportTaskState.Running(
                                phase = ImportTaskState.Running.Phase.IMPORT,
                                done = progress.done,
                                total = progress.total,
                                messages = progress.messages,
                            )
                        }
                    }
                val onIndexProgress: (Int, Int) -> Unit = { done, total ->
                        if (!suppressResults) {
                            _state.value = ImportTaskState.Running(
                                phase = ImportTaskState.Running.Phase.INDEX,
                                done = done,
                                total = total,
                                messages = lastMessages,
                            )
                        }
                    }
                val onConsolidateProgress: (Int, Int) -> Unit = { done, total ->
                        if (!suppressResults) {
                            _state.value = ImportTaskState.Running(
                                phase = ImportTaskState.Running.Phase.CONSOLIDATE,
                                done = done,
                                total = total,
                                messages = lastMessages,
                            )
                        }
                    }
                val outcome = if (params.batchPlan != null) {
                    val batchRunner = importer as? BatchImportRunner
                        ?: throw IllegalStateException("Grouped import is unavailable")
                    batchRunner.importBatch(
                        zip = params.zip,
                        plan = params.batchPlan,
                        onProgress = onProgress,
                        onIndexProgress = onIndexProgress,
                        isCancelled = { cancelRequested.get() },
                    )
                } else importer.import(
                    zip = params.zip,
                    zipName = params.zipName,
                    name = params.name,
                    persona = params.persona,
                    consolidate = params.consolidate,
                    range = params.range,
                    resumeCharacterId = params.resumeCharacterId,
                    onProgress = onProgress,
                    onIndexProgress = onIndexProgress,
                    onConsolidateProgress = onConsolidateProgress,
                    isCancelled = { cancelRequested.get() },
                    requiresConsent = { plan -> requiresConsent(plan) },
                )

                // 指纹：成功与「中途停止但有已提交内容」都要落，续导靠它。
                if (outcome.summary.batchId == null && outcome.summary.characterId.isNotBlank()) {
                    runImportOperationCatchingFailure {
                        saveLastImport(
                            params.zipName,
                            params.zip.length(),
                            outcome.summary.characterId,
                            System.currentTimeMillis(),
                        )
                    }
                }

                if (suppressResults) return@launch
                when {
                    cancelRequested.get() -> {
                        // 协作式停止：已提交会话保留；不进入整理（「确认停下并保留」）。
                        // 先删 ZIP 再发布终态（顺序要求见类注释「cache ZIP 生命周期」）：
                        // 结果页对 CANCELLED 不提供「再试一次」，看到终态时文件必须已删。
                        deleteCurrentZip()
                        _state.value = cancelledState()
                    }

                    outcome.consentPending && outcome.consolidationPlan != null -> {
                        val context = importer.pendingConsolidation
                        synchronized(pendingLock) {
                            pending = context
                            pendingSummary = outcome.summary
                            pendingIndexed = outcome.indexed
                        }
                        // 从「导入 job」切到「等确认」：先释放单槽，再发布状态——UI 看到
                        // AwaitingConsent 后立刻 confirm 才不会撞上还没释放的单槽。
                        busy.set(false)
                        activeJob = null
                        _state.value = ImportTaskState.AwaitingConsent(
                            summary = outcome.summary,
                            plan = outcome.consolidationPlan,
                        )
                        return@launch
                    }

                    else -> {
                        // 成功终态：先删 ZIP 再发布 Done。调用方（结果页/测试）看到 Done 时
                        // 文件必须已不存在，避免收集方在终态发布后仍看到临时文件。
                        deleteCurrentZip()
                        _state.value = ImportTaskState.Done(
                            summary = outcome.summary,
                            indexed = outcome.indexed,
                            consolidation = outcome.consolidation,
                            batch = outcome.batch,
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (!suppressResults) {
                    // 用户取消（协作式停止）是终态：先删 ZIP 再发布状态；job 自身被取消则
                    // 保留给「再试一次」。
                    if (cancelRequested.get()) deleteCurrentZip()
                    _state.value = if (cancelRequested.get()) {
                        cancelledState()
                    } else {
                        ImportTaskState.Failed(ImportErrorCodes.IMPORT_FAILED, ImportErrorText.of(ImportErrorCodes.IMPORT_FAILED))
                    }
                }
            } catch (t: Throwable) {
                if (!suppressResults) _state.value = failedState(t)
            } finally {
                // 已切到「等确认」时单槽已提前释放；这里不能误清 confirm 拿到的锁。
                if (!consolidationStarted.get()) finishTask()
            }
        }
        activeJob = job
    }

    // ------------------------------------------------------------------ 确认 / 取消 / 重置

    /**
     * 用户对 `AwaitingConsent` 的决定：`true` = 继续整理（跑 的 run），`false` = 放弃整理。
     *
     * 只消费一次；非 [ImportTaskState.AwaitingConsent] 状态调用直接忽略。
     */
    fun confirmConsolidation(proceed: Boolean) {
        val current = _state.value as? ImportTaskState.AwaitingConsent ?: return
        if (!consolidationStarted.compareAndSet(false, true)) return

        val context = synchronized(pendingLock) {
            val snapshot = pending
            pending = null
            PendingConfirmation(
                context = snapshot,
                summary = pendingSummary,
                indexed = pendingIndexed,
            )
        }
        val summary = context.summary ?: current.summary
        if (!proceed || context.context == null) {
            busy.set(false)
            // 先删 ZIP 再发布 Done（终态时文件必须已删；见类注释「cache ZIP 生命周期」）。
            deleteCurrentZip()
            _state.value = ImportTaskState.Done(summary, context.indexed, null)
            return
        }

        val ctx = context.context
        cancelRequested.set(false)
        suppressResults = false
        busy.set(true)
        _state.value = ImportTaskState.Running(
            phase = ImportTaskState.Running.Phase.CONSOLIDATE,
            done = 0,
            total = ctx.plan.shards,
            messages = lastMessages,
        )
        val job = scope.launch {
            try {
                val result = importer.resumeConsolidation(
                    threadId = ctx.threadId,
                    segments = ctx.segments,
                    range = ctx.range,
                    plan = ctx.plan,
                    onProgress = { done, total ->
                        if (!suppressResults) {
                            _state.value = ImportTaskState.Running(
                                phase = ImportTaskState.Running.Phase.CONSOLIDATE,
                                done = done,
                                total = total,
                                messages = lastMessages,
                            )
                        }
                    },
                    isCancelled = { cancelRequested.get() },
                )
                if (!suppressResults) {
                    // 整理取消也回 Done：导入数据已安全，"取消"只停止再花钱（结果里 cancelled=true）。
                    // 先删 ZIP 再发布 Done（终态时文件必须已删）。
                    deleteCurrentZip()
                    _state.value = ImportTaskState.Done(summary, context.indexed, result)
                }
            } catch (e: CancellationException) {
                if (!suppressResults) {
                    // 用户取消（协作式停止）同样是终态：先删 ZIP 再发布状态；异常取消保留给
                    // 「再试一次」。
                    if (cancelRequested.get()) deleteCurrentZip()
                    _state.value = if (cancelRequested.get()) {
                        cancelledState()
                    } else {
                        ImportTaskState.Failed(ImportErrorCodes.IMPORT_FAILED, ImportErrorText.of(ImportErrorCodes.IMPORT_FAILED))
                    }
                }
            } catch (t: Throwable) {
                if (!suppressResults) _state.value = failedState(t)
            } finally {
                finishTask()
            }
        }
        activeJob = job
    }

    /**
     * 协作式取消：只置取消标志，交给 importer 在会话/分片边界停下。
     *
     * - 导入中：在途 job 结束后进 [ImportTaskState.Failed]（`CANCELLED`）；已提交数据保留，
     *   cache ZIP 删除（结果页对 CANCELLED 不提供「再试一次」，见类注释「cache ZIP 生命周期」）。
     * - 等确认时（没有在途 job）：等价于放弃整理，进 [ImportTaskState.Done]（导入已完成），ZIP 删除。
     * - Done/Failed/Idle：无操作（终态由 `reset()` 清）。
     */
    fun cancel() {
        val current = _state.value
        if (current is ImportTaskState.AwaitingConsent) {
            if (!consolidationStarted.compareAndSet(false, true)) return
            val context = synchronized(pendingLock) {
                val snapshot = pending
                pending = null
                PendingConfirmation(pendingSummary, pendingIndexed, snapshot)
            }
            // 导入已完成、整理被放弃 = 终态：先删 ZIP 再发布 Done（终态时文件必须已删）。
            deleteCurrentZip()
            _state.value = ImportTaskState.Done(
                summary = context.summary ?: current.summary,
                indexed = context.indexed,
                consolidation = null,
            )
            return
        }
        if (!busy.get()) return
        cancelRequested.set(true)
    }

    /**
     * 回 Idle（结果页「完成」/重新选包前/清空所有数据时调用）。在途任务的结果会被抑制，
     * 不会覆盖 Idle。
     *
     * v0.5.4：除「抑制结果」外**还要真的取消在途 job**
     * （[activeJob]）。只设置协作式取消标志和 `suppressResults` 时，任务仍会运行到 importer
     * 的下一个检查点；清空期间如果它随后写入，就可能把导入内容写回已清空的数据库。因此这里
     * 直接 `cancel()`：已提交的数据保留（与协作式取消同口径），不会写回已清空的库。
     *
     * `AppRoot.runClear` 在**进入执行的那一刻**调用此方法（早于
     * `ExportService.clearAll`），不是成功后；这样 clearAll 执行期间在途导入也已被取消，
     * 不会出现「DB 清完、导入 job 又写回」。清空失败时**可能已部分删除**（分步执行、无统一回滚），导入任务仍回到 Idle。
     *
     * 同时删掉当前任务对应的 cache ZIP：离开页面（包括 Ready 还没开始、导入中被放弃）时
     * 不应把完整导出包留在 cache 里。
     */
    fun reset() {
        // 无条件置抑制：任何迟到的 Done/Failed/进度都不得覆盖本次 reset 之后的 Idle。
        // （beginTask() 会在下一个任务开始时把它复位为 false，不影响正常路径。）
        suppressResults = true
        cancelRequested.set(true)
        // 真的停掉在途协程（v0.5.4）；它自己的 CancellationException 分支被 suppressResults
        // 挡住，不会再发布 Failed。finishTask() 在 finally 里把 busy/activeJob 清干净。
        activeJob?.cancel()
        // 在途 job 在 POSIX 上已打开的句柄不受删除影响；未打开时它会因 NOT_FOUND 静默失败，
        // 这正是「放弃任务」的期望结果（结果已被 suppressResults 抑制）。
        deleteCurrentZip()
        synchronized(pendingLock) {
            pending = null
            pendingSummary = null
            pendingIndexed = false
        }
        consolidationStarted.set(false)
        _state.value = ImportTaskState.Idle
    }

    // ------------------------------------------------------------------ 内部

    /** 拿单槽并复位本轮标志；已被占用返回 false（防双击/双入口）。 */
    private fun beginTask(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        cancelRequested.set(false)
        suppressResults = false
        return true
    }

    private fun finishTask() {
        busy.set(false)
        activeJob = null
    }

    /**
     * 删掉当前任务的 cache ZIP 并清掉引用（幂等）。
     *
     * 只在**终态**调用（Done / 用户取消 / reset）；普通 [ImportTaskState.Failed] 保留文件，
     * 让「再试一次」能用同一份 ZIP 幂等重跑（`「清理」行、v0.4 建议）。
     */
    private fun deleteCurrentZip() {
        val zip = currentZip ?: return
        currentZip = null
        ImportCache.deleteQuietly(zip)
    }

    private fun cancelledState(): ImportTaskState.Failed = ImportTaskState.Failed(
        code = ImportErrorCodes.CANCELLED,
        detail = ImportErrorText.of(ImportErrorCodes.CANCELLED),
    )

    private fun failedState(t: Throwable): ImportTaskState.Failed {
        val code = when (t) {
            is ZipImportException -> t.code
            is DeepSeekParseException -> t.code
            is ImportException -> t.code
            else -> ImportErrorCodes.IMPORT_FAILED
        }
        return ImportTaskState.Failed(code, ImportErrorText.of(code))
    }

    /** `confirmConsolidation` / `cancel` 取上下文的快照（避免持锁做状态切换）。 */
    private class PendingConfirmation(
        val summary: ImportSummary?,
        val indexed: Boolean,
        val context: DeepSeekImporter.PendingConsolidation?,
    )
}

/**
 * 错误码 → 人话（` 错误码表；UI 若已有同一套映射可直接用码，detail 只作兜底）。
 *
 * 文案纪律：说人话、不出现 token / 上下文 / 窗口 / 向量。
 */
internal object ImportErrorText {

    fun of(code: String): String = importErrorText(code)
}
