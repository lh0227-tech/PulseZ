package cc.pulsez.app.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import cc.pulsez.app.core.ConsolidateRange
import cc.pulsez.app.core.ConsolidateResult
import cc.pulsez.app.data.repo.ImportException
import cc.pulsez.app.data.repo.ImportBatchException
import cc.pulsez.app.data.repo.ImportBatchGroupPlan
import cc.pulsez.app.data.repo.ImportBatchPlan
import cc.pulsez.app.data.repo.ImportBatchResult
import cc.pulsez.app.data.repo.ImportBatchWindowPlan
import cc.pulsez.app.data.repo.ImportSummary
import cc.pulsez.app.data.settings.LastImport
import cc.pulsez.app.importer.deepseek.DeepSeekImporter
import cc.pulsez.app.importer.deepseek.DeepSeekRoleClusterer
import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import cc.pulsez.app.importer.deepseek.DeepSeekParseException
import cc.pulsez.app.importer.deepseek.ImportCache
import cc.pulsez.app.importer.deepseek.ImportErrorCodes
import cc.pulsez.app.importer.deepseek.ZipErrorCodes
import cc.pulsez.app.importer.deepseek.ZipImportException
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 确认屏「TA 叫什么」的预填值（` 名字必填、预填「TA」可改）。*/
const val DEFAULT_IMPORT_NAME: String = "TA"

data class ImportGroupOption(
    val id: String,
    val name: String,
    val persona: String = "",
    val existingCharacterId: String? = null,
    val status: String? = null,
    val userName: String? = null,
    val styleSamples: String? = null,
)

/**
 * 旧聊天导入页的表单状态（`「确认屏（一屏）」、§2 ）。
 *
 * 只放「用户能改的东西」和派生提示；任务本身的进度在 [ImportTaskState]（ 的
 * `ImportTaskStore`）里，页面重进也不丢（`「重进页面从 state 恢复」）。
 *
 * @property name TA 名字（预填「TA」，可改；空则提交时兜底为「TA」）
 * @property persona 一句「TA 是什么样的人」；空 = 中性
 * @property consolidate 是否整理成记忆（**默认不勾**，§2）
 * @property range 整理范围（**默认最近 90 天**，§2）
 * @property aiReady 当前是否已连上 AI（未连时复选框禁用 +「去连接」）
 * @property embedReady 当前是否配好了「找旧事」服务（v0.4.1 配好才显示「顺便……」行）
 * @property embedOnImport 是否顺便让 TA 以后能按意思找到这些旧事（**默认开、可关**；§15）
 * @property semanticIndexing 正在为导入的旧事建向量（导入页据此区分阶段文案，§15）
 * @property resumeLast 选中的 ZIP 与上次成功导入的指纹一致时的上次记录（§6「续导」）
 * @property resumeCharacterName 上次那个 TA 现在的名字（只用于提示条；角色被删则整条提示不显示）
 * @property useResume true = 继续补完上次的 TA；false = 重新导入一份新 TA
 * @property pickError 选包/拷贝阶段的人话错误（选包失败时在 Idle 页显示）
 * @property undoBusy 撤销进行中（防双击）
 * @property undoError 撤销失败的人话提示
 */
data class DeepSeekImportUiState(
    val name: String = DEFAULT_IMPORT_NAME,
    val persona: String = "",
    val consolidate: Boolean = false,
    val range: ConsolidateRange = ConsolidateRange.LAST_90_DAYS,
    val aiReady: Boolean = false,
    val embedReady: Boolean = false,
    val embedOnImport: Boolean = true,
    val semanticIndexing: Boolean = false,
    val resumeLast: LastImport? = null,
    val resumeCharacterName: String? = null,
    val useResume: Boolean = false,
    val pickError: String? = null,
    val undoBusy: Boolean = false,
    val undoError: String? = null,
    val importGroups: List<ImportGroupOption> = emptyList(),
    val assignments: Map<String, String?> = emptyMap(),
    val savedBatchPlan: ImportBatchPlan? = null,
    val alreadyImportedSourceIds: Set<String> = emptySet(),
    val duplicateSourceIdsInZip: Set<String> = emptySet(),
    val sourceDedupeCheckComplete: Boolean = false,
    val sourceDedupeCheckFailed: Boolean = false,
    val awakenOnImport: Boolean = true,
    val autoClustering: Boolean = false,
    val autoClusterError: String? = null,
    val autoClusterSummary: String? = null,
)

/**
 * 旧聊天（DeepSeek 导出 ZIP）导入页的 ViewModel。
 *
 * ## 分层
 * - **不碰 Context / Compose / IO**：SAF 选包与拷进 cache 在 `AppRoot` 的回调里立刻做
 *   （SAF 临时授权只在回调期间可靠，` 阶段 1），这里只收 [File]；
 * - 预检/导入/整理的状态机全部委托给 [ImportTaskStore]（挂在 `appScope`）；
 * - 本类只持有表单状态、任务参数（供「再试一次」）、以及「上次导入」匹配的提示。
 *
 * ## 「继续补完 / 重新导入一份」
 * 指纹 = `zip 文件名 + 字节数`；匹配上且那个 TA 还在时，默认选「继续补完」（不新建重复 TA），
 * 用户可显式改成「重新导入一份」。角色已被删除/软删时整条提示不显示，避免点了报
 * `RESUME_TARGET_MISSING`。
 */
class DeepSeekImportViewModel(private val container: AppContainer) : ViewModel() {

    private val _form = MutableStateFlow(DeepSeekImportUiState())

    /** 表单状态（Compose 侧 `collectAsState`）。 */
    val state: StateFlow<DeepSeekImportUiState> = _form

    /** 导入任务状态（单例；页面重进不丢）。*/
    val taskState: StateFlow<ImportTaskState> = container.importTasks.state

    /**
     * 结果页是否提供「撤销这次导入」。
     *
     * 批次导入按来源精确撤销新增消息；旧的非批次导入仍沿用软删新 TA 的旧语义。判定是纯函数
     * [canUndoImport]，UI 直接复用。
     */
    val canUndo: Boolean
        get() = (container.importTasks.state.value as? ImportTaskState.Done)
            ?.let { canUndoImport(it.summary) } == true

    /** 当前选中的 ZIP（cacheDir 里的临时文件；已拷好，不依赖 SAF 授权）。 */
    private var zipFile: File? = null

    /** 「再试一次」复用的完整参数（含名字/整理选项/续导目标）。 */
    private var lastParams: ImportStartParams? = null

    /** 自动分角色协程（支持随时取消）。 */
    private var autoClusterJob: Job? = null

    init {
        refreshAiReady()
        viewModelScope.launch {
            container.importTasks.state.collect { task ->
                when (task) {
                    is ImportTaskState.Ready -> {
                        matchLastImport(task.inspect.fingerprint)
                        prepareBatchPlan(task.inspect)
                    }
                    is ImportTaskState.Done -> {
                        deleteCachedZip()
                        val batch = task.batch
                        if (batch != null && batch.awakenOnImport && batch.createdOrUpdatedCharacterIds.isNotEmpty()) {
                            container.awakeningManager.enqueueBatch(
                                batchId = batch.batchId,
                                characterIds = batch.createdOrUpdatedCharacterIds,
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        // 建向量阶段的信号：importer 不新增任务 phase（任务状态机不在 作用域），
        // 用这个窄信号把「正在整理」与「正在按意思找旧事」分开说，进度数字仍走 taskState。
        viewModelScope.launch {
            container.deepSeekImporter.semanticIndexing.collect { indexing ->
                _form.value = _form.value.copy(semanticIndexing = indexing)
            }
        }
    }

    // ------------------------------------------------------------------ 选包 / 表单

    /**
     * SAF 选完包、已拷进 `cacheDir` 后由 `AppRoot` 调进来（**必须在回调里拷**，见类注释）。
     *
     * 立刻进入预检（`Idle → Inspecting`）；换了一个文件时顺手删掉上一个临时文件。
     */
    fun onZipPicked(file: File, zipName: String) {
        autoClusterJob?.cancel()
        autoClusterJob = null
        val previous = zipFile
        if (previous != null && previous.absolutePath != file.absolutePath) {
            ImportCache.deleteQuietly(previous)
        }
        zipFile = file
        lastParams = null
        _form.value = _form.value.copy(
            pickError = null,
            resumeLast = null,
            resumeCharacterName = null,
            useResume = false,
            undoError = null,
            importGroups = emptyList(),
            assignments = emptyMap(),
            savedBatchPlan = null,
            alreadyImportedSourceIds = emptySet(),
            duplicateSourceIdsInZip = emptySet(),
            sourceDedupeCheckComplete = false,
            sourceDedupeCheckFailed = false,
            autoClustering = false,
            autoClusterError = null,
            autoClusterSummary = null,
        )
        container.importTasks.inspect(file, zipName)
    }

    /**
     * 选包/拷贝失败（`AppRoot` 把异常带进来）。全部映射成人话，不把异常栈/文件名丢给 UI。
     */
    fun onPickFailed(error: Throwable) {
        val code = when (error) {
            is ZipImportException -> error.code
            is DeepSeekParseException -> error.code
            is ImportException -> error.code
            else -> null
        }
        _form.value = _form.value.copy(
            pickError = if (code != null) importErrorText(code) else "这个文件读不出来。换一个试试？",
        )
        container.importTasks.reset()
    }

    fun onNameChange(value: String) {
        _form.value = _form.value.copy(name = value)
    }

    fun onPersonaChange(value: String) {
        _form.value = _form.value.copy(persona = value)
    }

    fun onConsolidateChange(enabled: Boolean) {
        // 没连 AI 时复选框本来就禁用，这里再兜一层：不联网就绝不产生整理费用。
        if (enabled && !_form.value.aiReady) return
        _form.value = _form.value.copy(consolidate = enabled)
    }

    /**
     * 「顺便让 TA 以后能按意思找到这些旧事」开关（` 默认开、可关）。
     *
     * 只有「找旧事」服务配置齐全时这一行才显示（没配置时 UI 直接不出现）；这里再兜一层：
     * 未配置时不允许打开，避免 UI 状态与真实能力不一致。关掉时导入照常进行，只是
     * chunk 保持关键词-only（`dim=0`），之后可在记忆面板「补建」。
     */
    fun onEmbeddingChange(enabled: Boolean) {
        if (enabled && !_form.value.embedReady) return
        _form.value = _form.value.copy(embedOnImport = enabled)
    }

    fun onAwakenOnImportChange(enabled: Boolean) {
        if (enabled && !_form.value.aiReady) return
        _form.value = _form.value.copy(awakenOnImport = enabled)
    }

    fun autoCluster() {
        val current = _form.value
        if (current.autoClustering || current.savedBatchPlan != null) return
        val ready = container.importTasks.state.value as? ImportTaskState.Ready ?: return
        autoClusterJob?.cancel()
        autoClusterJob = viewModelScope.launch {
            _form.value = _form.value.copy(
                autoClustering = true,
                autoClusterError = null,
                autoClusterSummary = null,
            )
            try {
                val client = runImportOperationCatchingFailure { container.llmClient() }.getOrNull()
                val settings = runImportOperationCatchingFailure { container.settings.flow.first() }.getOrNull()
                if (client == null || settings == null || settings.chatModel.isBlank()) {
                    _form.value = _form.value.copy(
                        aiReady = false,
                        autoClustering = false,
                        autoClusterError = "请先连接对话服务，再让系统帮你区分角色。",
                    )
                    return@launch
                }
                val result = DeepSeekRoleClusterer.forClient(
                    client = client,
                    model = settings.chatModel,
                    baseUrl = settings.chatBaseUrl,
                ).cluster(ready.inspect.sourceWindows)

                val latest = _form.value
                val existingGroups = latest.importGroups.filter { it.existingCharacterId != null }
                val newGroups = result.roles.map { role ->
                    ImportGroupOption(
                        id = "new-${java.util.UUID.randomUUID()}",
                        name = role.name,
                        persona = role.persona,
                        userName = role.userName,
                        styleSamples = role.styleSamples
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString("\u0001"),
                    )
                }
                val nextAssignments = ready.inspect.sourceWindows
                    .associate { it.sourceSessionId to (null as String?) }
                    .toMutableMap()
                result.roles.zip(newGroups).forEach { (role, group) ->
                    role.sessionIds.forEach { sessionId ->
                        if (sessionId in nextAssignments) {
                            nextAssignments[sessionId] = group.id
                        }
                    }
                }
                result.noiseSessionIds.forEach { noiseId ->
                    if (noiseId in nextAssignments) {
                        nextAssignments[noiseId] = null
                    }
                }
                val summaryText = if (result.roles.isNotEmpty()) {
                    "已自动分成 ${result.roles.size} 个 TA 分组，暂跳过 ${result.noiseSessionIds.size} 段杂项，你可以随时手动调整。"
                } else {
                    "这批对话大多像是日常查资料或零散问答，暂时没有分出固定 TA，你可以手动勾选调整。"
                }
                _form.value = latest.copy(
                    importGroups = existingGroups + newGroups,
                    assignments = nextAssignments,
                    autoClustering = false,
                    autoClusterError = null,
                    autoClusterSummary = summaryText,
                )
            } catch (cancel: CancellationException) {
                _form.value = _form.value.copy(autoClustering = false)
                throw cancel
            } catch (_: Throwable) {
                _form.value = _form.value.copy(
                    autoClustering = false,
                    autoClusterError = "没能完成自动分角色。可以再试一次，或先手动分组。",
                )
            }
        }
    }

    fun cancelAutoCluster() {
        autoClusterJob?.cancel()
        autoClusterJob = null
        _form.value = _form.value.copy(autoClustering = false)
    }

    fun onRangeChange(range: ConsolidateRange) {
        _form.value = _form.value.copy(range = range)
    }

    fun addNewImportGroup() {
        val current = _form.value
        if (current.savedBatchPlan != null) return
        val newCount = current.importGroups.count { it.existingCharacterId == null } + 1
        val name = current.name.trim().ifBlank { DEFAULT_IMPORT_NAME }
            .let { if (newCount == 1) it else "$it $newCount" }
        val group = ImportGroupOption(
            id = "new-${java.util.UUID.randomUUID()}",
            name = name,
            persona = current.persona,
        )
        _form.value = current.copy(importGroups = current.importGroups + group)
    }

    fun removeImportGroup(groupId: String) {
        val current = _form.value
        if (current.savedBatchPlan != null) return
        _form.value = current.copy(
            importGroups = current.importGroups.filterNot { it.id == groupId },
            assignments = current.assignments.mapValues { (_, assigned) -> if (assigned == groupId) null else assigned },
        )
    }

    fun changeImportGroupName(groupId: String, name: String) {
        val current = _form.value
        if (current.savedBatchPlan != null) return
        _form.value = current.copy(importGroups = current.importGroups.map { if (it.id == groupId && it.existingCharacterId == null) it.copy(name = name) else it })
    }

    fun changeImportGroupPersona(groupId: String, persona: String) {
        val current = _form.value
        if (current.savedBatchPlan != null) return
        _form.value = current.copy(importGroups = current.importGroups.map { if (it.id == groupId && it.existingCharacterId == null) it.copy(persona = persona) else it })
    }

    fun assignWindow(sourceSessionId: String, groupId: String?) {
        val current = _form.value
        if (current.savedBatchPlan != null) return
        val allowed = groupId == null || current.importGroups.any { it.id == groupId }
        if (!allowed) return
        _form.value = current.copy(assignments = current.assignments + (sourceSessionId to groupId))
    }

    /** 「继续补完（true）/ 重新导入一份（false）」的显式选择（§6）。 */
    fun onResumeChoice(useResume: Boolean) {
        val form = _form.value
        // 继续补完时名字用 TA 原来的（续导会复用已有角色，名字本来也不参与写入）。
        val name = if (useResume) form.resumeCharacterName ?: form.name else form.name
        _form.value = form.copy(useResume = useResume, name = name)
    }

    /**
     * 重新确认「当前有没有连上 AI」，并顺带刷新「找旧事」是否配好（v0.4.1）。
     *
     * 从「去连接」跳到配置页再返回时，页面会重新组合并调到这里；连上后复选框立刻可用，
     * 配好「找旧事」后确认屏才会出现「顺便让 TA 按意思找到这些旧事」那一行。
     */
    fun refreshAiReady() {
        viewModelScope.launch {
            val ready = runImportOperationCatchingFailure { container.llmClient() != null }.getOrDefault(false)
            // 未配置时 `embeddingConfigured()` 为 false：不显示入口、不产生任何网络调用。
            val embedReady = runImportOperationCatchingFailure { container.embeddingConfigured() }.getOrDefault(false)
            val form = _form.value
            _form.value = form.copy(
                aiReady = ready,
                consolidate = if (ready) form.consolidate else false,
                embedReady = embedReady,
                // 未配置时强制关（UI 不显示该行）；刚配好（上一轮还没有）时回到默认开；
                // 已配置时保留用户本次的选择（关掉就一直是关，不偷偷改回来）。
                embedOnImport = when {
                    !embedReady -> false
                    !form.embedReady -> true
                    else -> form.embedOnImport
                },
            )
        }
    }

    // ------------------------------------------------------------------ 任务动作

    /** 「开始导入」：把一屏确认收成 [ImportStartParams]，交给 状态机。*/
    fun start() {
        val ready = container.importTasks.state.value as? ImportTaskState.Ready ?: return
        val file = zipFile ?: return
        val form = _form.value
        if (!form.sourceDedupeCheckComplete || form.sourceDedupeCheckFailed) return
        val plan = form.savedBatchPlan ?: run {
            val chosenGroupIds = form.assignments.values.filterNotNull().toSet()
            if (chosenGroupIds.isEmpty()) return
            val groups = form.importGroups.filter { it.id in chosenGroupIds }.map { group ->
                ImportBatchGroupPlan(
                    id = group.id,
                    targetCharacterId = group.existingCharacterId,
                    name = group.name,
                    persona = group.persona.trim().ifBlank { null },
                    userName = group.userName?.trim()?.ifBlank { null },
                    styleSamples = group.styleSamples?.trim()?.ifBlank { null },
                )
            }
            // One source ID is one logical conversation. Repeated ZIP entries cannot be separately
            // represented by the durable (batchId, sourceSessionId) key, so keep one and warn in preview.
            val windows = ready.inspect.sourceWindows.distinctBy { it.sourceSessionId }.map { preview ->
                ImportBatchWindowPlan(preview, form.assignments[preview.sourceSessionId])
            }
            ImportBatchPlan(
                id = java.util.UUID.randomUUID().toString(),
                fingerprint = ready.inspect.contentFingerprint,
                zipName = ready.zipName,
                groups = groups,
                windows = windows,
                awakenOnImport = form.awakenOnImport && form.aiReady,
            )
        }
        val params = ImportStartParams(
            zip = file,
            zipName = ready.zipName,
            name = form.name.trim().ifBlank { DEFAULT_IMPORT_NAME },
            persona = form.persona.trim().ifBlank { null },
            // 没连 AI 时绝不传 true：整理必须有模型才可能发生。
            // Grouped batches intentionally do not consolidate memory: see ImportRepository.undoBatch.
            consolidate = false,
            range = form.range,
            resumeCharacterId = null,
            batchPlan = plan,
        )
        lastParams = params
        // 用户意愿交给 AppContainer → DeepSeekImporter：默认开、可关；未配置时为 false。
        // 真正是否联网由 `ImportIndexer.embeddingReady()` 再判一次（没配好就一次都不调用）。
        container.importEmbedConsent = form.embedOnImport && form.embedReady
        _form.value = form.copy(undoError = null)
        container.importTasks.start(params)
    }

    /** 费用二次确认（`AwaitingConsent`）：true = 继续整理，false = 先不整理。 */
    fun consent(proceed: Boolean) {
        container.importTasks.confirmConsolidation(proceed)
    }

    /** 用户点「停下」/确认停下：协作式取消，已提交的数据保留。*/
    fun cancel() {
        container.importTasks.cancel()
    }

    /**
     * 「再试一次」：
     * - 已经开始过导入（有完整参数）→ 原样重跑（幂等，不会重复写数据）；
     * - 预检阶段就失败（没有参数）→ 清掉这个包回 Idle，让用户重新选一个。
     */
    fun retry() {
        val params = lastParams
        if (params != null) {
            container.importTasks.start(params)
            return
        }
        val current = container.importTasks.state.value
        val code = (current as? ImportTaskState.Failed)?.code
        deleteCachedZip()
        container.importTasks.reset()
        _form.value = _form.value.copy(
            pickError = code?.let(::importErrorText),
            resumeLast = null,
            resumeCharacterName = null,
            useResume = false,
        )
    }

    /**
     * 撤销这次导入：批次按本批来源精确删除，旧的非批次导入仍软删新 TA。
     * 批次删除与索引清理在 Room 事务内检查批次状态及记忆指纹，失败时不做部分删除。
     *
     * 无新增消息或非批次续导时不执行；UI 也不渲染按钮，这里再兜一层防误调。
     *
     * @param onDone 成功后的导航回调（`AppRoot` 用它 `nav.reset(Route.Threads)`）
     */
    fun undo(onDone: () -> Unit) {
        val done = container.importTasks.state.value as? ImportTaskState.Done ?: return
        val summary = done.summary
        if (!canUndoImport(summary)) return
        if (_form.value.undoBusy) return
        _form.value = _form.value.copy(undoBusy = true, undoError = null)
        viewModelScope.launch {
            try {
                val targetCharacterIds = buildList {
                    if (summary.characterId.isNotBlank()) add(summary.characterId)
                    done.batch?.createdOrUpdatedCharacterIds?.let { addAll(it) }
                }.distinct()
                container.awakeningManager.cancelAndClear(targetCharacterIds, batchId = summary.batchId)
                if (summary.batchId != null) {
                    container.importRepository.undoBatch(summary.batchId)
                } else {
                    container.importRepository.undoImport(summary.characterId)
                    container.importPrefs.clearLastImport(summary.characterId)
                }
                reset()
                _form.value = _form.value.copy(undoBusy = false)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val message = if ((t as? ImportBatchException)?.code == ImportBatchException.MEMORY_CHANGED) {
                    "这次导入后记忆发生过变化。为了保住已有和新记忆，没有撤销任何内容。"
                } else {
                    "没撤销成功。聊天和记忆都保留着，可以再试一次。"
                }
                _form.value = _form.value.copy(undoBusy = false, undoError = message)
            }
        }
    }

    /** 结果页「完成」/离开：清任务与临时 ZIP，回到 Idle（保留下次还要用的「有没有连 AI」）。 */
    fun reset() {
        autoClusterJob?.cancel()
        autoClusterJob = null
        deleteCachedZip()
        lastParams = null
        container.importTasks.reset()
        _form.value = DeepSeekImportUiState(aiReady = _form.value.aiReady)
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 指纹匹配「上次导入」。
     *
     * 只在「同一个 ZIP + 那个 TA 还在」时提示；名字查不到（角色被删）就整条不显示，
     * 免得用户点了续导才报 `RESUME_TARGET_MISSING`（` 续导）。
     */
    private suspend fun matchLastImport(fingerprint: String) {
        val last = runImportOperationCatchingFailure { container.importPrefs.lastImport.first() }.getOrNull()
        val matched = last?.takeIf {
            DeepSeekImporter.fingerprintOf(it.zipName, it.zipSize) == fingerprint
        }
        val character = matched?.let {
            runImportOperationCatchingFailure { container.characters.get(it.characterId) }.getOrNull()
        }
        val form = _form.value
        _form.value = form.copy(
            resumeLast = if (character != null) matched else null,
            resumeCharacterName = character?.name,
            useResume = character != null,
            // 续导时名字输入框显示 TA 原来的名字（续导本身复用旧角色，名字不参与写入）。
            name = character?.name ?: form.name,
        )
    }

    private suspend fun prepareBatchPlan(inspect: DeepSeekImporter.Inspect) {
        _form.value = _form.value.copy(sourceDedupeCheckComplete = false, sourceDedupeCheckFailed = false)
        val roles = runImportOperationCatchingFailure { container.characters.list() }.getOrDefault(emptyList())
        val dedupeCheck = try {
            Result.success(container.importRepository.importedSourceSessionIds(inspect.sourceWindows))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        val saved = runImportOperationCatchingFailure {
            container.importRepository.resumableBatch(inspect.contentFingerprint)
        }.getOrNull()
        val groups = if (saved != null) {
            saved.groups.map { target ->
                val existing = roles.firstOrNull { it.id == target.targetCharacterId }
                ImportGroupOption(
                    id = target.id,
                    name = existing?.name ?: target.name,
                    persona = target.persona.orEmpty(),
                    existingCharacterId = target.targetCharacterId,
                    status = target.status,
                )
            }
        } else {
            roles.map { character ->
                ImportGroupOption(
                    id = "existing-${character.id}",
                    name = character.name,
                    existingCharacterId = character.id,
                )
            }
        }
        val assignments = saved?.windows?.associate { it.preview.sourceSessionId to it.groupId }
            ?: inspect.sourceWindows.associate { it.sourceSessionId to null }
        val form = _form.value
        val duplicateIds = inspect.sourceWindows.groupingBy { it.sourceSessionId }
            .eachCount().filterValues { it > 1 }.keys
        _form.value = form.copy(
            importGroups = groups,
            assignments = assignments,
            savedBatchPlan = saved,
            alreadyImportedSourceIds = dedupeCheck.getOrDefault(emptySet()),
            duplicateSourceIdsInZip = duplicateIds,
            sourceDedupeCheckComplete = true,
            sourceDedupeCheckFailed = dedupeCheck.isFailure,
        )
    }

    /** 删掉 cache 里的临时 ZIP（成功/重置后调用；失败留着给「再试一次」）。 */
    private fun deleteCachedZip() {
        zipFile?.let(ImportCache::deleteQuietly)
        zipFile = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeepSeekImportViewModel(container) }
        }
    }
}

// ------------------------------------------------------------------ 纯函数（可单测）

/**
 * 结果页是否提供「撤销这次导入」。
 *
 * 批次导入只删本批写入的来源；旧的非批次导入仍按旧契约只软删本次新建的 TA。两类都必须
 * 确实写入新消息才提供撤销按钮。
 */
internal fun canUndoImport(summary: ImportSummary): Boolean =
    if (summary.batchId != null) summary.messagesInserted > 0
    else summary.createdNewCharacter && summary.messagesInserted > 0

/**
 * 结果页不给「撤销」时的说明文字（说人话；null = 提供撤销按钮，不显示说明）。
 *
 * 没有新消息时不显示撤销；非批次续导沿用旧限制。
 */
internal fun undoUnavailableHint(summary: ImportSummary): String? = when {
    canUndoImport(summary) -> null
    summary.batchId != null -> "这次没有新增聊天。"
    !summary.createdNewCharacter -> "这次是接着上次导入的，不提供整批撤销。"
    else -> "这次没有新增内容。"
}

/**
 * 结果页「整理」相关的提示（` 建议）。
 *
 * - [Done] 里 `consolidation == null` 且写入了新往事：用户没勾整理（或确认后放弃）→ 引导去记忆面板；
 * - `consolidation.shardsTotal == 0`：两个范围都没有可整理的往事 → 说「没有可整理的旧事」，
 *   不要引导用户再去面板点一次（那会再次空转）；
 * - 其它（真正跑过整理）→ 不提示。
 */
internal fun consolidationDoneHint(
    consolidation: ConsolidateResult?,
    messagesInserted: Int,
): String? = when {
    consolidation != null && consolidation.shardsTotal == 0 -> NOTHING_TO_CONSOLIDATE_TEXT
    consolidation == null && messagesInserted > 0 -> NOT_CONSOLIDATED_TEXT
    else -> null
}

/** 「两个范围都没有可整理的内容」（回退全部后仍 0 片）。 */
internal const val NOTHING_TO_CONSOLIDATE_TEXT: String = "没有可整理的旧事。"

/**
 * 导入页费用确认正文（v0.4.1 文案修复，` 三·#8）。
 *
 * 去掉内部计量单位「片」：付费确认只回答「花多少钱、换什么」。
 * 与记忆面板同一句（`MemoryCopy.consentText`），两处都只说金额（` 数字口径）。
 *
 * @param yuanText 已按 ` 格式化好的金额（如 `¥1.20`）
 */
internal fun importConsentText(yuanText: String): String = "整理成记忆大概 $yuanText，继续吗？"

/** 有旧事但没勾整理 / 放弃整理：引导到记忆面板（面板里再整理只跑未完成片）。 */
internal const val NOT_CONSOLIDATED_TEXT: String =
    "这些往事还没整理成记忆。想让它被记住，可以在 TA 的记忆面板里点「整理往事」。"

/** Exact confirmation copy for legacy soft-hide and the source-scoped batch undo. */
internal fun undoConfirmationText(summary: ImportSummary): String = when {
    summary.batchId == null -> "这个 TA 会从首页隐藏，但已经写入的聊天会保留。"
    summary.createdNewCharacter ->
        "会删除本批新加的聊天。已有聊天会保留；这次新建的 TA 若后来有聊天也会保留，否则会一并删除。记忆有变化时，撤销会拒绝，不会删除任何内容。"
    else ->
        "会删除本批新加的聊天。这个已有 TA、已有聊天和之后新增的聊天都会保留。记忆有变化时，撤销会拒绝，不会删除任何内容。"
}

internal data class FailedImportGroupNotice(val groupName: String, val reason: String)

internal fun failedImportGroupNotices(
    batch: ImportBatchResult?,
    groups: List<ImportGroupOption>,
): List<FailedImportGroupNotice> = batch?.groups.orEmpty()
    .filter { it.errorCode != null }
    .map { result ->
        FailedImportGroupNotice(
            groupName = groups.firstOrNull { it.id == result.groupId }?.name ?: "这个 TA 分组",
            reason = importErrorText(result.errorCode!!),
        )
    }

internal const val FAILED_GROUP_RETRY_HINT: String =
    "点“完成”后重新选择同一个压缩包，可以继续补完；已经完成的分组会跳过，失败的分组会重试。"

/**
 * 导入错误码 → 人话（` 错误码表；文案纪律见 `说人话、给下一步、不出现技术词）。
 *
 * 集中一处，UI 只按码取文案；页面/结果页永远不展示异常栈或文件名。
 */
internal fun importErrorText(code: String): String = when (code) {
    ZipErrorCodes.NOT_ZIP -> "这不是一个压缩包。"
    ZipErrorCodes.BAD_ZIP,
    ZipErrorCodes.ENCRYPTED_OR_DAMAGED,
    -> "这个文件读不出来，可能坏了或者不是 DeepSeek 导出的。重新导出一次试试？"

    DeepSeekParseException.VERSION_UNSUPPORTED ->
        "这个压缩包不是 DeepSeek 新版的导出文件。请在 DeepSeek 里重新导出一次。"

    DeepSeekParseException.MALFORMED_JSON,
    DeepSeekParseException.SESSION_ID_MISMATCH,
    DeepSeekParseException.BAD_TIMESTAMP,
    -> "这组对话文件不完整或格式有误。请重新导出后再试。"

    DeepSeekParseException.EMPTY_SESSION -> "这段对话没有可导入的消息。"

    ImportBatchException.TARGET_MISSING,
    ImportException.RESUME_TARGET_MISSING,
    ImportException.THREAD_MISSING,
    -> "原来选的 TA 已经不存在。重新导出压缩包后重新分组，再导入。"

    ImportException.OPEN_SEGMENT_MISSING -> "这个 TA 现在无法保存聊天；已有内容没有被改动。"
    ImportException.SESSION_FAILED -> "这组聊天没能完整保存，没有留下半段聊天；重新选择同一个压缩包可以重试。"

    ImportErrorCodes.NO_SESSIONS -> "压缩包里没有找到对话。"

    ZipErrorCodes.TOO_LARGE,
    ZipErrorCodes.TOO_MANY_ENTRIES,
    ZipErrorCodes.ENTRY_TOO_LARGE,
    -> "文件有点大，先在电脑上解压删掉图片再导入？"

    ZipErrorCodes.LOW_STORAGE -> "手机空间不够了。清一点再来？"

    ZipErrorCodes.BAD_ENCODING,
    ZipErrorCodes.CRC_MISMATCH,
    -> "文件里有读不懂的内容，换一个导出包试试？"

    ZipErrorCodes.NOT_FOUND -> "找不到这个文件，可能已经被清理了。请重新选择一次。"

    ImportErrorCodes.CANCELLED -> "已停止。已经导入的都保留着。"

    else -> "这次没成。再试一次？"
}

/**
 * 元 → 「¥X」（`「数字」：两位小数；大于 100 不带小数）。
 *
 * 只用于**粗估**展示，不写进任何账单口径。
 */
internal fun formatYuan(yuan: Double): String =
    if (yuan >= 100.0) {
        "¥" + String.format(Locale.CHINA, "%.0f", yuan)
    } else {
        "¥" + String.format(Locale.CHINA, "%.2f", yuan)
    }

/** 「大概 ¥0.10」/「按实际用量算」——费用粗估缺失时宁可说「按实际用量算」，不编数字。 */
internal fun estimatePhrase(yuan: Double?): String =
    if (yuan == null) "按实际用量算" else "大概 ${formatYuan(yuan)}"

/**
 * 预检时间范围 → 「时间大概 2025年3月–2026年11月」/「时间未知」（` 阶段 1）。
 * [zone] 只给单测注入用；生产用系统时区（与 `MemTime` 口径一致）。
 */
internal fun formatImportTimeRange(
    range: LongRange?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (range == null) return "时间未知"
    val yearMonth = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
    fun label(at: Long): String = yearMonth.format(Instant.ofEpochMilli(at).atZone(zone))
    val from = label(range.first)
    val to = label(range.last)
    return if (from == to) "时间大概 $from" else "时间大概 $from–$to"
}

/** A source session start time, distinct from the last-message time used for activity filtering. */
internal fun formatImportSourceStartTime(
    at: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (at == null) return "开始时间未知"
    return DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
        .format(Instant.ofEpochMilli(at).atZone(zone))
}

// ------------------------------------------------------------------ 向量/按意思找旧事

/**
 * 导入页「顺便让 TA 以后能按意思找到这些旧事」的文案（v0.4.1 `）。
 *
 * 文案口径（说人话 ≠ 不解释清楚）：让用户能答出三件事——
 * 1. 这是什么：用**你填的「找旧事」服务**处理这些旧事，让 TA 以后能按意思找到；
 * 2. 谁收钱：费用由**你的服务商**收取（不是 app 收）；
 * 3. 不选会怎样：不勾也能导入，只是以后只能按原话找。
 *
 * 刻意不用「向量 / embedding / 语义索引 / 模型」这类机制词（` 禁用词）；
 * 用「按意思找旧事」复述能力本身（与设置页「找旧事」是同一个名字，用户能对上）。
 * 集中在这里是为了让 `DeepSeekImportViewModelTest` 逐字锁定 + 检查禁用词。
 */
internal object ImportSemanticCopy {

    /** 确认屏复选框标题（仅当已配置「找旧事」时显示；默认开、可关）。 */
    const val OPTION: String = "顺便让 TA 以后能按意思找到这些旧事"

    /** 复选框说明（是什么 / 费用谁收 / 不勾会怎样）。 */
    const val HINT: String =
        "用你填的「找旧事」服务处理这些旧事，费用由你的服务商收取，通常很少。" +
            "不勾也能导入，只是以后只能按原话找。"

    /** 建向量阶段的进度文案（与「正在整理成记忆」区分：这是另外一件事、另外一份钱）。 */
    const val PHASE: String = "正在让 TA 以后能按意思找到这些旧事……"
}

internal object ImportAutoClusterCopy {
    const val BUTTON: String = "帮我自动分角色并写好人设"
    const val RUNNING: String = "正在翻看对话并区分角色……"
    const val HINT: String =
        "自动挑出属于同一个 TA 的多段对话，替每位 TA 起好名字、写好人设并筛除日常查资料杂项。这会调用你的对话服务，花费不多，具体以服务商账单为准。"
    const val NEED_AI_HINT: String = "先去连接对话服务，才能自动分角色并写好人设。"
}

internal object ImportAwakenCopy {
    const val TITLE: String = "让 TA 先回忆一遍旧聊天"
    const val HINT: String =
        "开启后，TA 会先翻看你们聊过的往事，记住你的称呼与习惯；第一次点进聊天时，TA 会先一桩桩想起这些事再和你重逢。这会调用你的对话服务，花费不多，具体以服务商账单为准。如果先不开，聊天记录也会完整导进来供你翻看，只是刚开聊时 TA 还想不起以前的细节。"
}

/**
 * 导入进度阶段文案。
 *
 * 索引阶段有两种含义，必须分开说：
 * - 普通索引（关键词）：正在整理旧事；
 * - 建向量（[semanticIndexing] = true）：正在让 TA 按意思找到旧事——这一步会调用用户的
 *   「找旧事」服务（可能有很少的费用），不能和「整理成记忆」混成一句。
 */
internal fun importPhaseText(phase: ImportTaskState.Running.Phase, semanticIndexing: Boolean): String =
    when (phase) {
        ImportTaskState.Running.Phase.IMPORT -> "导入中"
        ImportTaskState.Running.Phase.INDEX ->
            if (semanticIndexing) ImportSemanticCopy.PHASE else "正在整理"
        ImportTaskState.Running.Phase.CONSOLIDATE -> "正在整理成记忆"
    }
