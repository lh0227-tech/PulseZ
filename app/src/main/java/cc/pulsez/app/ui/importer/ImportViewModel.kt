package cc.pulsez.app.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.repo.BackupRestoreRepository
import cc.pulsez.app.data.repo.RestoreResult
import cc.pulsez.app.importer.BackupDocument
import cc.pulsez.app.importer.BackupInspection
import cc.pulsez.app.importer.BackupIssue
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.BackupPreview
import cc.pulsez.app.importer.Format
import cc.pulsez.app.importer.ImportPipeline
import cc.pulsez.app.importer.ParsedImport
import cc.pulsez.app.importer.Source
import cc.pulsez.app.importer.Speaker
import cc.pulsez.app.importer.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入页状态（v0.5.2 前半新增备份两态）。
 *
 * 互斥不变量（[applyInspection] 保证）：`backup` 与 `backupRejected` 永不同时非空；
 * `raw` 只在普通解析路径承载原文——备份原文任何时刻都不得进 `raw`（输入框）。
 */
data class ImportUiState(
    val raw: String = "",
    val parsed: ParsedImport? = null,
    /** 认出来的备份预览（识别 + 恢复）。*/
    val backup: BackupPreview? = null,
    /** 备份被拒的确定性原因（绝不降级为人设解析）。 */
    val backupRejected: BackupIssue? = null,
    /** 恢复执行的可见状态；null = 还没点「恢复」（预览态）。*/
    val restore: RestoreUi? = null,
    val name: String = "",
    val persona: String = "",
    val speakerSide: Speaker = Speaker.UNKNOWN,
    val warnings: List<String> = emptyList(),
    val busy: Boolean = false,
    val createdId: String? = null,
    val error: String? = null,
)

/**
 * 恢复执行的三态。
 *
 * `Running` 的 `(done, total)` 由仓库按**实际 plan 的会话数**（跳过孤儿后更小）回调；
 * 首个回调前 UI 用 `restoreProgress` 的 `total<=0` 兜底文案。
 */
sealed interface RestoreUi {
    data class Running(val done: Int, val total: Int) : RestoreUi
    data class Done(val result: RestoreResult.Restored) : RestoreUi
    data object Failed : RestoreUi
}

/**
 * 角色导入。
 *
 * 流程：粘贴/选文件/分享 → **先判是不是 PulseZ 备份**（[BackupParser.inspect]）→
 * 备份走预览→恢复→结果（起真的能装回来）；普通内容走解析 → 预览确认（含"哪一方是 TA"）→ 入库。
 * 解析失败不抛异常，只给 warning；用户永远能改。
 *
 * 可测性说明：[inspector] / [pipelineParse] / [backupParser] /
 * [restorer] 都是 **suspend** 函数类型，测试才能让它们挂起、验证 `busy` 防重入与状态机；
 * [workDispatcher] 让测试把解析线程固定到 `StandardTestDispatcher`。生产默认值 = 原行为 / 真实仓库。
 */
class ImportViewModel(
    private val container: AppContainer,
    private val inspector: suspend (String, Source) -> BackupInspection =
        { text, source -> BackupParser.inspect(text, source) },
    private val pipelineParse: suspend (String, Source) -> ParsedImport =
        { text, source -> ImportPipeline.parse(text, source) },
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** 备份文本 → 字段级文档（注入缝；默认真实 `BackupParser.parseBackup`）。*/
    private val backupParser: suspend (String) -> BackupDocument =
        { text -> BackupParser.parseBackup(text) },
    /** 执行恢复（注入缝；默认真实仓库，生产接线 `container.database` + `container.importIndexer`）。*/
    private val restorer: suspend (BackupDocument, (Int, Int) -> Unit) -> RestoreResult =
        { doc, onProgress ->
            BackupRestoreRepository(container.database, container.importIndexer).restore(doc, onProgress)
        },
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /**
     * 创建本 ViewModel 时的清空代际。
     *
     * `AppContainer.wipeEpoch` 变大 = 用户开始执行「清空所有数据」：本页还在跑的
     * 「恢复备份」/「创建角色」必须取消并丢弃迟到写入，不能把清空前的库写回。
     * 取消的依赖是既有语义：`BackupRestoreRepository.restore` 用**整包一个 Room 事务**且
     * `CancellationException` 必重抛（`data/repo/BackupRestoreRepository.kt:117-151`）→
     * 取消 = 事务回滚；`createWithThread` + 逐条 append 在挂起点取消同样回滚/中止。
     *
     * 不用 `drop(1)`：VM 构造与收集器启动之间若刚好清空，首帧就是新代际，drop 会漏。
     */
    private val wipeEpochAtCreation: Long = container.wipeEpoch.value

    /** 正在跑的「恢复备份」job（v0.5.4：清空开始执行时取消）。*/
    private var restoreJob: Job? = null

    /** 正在跑的「创建角色」job（同上）。 */
    private var createJob: Job? = null

    /**
     * 正在跑的「识别 / 解析」job（v0.5.6 清空开始执行时一并取消）。
     *
     * 之前 `parse()` / `onShareText()` 用裸 `viewModelScope.launch { inspectAndParse(…) }`，
     * job 没被持有 → 清空时取消不到：`inspectAndParse` 在 `inspector` / `backupParser` /
     * `pipelineParse` 挂起返回后会写回 `pendingBackupDoc`/`pendingBackupText` 与预览态，
     * 清空后的旧备份又变成「待恢复」，`restore()` 里按**点击时**代际取基准的守卫拦不住。
     * 取消是「第一道」，挂起返回后的代际复核（[stopIfWiped]）是「第二道」——覆盖
     * 未经过本字段的调用（如文件入口由 `AppRoot` 直接调 `inspectAndParse`）。
     */
    private val inspectJobs = mutableSetOf<Job>()

    init {
        // v0.5.4 清空代际一变 → 立刻取消在途恢复/创建/识别（清空之后不该再有写入）。
        viewModelScope.launch {
            container.wipeEpoch.collect { epoch ->
                if (epoch != wipeEpochAtCreation) cancelJobsForWipe()
            }
        }
    }

    /**
     * 清空开始执行后的收口：取消在途 job、清掉待恢复内容，并把页面状态整体复位到初始。
     *
     * v0.5.5（+）：只取消 job + 复位 `busy`/`restore` 不够——
     * `backup` / `backupRejected` / `parsed` / 草稿与 [pendingBackupDoc] 都还会留在**本实例**里；
     * 一旦这个实例因 key 命中（旧 key 被复用、或另一个 Activity 实例仍在用旧 key）重新上屏，
     * 旧备份预览仍可被「恢复」一键写回已清空的库。所以这里把待恢复缓存（[clearPendingBackup]）
     * 与整个 [ImportUiState] 一起复位到初始态。
     *
     * v0.5.6：在途**识别/解析**（[inspectJobs]）也必须取消——之前它没被持有，清空后
     * 挂起返回仍会重填 `pendingBackupDoc`/`pendingBackupText` 与预览（旧备份可一键恢复）。
     *
     * 正常路径的 `resetToEdit()` / 识别 / 恢复语义**不受影响**：本函数只在清空代际变化时调用。
     */
    private fun cancelJobsForWipe() {
        restoreJob?.cancel()
        restoreJob = null
        createJob?.cancel()
        createJob = null
        val inspections = synchronized(inspectJobs) {
            inspectJobs.toList().also { inspectJobs.clear() }
        }
        inspections.forEach { it.cancel() }
        clearPendingBackup()
        // ① 取消的幂等复位：旧实例的重组不会卡在「恢复/创建/识别进行中」（v0.5.4 行为）。
        _state.value = _state.value.copy(busy = false, restore = null)
        // ② v0.5.5：整体回到初始态——清空前的备份预览、解析草稿、名字/人设、恢复结果与
        //    「创建完成」信号（createdId）都不能留给可能被复用的旧实例（否则旧备份仍可一键恢复）。
        //    刻意不重置 lastSharedText：本实例已消费过的分享文本不应因清空而重新消费（第二道防线）；
        //    换一份**不同**文本仍可注入（温启动：同一 VM 上第二条不同文本必须生效）。
        _state.value = ImportUiState()
    }

    /** 清空代际是否已不同于 [startEpoch]（v0.5.4 迟到写入判定）。*/
    private fun wipeChangedSince(startEpoch: Long): Boolean =
        container.wipeEpoch.value != startEpoch

    /** 注册所有尚未完成的识别 job，避免新分享覆盖仍在途的旧 job。 */
    private fun launchInspection(text: String, source: Source) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            inspectAndParse(text, source)
        }
        synchronized(inspectJobs) { inspectJobs.add(job) }
        job.invokeOnCompletion {
            synchronized(inspectJobs) { inspectJobs.remove(job) }
        }
        job.start()
    }

    private fun hasActiveInspection(): Boolean = synchronized(inspectJobs) {
        inspectJobs.any { it.isActive }
    }

    /**
     * 挂起点返回后的清空复核（v0.5.6 第二道防线）。
     *
     * 清空代际已变时返回 true，调用方必须直接返回，不得再写待恢复缓存或页面状态。
     * 整页复位由 wipeEpoch 收集器负责；迟到的旧识别不能清空新一代操作已经写入的状态。
     */
    private fun stopIfWiped(startEpoch: Long): Boolean {
        return wipeChangedSince(startEpoch)
    }

    /**
     * 分享入口已消费的**文本**（第二道防线；AppRoot 侧还有按文本去重的第一道）。
     *
     * v0.5.6：从「实例只消费一次」的布尔值改成**记下已消费的文本**——同一 Activity
     * 温启动再分享一份**不同**文本时（v0.5.6 起经事件流到达这里），旧的布尔值会把第二条
     * 静默丢掉（的第二层）。仍保证「同一份文本只识别一次」。
     * 刻意不在清空收口里重置（见 [cancelJobsForWipe]）：本实例已消费过的文本不应重新消费。
     */
    private var lastSharedText: String? = null

    /**
     * 待恢复文档缓存（**不改 `ImportStep.kt`**）。
     *
     * 预览态下 `inspectAndParse` 会即时解析：解析成功缓存文档；失败只缓存原文（恢复时再试一次），
     * **绝不把已判定的 Preview 降级成 Normal/Rejected**。
     */
    private var pendingBackupDoc: BackupDocument? = null
    private var pendingBackupText: String? = null

    fun onRawChange(text: String) {
        _state.value = _state.value.copy(raw = text, error = null)
    }

    /**
     * 解析粘贴进来的内容（PASTE/编辑步「看看是什么」按钮）。
     *
     * 薄包装：读当前 `raw` → 空则保留既有提示 → 启动协程走
     * [inspectAndParse]（**也判是不是备份**）。普通粘贴行为不变。
     */
    fun parse(source: Source = Source.PASTE) {
        val raw = _state.value.raw
        if (raw.isBlank()) {
            // 回归项：空 raw 不置 busy、不调两个注入缝、状态除 error 外不变。
            _state.value = _state.value.copy(error = "没读到内容。再看看是不是复制漏了？")
            return
        }
        if (_state.value.busy || hasActiveInspection()) return
        // v0.5.6 识别 job 必须被持有，清空开始时才能取消。
        launchInspection(raw, source)
    }

    /**
     * 文件/分享/粘贴统一入口：先识别备份，再决定走预览/拒绝/普通解析。
     *
     * - 空白文本守卫在**第一行**：静默、不动任何状态、不调注入缝；`AppRoot` 文件回调
     *   因此不再自己判空（空文件/全空白保持静默）；
     * - 第二行 `busy` 防重入：FILE/SHARE 回调直调本方法会绕过 [parse] 的守卫，必须自己挡；
     * - `inspect` 与 `pipelineParse` 都切到 [workDispatcher]（生产 = Default，解析可能读完
     *   5 MB 文本，不能占主线程；`withContext` 也确保注入缝可挂起）；
     * - `Normal` → 写 `raw` 后走原解析逻辑；`Preview/Rejected` → 纯状态转换，`busy=false`。
     * - v0.5.6：进入时记下发起时代际；**每个 `withContext` 挂起返回后、写
     *   `pendingBackupDoc`/`pendingBackupText`/`_state` 之前**都按它复核（[stopIfWiped]）。
     *   清空已开始则直接返回：不写任何预览/原文/待恢复缓存（job 取消是第一道，这是第二道，
     *   覆盖未经 [inspectJobs] 跟踪的调用，如 `AppRoot` 文件入口直接调用本方法）。
     */
    suspend fun inspectAndParse(text: String, source: Source) {
        if (text.isBlank()) return
        if (_state.value.busy) return
        // v0.5.6 发起时代际（后续每个挂起点返回后都复核）。
        val startEpoch = container.wipeEpoch.value
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val inspection = withContext(workDispatcher) { inspector(text, source) }
            // ① `inspector` 挂起返回后：清空已开始 → 不判预览、不写任何状态/缓存。
            if (stopIfWiped(startEpoch)) return
            when (inspection) {
                is BackupInspection.Preview -> {
                    // 预览态即时解析文档，供「恢复」按钮直接用。
                    // **不能用 runCatching**：它会把 CancellationException 吞成「解析失败」后继续。
                    val doc = withContext(workDispatcher) {
                        try {
                            backupParser(text)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            null
                        }
                    }
                    // ② `backupParser` 挂起返回后：清空已开始 → 不写 pending、不写预览。
                    if (stopIfWiped(startEpoch)) return
                    // 解析失败不降级：只缓存原文，恢复时再试；用户仍停在预览态。
                    pendingBackupDoc = doc
                    pendingBackupText = if (doc == null) text else null
                    _state.value = applyInspection(_state.value, inspection)
                        .copy(busy = false, error = null, restore = null)
                }

                is BackupInspection.Rejected -> {
                    clearPendingBackup()
                    _state.value = applyInspection(_state.value, inspection)
                        .copy(busy = false, error = null, restore = null)
                }

                BackupInspection.Normal -> {
                    clearPendingBackup()
                    val parsed = withContext(workDispatcher) { pipelineParse(text, source) }
                    // ③ `pipelineParse` 挂起返回后：清空已开始 → 不写原文/草稿/预览。
                    if (stopIfWiped(startEpoch)) return
                    val guessed = ImportPipeline.guessSpeaker(parsed.turns)
                    val personaDraft = if (parsed.turns.size >= ImportPipeline.MIN_CHAT_TURNS) {
                        ImportPipeline.personaFromTurns(parsed.turns)
                    } else {
                        parsed.persona.orEmpty()
                    }
                    // 普通内容：清掉旧的备份两态，再把原文交给编辑/预览路径（与旧 parse 行为一致）。
                    val cleared = applyInspection(_state.value, BackupInspection.Normal)
                    _state.value = cleared.copy(
                        raw = text,
                        parsed = parsed,
                        name = parsed.name.orEmpty(),
                        persona = personaDraft,
                        speakerSide = guessed,
                        warnings = parsed.warnings,
                        busy = false,
                        error = null,
                        // 重新识别后不得残留上次恢复结果（I-6）。
                        restore = null,
                    )
                }
            }
        } catch (ce: CancellationException) {
            // 清空取消时不能让旧 job 覆盖复位态或清空后新任务的 busy 状态。
            if (!wipeChangedSince(startEpoch)) _state.value = _state.value.copy(busy = false)
            throw ce
        } catch (t: Throwable) {
            // 失败也可能迟到于清空；先复核发起代际，再允许旧识别写 busy/error。
            if (wipeChangedSince(startEpoch)) return
            _state.value = _state.value.copy(busy = false, error = "这段内容没整理成，再试一次？")
        }
    }

    /**
     * 分享入口：同一份文本只消费一次。
     *
     * `AppRoot` 的分享注入效应在重组/重进页面时可能再跑一次；第一次之后直接 return——
     * 不重复 `inspect`、不重复弹旧状态。注意跨导航的 VM 状态保持现状（R8）。
     * v0.5.6：分享文本改由 Activity 级事件流下发（温启动的不同文本也能到这里），
     * 所以去重按**文本内容**比较：同一份文本不重复识别，不同文本照常识别（原来的实例布尔
     * `shareConsumed` 会把同一 VM 上的第二条不同文本静默丢掉）。AppRoot 侧还有 first-pass
     * 去重（`ShareConsumption.shouldInject`），这是第二道。
     * v0.5.6：所有识别 job 都被持有（[inspectJobs]），清空开始时一并取消。
     */
    fun onShareText(text: String) {
        if (text == lastSharedText) return
        // 识别中收到的新分享直接丢弃，不登记为已消费，也不创建可覆盖旧 job 的空任务。
        if (_state.value.busy || hasActiveInspection()) return
        lastSharedText = text
        launchInspection(text, Source.SHARE)
    }

    /**
     * 回初始编辑步（备份两态的「取消 / 换一个文件」与恢复后「好」都走它）。
     *
     * 除清草稿与备份两态，还必须清 `busy`/`createdId`（防旧 `createdId` 重导航）。
     * `clearedForEdit` 只列出已知字段，并用 `state.copy` 生成，
     * **不会**替本包清 `restore` 与待恢复缓存，所以这里显式清（双保险，必须项）。
     */
    fun resetToEdit() {
        clearPendingBackup()
        _state.value = clearedForEdit(_state.value).copy(restore = null, busy = false)
    }

    /** 清掉待恢复文档缓存（非预览态 / 复位 / 重新识别时都要清，防残留旧备份被误恢复）。 */
    private fun clearPendingBackup() {
        pendingBackupDoc = null
        pendingBackupText = null
    }

    /**
     * 点「恢复」（或失败态「再试一次」）→ 预览→执行→结果状态机。
     *
     * 语义（逐条对应 §6.3 I）：
     * - 第二行 `busy` 守卫：`busy` 在启动协程**之前**同步置 true → 双击第二次立刻 return；
     * - `Done` 后不再重复（已成功不再恢复）；
     * - 没有待恢复文档（`doc`/`text` 都空）→ **静默、状态零变化**；
     * - `CancellationException` 必须重抛（禁止 `runCatching`；A-3）；
     * - 失败的 `Failed` 只代表事务前/事务内失败（索引阶段异常已在仓库内收敛成 `indexSkipped`），
     *   所以 `RESTORE_FAILED` 的「原来的数据没有动」成立。
     */
    fun restore() {
        val current = _state.value
        if (current.busy) return
        if (current.restore is RestoreUi.Done) return
        val document = pendingBackupDoc
        val text = pendingBackupText
        if (document == null && text == null) return
        _state.value = current.copy(
            busy = true,
            error = null,
            restore = RestoreUi.Running(0, 0),
        )
        // v0.5.4 清空开始执行时本 VM 的恢复必须作废（库已被清，写回就是复活旧数据）。
        val runWipeEpoch = container.wipeEpoch.value
        restoreJob = viewModelScope.launch {
            val doc = document ?: withContext(workDispatcher) {
                try {
                    backupParser(text!!)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    null
                }
            }
            if (doc == null) {
                _state.value = _state.value.copy(busy = false, restore = RestoreUi.Failed)
                return@launch
            }
            // v0.5.4 清空已发生 → 连事务都不开（restorer 是整包一个 Room 事务，开了也会被清掉/写回）。
            if (wipeChangedSince(runWipeEpoch)) {
                _state.value = _state.value.copy(busy = false, restore = null)
                return@launch
            }
            // 不预填 total：进度 total 由仓库按**实际 plan 的会话数**（跳过孤儿后更小）在首个
            // onProgress(done,total) 里回调；预填 doc.threads.size 会让进度停在 n/m。
            val result = try {
                restorer(doc) { done, total ->
                    if (!wipeChangedSince(runWipeEpoch)) {
                        _state.value = _state.value.copy(restore = RestoreUi.Running(done, total))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                RestoreResult.Failed
            }
            // v0.5.4 清空后不落结果态（旧实例已不上屏；写结果无意义，避免误导航）。
            if (wipeChangedSince(runWipeEpoch)) return@launch
            _state.value = _state.value.copy(
                busy = false,
                restore = if (result is RestoreResult.Restored) RestoreUi.Done(result) else RestoreUi.Failed,
            )
        }
    }

    fun onNameChange(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun onPersonaChange(value: String) {
        _state.value = _state.value.copy(persona = value)
    }

    /**
     * 快速克隆（「再建一个像 TA 的」）：把当前草稿的 name/persona 预填成新角色，
     * 直接回到可编辑的确认页。
     *
     * 刻意**不带旧对话与旧 raw**：克隆的是人设与说话样子，不是把历史复制一份；
     * 用户改完名字就能当新角色用（`create()` 的 parsed 走这条空 turns 草稿）。
     * v0.5.2：同时清空备份两态，保证与 `parsed` 互斥、不会残留旧备份预览。
     */
    fun prefill(name: String, persona: String) {
        // 返回导入初始步不能残留恢复结果/待恢复文档。
        clearPendingBackup()
        _state.value = _state.value.copy(
            raw = "",
            parsed = ParsedImport(
                name = name.takeIf { it.isNotBlank() },
                persona = persona.takeIf { it.isNotBlank() },
                greeting = null,
                styleSamples = emptyList(),
                turns = emptyList(),
                format = Format.TEXT,
                warnings = emptyList(),
            ),
            backup = null,
            backupRejected = null,
            restore = null,
            name = name,
            persona = persona,
            speakerSide = Speaker.UNKNOWN,
            warnings = emptyList(),
            busy = false,
            createdId = null,
            error = null,
        )
    }

    /** 消费"创建完成"信号：不消费会导致再次进入导入页时立刻跳回上一个角色。 */
    fun consumeCreated() {
        if (_state.value.createdId != null) {
            _state.value = _state.value.copy(createdId = null)
        }
    }

    fun chooseSpeaker(side: Speaker) {
        val parsed = _state.value.parsed ?: return
        val applied = ImportPipeline.applySpeakerChoice(parsed, side)
        val personaDraft = ImportPipeline.personaFromTurns(applied.turns)
        _state.value = _state.value.copy(
            parsed = applied,
            speakerSide = side,
            persona = personaDraft.ifBlank { _state.value.persona },
        )
    }

    fun create() {
        val state = _state.value
        if (state.busy) return
        val name = state.name.trim()
        if (name.isEmpty()) {
            _state.value = state.copy(error = "先起个名字吧。")
            return
        }
        // v0.5.4 清空开始执行时本 VM 的创建必须作废（不写角色/会话/消息）。
        val runWipeEpoch = container.wipeEpoch.value
        createJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            // 已清空 → 连角色都不建（否则给已清空的库补一个孤儿角色，清空后还会出现在首页）。
            if (wipeChangedSince(runWipeEpoch)) {
                _state.value = _state.value.copy(busy = false)
                return@launch
            }
            val created = runImportOperationCatchingFailure {
                val (character, thread) = container.characters.createWithThread(
                    name = name,
                    persona = state.persona.trim(),
                    greeting = state.parsed?.greeting,
                    styleSamples = state.parsed?.styleSamples.orEmpty(),
                    sourceType = state.parsed?.format?.name ?: "PASTE",
                    rawImported = state.raw,
                )
                // 往事落库：TA 说的进 assistant，用户说的进 user；UNKNOWN 丢弃
                val segmentId = thread.activeSegmentId
                if (segmentId != null) {
                    state.parsed?.turns.orEmpty().forEach { turn ->
                        if (turn.speaker == Speaker.UNKNOWN) return@forEach
                        // v0.5.4 每条写库前再按代际收口（清空后不再追加消息）。
                        if (wipeChangedSince(runWipeEpoch)) return@forEach
                        container.chat.append(
                            threadId = thread.id,
                            segmentId = segmentId,
                            role = if (turn.speaker == Speaker.CHARACTER) "assistant" else "user",
                            text = turn.text,
                            tokens = turn.text.length / 2 + 4,
                            createdAt = turn.at ?: System.currentTimeMillis(),
                        )
                    }
                }
                character
            }.getOrNull()
            // v0.5.4 清空已发生 → 不置 createdId（否则 AppRoot 会导航到已删角色）、不报错。
            if (wipeChangedSince(runWipeEpoch)) {
                _state.value = _state.value.copy(busy = false)
                return@launch
            }
            if (created != null) {
                _state.value = _state.value.copy(busy = false, createdId = created.id)
            } else {
                _state.value = _state.value.copy(busy = false, error = "没建成功。再试一次？")
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImportViewModel(container) }
        }
    }
}

/** 便于 UI 层显示的一方样例。 */
fun List<Turn>.samplesOf(speaker: Speaker, limit: Int = 2): List<String> =
    filter { it.speaker == speaker }.take(limit).map { it.text.take(60) }
