package cc.pulsez.app.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import cc.pulsez.app.core.ConsolidatePlan
import cc.pulsez.app.core.ConsolidateRange
import cc.pulsez.app.core.EmbedPort
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.core.embedWithRetry
import cc.pulsez.app.data.db.ChunkEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.repo.ImportedSegment
import cc.pulsez.app.data.settings.isFullyConsolidated
import cc.pulsez.app.mem.MemoryTable
import cc.pulsez.app.mem.VectorMath
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆分组的 kind（与 `data` 层的 `MemoryItemEntity.kind` 同名；RELATION 不显示「别再提」）。 */
internal const val MEMORY_KIND_RELATION: String = "RELATION"

/**
 * 记忆面板文案。
 *
 * 全部集中在这里，UI 不写死字符串：文案纪律要求「说人话、不出现
 * 内部技术词、角色代词用 TA」，集中一处才好逐字核对（见 `MemoryPanelLogicTest`）。
 * 标语/分组/空状态与 `–§4.7、` 附录 A 的收敛稿逐字一致。
 * v0.4.1 按用户 2026-11-20 反馈修订了两处（` 三·#7/#8）：
 * 分组标题改成以用户为主语（[GROUP_PREF]），费用确认去掉内部单位「片」（[consentText]）。
 */
internal object MemoryCopy {

    /** 标题。*/
    const val TITLE: String = "TA 记得的事"

    /** 顶部说明（首次进入即可见；` 收敛稿）。*/
    const val CAPTION: String = "这里是被整理过的重点，不是每一句话。记错或不想留的，可以忘掉或让 TA 别再提。"

    /** 空状态。*/
    const val EMPTY: String = "这里还是空的。聊到的事会整理在这里，不想留的可以随时忘掉，或让 TA 以后别再提。"

    /** 分组标题（`「别碰的地方」→「不想被提起的」）。*/
    const val GROUP_FACT: String = "TA 记得的你"
    const val GROUP_RELATION: String = "你们之间"
    const val GROUP_LOOP: String = "还没说完的事"
    const val GROUP_PREF: String = "你喜欢和不喜欢的事"
    const val GROUP_BOUNDARY: String = "不想被提起的"

    /** 每行菜单（`每行右侧「…」）。*/
    const val MENU = "这一条的更多操作"
    const val FORGET: String = "忘掉"
    const val BOUNDARY: String = "以后别再提这件事"

    /** 有导入旧事时的提示块与「整理往事」入口。*/
    const val IMPORT_TIP: String = "这些是导入的旧事，不整理也会留着；想让 TA 记成长期重点可以点整理，按你的服务商计费（预估超过 ¥1 会先问你）。"
    const val CONSOLIDATE: String = "整理往事"
    const val CONSOLIDATE_AGAIN: String = "再整理一些"
    const val CONSOLIDATING: String = "正在整理……"
    const val CONSOLIDATE_CANCEL: String = "停下"
    const val PROGRESS_RUNNING_PREFIX: String = "正在整理 "
    const val PROGRESS_PARTIAL_PREFIX: String = "上次整理到 "

    /**
     * 老数据「补建」（v0.4.1 `）：让 TA 以后能**按意思**找到导入的旧事。
     *
     * 与「整理往事」是两件事，必须分开说：
     * - 整理往事 = 把旧事缩成 TA 记得的要点（用聊天 AI，按片计费）；
     * - 补建 = 用**你填的「找旧事」服务**给导入的旧事建检索用的向量（通常很便宜）。
     *
     * 文案要说清：这是什么 / 费用谁收 / 不做会怎样，不用机制词。
     */
    const val BACKFILL_TIP: String =
        "这些导入的旧事，现在只能按原话找。用你填的「找旧事」服务处理一下，" +
            "TA 以后就能按意思找到。费用由你的服务商收取，通常很少。"
    const val BACKFILL: String = "让 TA 能按意思找旧事"
    const val BACKFILL_RETRY: String = "再试一次"
    const val BACKFILLING: String = "正在处理……"
    const val BACKFILL_PROGRESS_PREFIX: String = "正在处理 "
    const val BACKFILL_CANCEL: String = "停下"
    const val NOTICE_BACKFILLED: String = "好了。以后 TA 能按意思找到这些旧事。"
    const val NOTICE_BACKFILL_FAILED: String = "做到一半停住了。做好的保留着，回头再试。"
    const val NOTICE_BACKFILL_CANCELLED: String = "停下了。做好的保留着。"
    const val NOTICE_BACKFILL_NOTHING: String = "没有需要处理的旧事。"
    const val NOTICE_BACKFILL_NEED_SERVICE: String = "先在设置里加一个「找旧事」，再回来处理。"

    /** 删除反馈（` 附录 A 4.7：改成**可兑现**的「以后不会再用这条」）。*/
    const val NOTICE_DELETED: String = "删掉了。以后不会再用这条。"
    const val NOTICE_DELETE_FAILED: String = "没删掉，再试一次？"

    /** 「别再提」（`边界是用户对记忆的直接控制）。*/
    const val NOTICE_BOUNDARY_ADDED: String = "不会主动提这件事了。"
    const val NOTICE_BOUNDARY_FAILED: String = "没记下来，再试一次？"

    /** 未连 AI（` 无 AI 时面板给入口 + 一行说明）。*/
    const val NOTICE_NEED_AI: String = "先在设置里连上 AI，再回来整理。"
    const val OPEN_SETTINGS: String = "去连接"

    /** 整理结果（` 失败也保留已整理的，不重复花钱）。*/
    const val NOTICE_CONSOLIDATED: String = "整理好了。有不对或不想留的，可以忘掉或让 TA 别再提。"
    const val NOTICE_CONSOLIDATE_FAILED: String = "整理到一半停住了。已经整理的保留着，回头再试。"
    const val NOTICE_CONSOLIDATE_CANCELLED: String = "停下了。已经整理的那些保留着。"

    /**
     * 「最近 90 天」为空时面板会自动回退整理「全部」；全部也为空才提示这句。
     *
     * 不写「最近 90 天」：回退之后再说 90 天会误导（建议）。
     */
    const val NOTICE_NOTHING_AT_ALL: String = "没有可整理的旧事。"
    const val NOTICE_NO_IMPORT: String = "这个 TA 还没有导入的旧事。"

    /** 费用二次确认（` >¥1 才问；正文在一行内说清金额）。*/
    const val CONSENT_CONTINUE: String = "继续"
    const val CONSENT_CANCEL: String = "先不整理"

    /**
     * 确认框正文（v0.4.1 文案修复，` 三·#8）。
     *
     * 去掉内部计量单位「片」：用户在付费确认这一屏只需要看懂「花多少钱、买什么」；
     * 「片」是分片跑模型的内部概念，` 的数字口径只允许 字/条/时间/钱。
     *
     * @param shards 分片数：**不再上屏**，保留参数只为不改调用方签名（估价与续跑逻辑不变）
     */
    fun consentText(yuan: Double, shards: Int): String =
        "整理成记忆大概 ${formatConsentYuan(yuan)}，继续吗？"
}

/** 整理费用超过这个金额（元）要二次确认（` >¥1）。*/
internal const val CONSOLIDATE_CONSENT_YUAN_THRESHOLD: Double = 1.0

/**
 * 面板内提示右侧引导按钮的去向（显式目标，UI 按枚举分派）。
 *
 * 公开（非 `internal`）原因：公开的 [MemoryUiState] 构造器/`copy` 带这个字段，
 * 枚举若为 internal 会触发 "public function exposes its internal parameter type"。
 *
 * 为什么不用提示语字符串判断：文案随时可能改，
 * 靠"提示语 == 某常量"决定进哪个设置页时，新增/改提示语会静默走进错误的页面，
 * 编译器和现有测试都发现不了。改成枚举后，去向和提示一起由
 * [noticeOpenTargetFor] 给出，UI 只认枚举。
 */
enum class NoticeOpenTarget {
    /** 「未连 AI」→ 聊天设置页（`Route.Config`）。 */
    CHAT,

    /** 「先在设置里加一个找旧事」→ 找旧事设置页（`Route.FindOldThings`）。 */
    FIND_OLD_THINGS,
}

/**
 * 记忆面板（`标题统一为「TA 记得的事」，角色代词只用 TA）。
 *
 * 只展示仍然有效的条目；删除立即生效，下一轮 prompt 里就没了。
 *
 * @property groups 分组（空 = 面板空状态）
 * @property total 有效条目数（首页「有 N 个 TA 记得你」口径不变）
 * @property importPending 该 thread 有导入旧事且还没被整段整理过（顶部提示块 + 整理入口）
 * @property consolidateProgress 整理进度（"3 / 8"）：运行中实时更新；没在跑时是上次没整理完的
 *   （分子是**已成功片数**，不是「已尝试」）
 * @property consentPlan 预估花费 >¥1 时等用户确认的计划（UI 弹确认框）；null = 不用确认/已确认
 * @property consentRange 与 [consentPlan] 配套的实际整理范围（90 天空时已回退到「全部」）
 * @property notice 面板内一行反馈（删除/别再提/整理结果/未连 AI）
 * @property noticeOpenTarget notice 右侧引导按钮的去向；null = 这条提示不显示按钮。
 *   由 ViewModel 在设置提示语时用 [withNotice] 按 [noticeOpenTargetFor] 一起给出
 *   。
 * @property consolidating 整理进行中（按钮禁用，防双击重复花钱）
 * @property semanticBackfillNeeded 有「导入的旧事」还没按意思处理过（`dim=0/model="none"` 的
 *   IMPORT chunk），且「找旧事」已配置 → 面板显示「让 TA 能按意思找旧事」（v0.4.1
 *   ` ）。**不自动执行、不静默花钱**：要用户自己点。
 * @property backfillProgress 补建进度（"3 / 10"）；没在跑时为 null
 * @property backfilling 补建进行中（按钮禁用 + 显示「停下」，防双击重复花钱）
 */
data class MemoryUiState(
    val groups: List<MemoryGroup> = emptyList(),
    val loading: Boolean = true,
    val total: Int = 0,
    val importPending: Boolean = false,
    val consolidateProgress: String? = null,
    val consentPlan: ConsolidatePlan? = null,
    val consentRange: ConsolidateRange? = null,
    val notice: String? = null,
    val noticeOpenTarget: NoticeOpenTarget? = null,
    val consolidating: Boolean = false,
    val semanticBackfillNeeded: Boolean = false,
    val backfillProgress: String? = null,
    val backfilling: Boolean = false,
)

data class MemoryGroup(val title: String, val kind: String, val items: List<String>)

class MemoryViewModel(
    private val container: AppContainer,
    private val characterId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    private var threadId: String = ""

    /**
     * 「停下」用的协作式取消标志（`ImportMemoryConsolidator.run` 在分片边界检查）。
     *
     * 整理任务挂 `AppContainer.appScope`：离开面板不中断（规划 §4.7 的任务口径），
     * 想停就点「停下」；进度按片写进 `ImportPrefs`，重进/重跑从断点续。
     */
    private val cancelRequested = AtomicBoolean(false)

    /**
     * 「补建」的协作式取消标志（` 「可取消」）。
     *
     * 与整理的取消分开：两个动作可以独立发起，共用一个标志会互相串取消。
     */
    private val backfillCancelRequested = AtomicBoolean(false)

    /**
     * 创建本 ViewModel 时的清空代际。
     *
     * `AppContainer.wipeEpoch` 变大 = 用户清空了所有数据：本 VM 的「整理往事 / 补建索引」都是
     * 进程级 `appScope` 任务（离开面板不中断），必须由这里**主动取消**并丢弃迟到写入，
     * 否则会把清空前的内容（记忆 patch / chunk 向量 / 「已整理」标记）写回已清空的库。
     *
     * 不用 `drop(1)`：VM 构造与收集器启动之间若刚好清空，首帧就是新代际，drop 会漏（与
     * ChatViewModel 同一理由）。
     */
    private val wipeEpochAtCreation: Long = container.wipeEpoch.value

    /** 正在跑的整理 job（v0.5.4：清空时代际一变就取消）。*/
    private var consolidateJob: Job? = null

    /** 正在跑的补建 job（同上）。 */
    private var backfillJob: Job? = null

    init {
        refresh()
        // v0.5.4 清空代际一变 → 立刻取消在途整理/补建（否则会把旧内容写回已清空的库）。
        viewModelScope.launch {
            container.wipeEpoch.collect { epoch ->
                if (epoch != wipeEpochAtCreation) cancelJobsForWipe()
            }
        }
    }

    /** 清空开始执行后的收口：取消两个在途任务、清按钮状态；已提交的片由清空流程负责删。 */
    private fun cancelJobsForWipe() {
        consolidateJob?.cancel()
        consolidateJob = null
        backfillJob?.cancel()
        backfillJob = null
        // 协作式标志也置上：两个 run 内部在分片边界还会检查它（双保险）。
        cancelRequested.set(true)
        backfillCancelRequested.set(true)
        _state.update { it.copy(consolidating = false, backfilling = false) }
    }

    /** 清空代际是否已不同于 [startEpoch]（v0.5.4 迟到写入判定）。*/
    private fun wipeChangedSince(startEpoch: Long): Boolean =
        container.wipeEpoch.value != startEpoch

    /**
     * 刷新面板：记忆条目 + 导入旧事提示 + 上次整理进度。
     *
     * 只读 `segment` 表判断「有没有导入旧事」（不读导入消息正文：面板打开频率高，
     * 大包导入后逐段读消息没必要；真正需要消息 id 的是 [startConsolidate]）。
     * 「已整理」标记来自 `ImportPrefs.consolidatedThreads`（` 不重复收费）：
     * 整段整理过的 thread 不再显示「整理往事」入口；有未完成断点仍显示「再整理一些」。
     */
    fun refresh() {
        viewModelScope.launch {
            // v0.5.4 本 VM 属于「清空前那一代」时不再写库（refresh 会 ensureThread，
            // 给已删角色重建会话行）。清空后 AppRoot 用 wipeToken 换全新 VM，旧实例不再上屏。
            if (wipeChangedSince(wipeEpochAtCreation)) return@launch
            val thread = container.chat.ensureThread(characterId)
            threadId = thread.id
            val table = container.memory.load(thread.id)
            val importSegments = container.database.segmentDao().forThread(thread.id)
                .filter { it.batonReason == ImportRepository.BATON_REASON_IMPORT }
            val hasImportHistory = importSegments.isNotEmpty()
            // 读不到标记（DataStore 抖动）时按「未整理」处理：多显示一次入口不会造成静默花费，
            // 点进来还有费用确认兜底（>¥1 会先问）；反过来误判成「已整理」才会藏掉入口。
            val consolidated = runCatching {
                container.importPrefs.consolidatedThreads.first()
            }.getOrDefault(emptySet())
            // 上次没整理完的进度（续跑）：flow 本身只给「还有未完成片」的状态
            // （全部完成/无进度=null），这里只需按 threadId 匹配。
            val storedProgress = runCatching { container.importPrefs.consolidation.first() }.getOrNull()
                ?.takeIf { it.threadId == thread.id }
            // 老数据「补建」入口：只有当「有导入旧事 + 找旧事已配置 + 确实还有
            // dim=0 的导入块」时才显示。**不自动执行**：读一遍块是本地查询，不联网、不花钱。
            val backfillPending = runCatching { loadPendingBackfillChunks(thread.id, importSegments) }
                .getOrDefault(emptyList())
            val current = _state.value
            _state.value = current.copy(
                groups = table.toGroups(),
                loading = false,
                total = table.activeCount(),
                importPending = importPendingFor(hasImportHistory, thread.id, consolidated),
                // 运行中的进度由 run 的进度回调实时写；没在跑时才回落到上次的断点。
                // 分子用「已成功片数」：失败片不能算进进度（否则 UI 会误以为快整理完了）。
                consolidateProgress = if (current.consolidating) {
                    current.consolidateProgress
                } else {
                    storedProgress?.let {
                        formatConsolidateProgress(it.doneCount, it.totalShards)
                    }
                },
                semanticBackfillNeeded = backfillPending.isNotEmpty(),
                backfillProgress = if (current.backfilling) current.backfillProgress else null,
            )
        }
    }

    /** 「忘掉」：删除立即生效，下一轮 prompt 里就没了；反馈文案不承诺「不再提」。 */
    fun forget(kind: String, value: String) {
        viewModelScope.launch {
            val ok = runCatching { container.memory.deleteEntry(threadId, kind, value) }.isSuccess
            _state.update {
                it.withNotice(if (ok) MemoryCopy.NOTICE_DELETED else MemoryCopy.NOTICE_DELETE_FAILED)
            }
            refresh()
        }
    }

    /**
     * 「以后别再提这件事」：写一条 BOUNDARY（`MemoryRepository.addBoundary`）。
     *
     * 只加边界、不删原条目（用户可能只是不想被主动提起）；RELATION 没有「别再提」的语义，
     * UI 不显示、这里也防御性忽略。
     */
    fun addBoundary(kind: String, value: String) {
        if (kind == MEMORY_KIND_RELATION) return
        viewModelScope.launch {
            val ok = runCatching { container.memory.addBoundary(threadId, value) }.isSuccess
            _state.update {
                it.withNotice(
                    if (ok) MemoryCopy.NOTICE_BOUNDARY_ADDED else MemoryCopy.NOTICE_BOUNDARY_FAILED,
                )
            }
            refresh()
        }
    }

    /** 点「整理往事」：没连 AI 给一行提示；连了就先估费用，>¥1 先问、否则直接跑。 */
    fun startConsolidate() {
        val current = _state.value
        if (current.consolidating || current.consentPlan != null) return
        _state.update { it.withNotice(null) }
        viewModelScope.launch {
            val client = runCatching { container.llmClient() }.getOrNull()
            if (client == null) {
                // 「未连 AI」→ 聊天设置页；去向由 withNotice 一起写进 noticeOpenTarget。
                _state.update { it.withNotice(MemoryCopy.NOTICE_NEED_AI) }
                return@launch
            }
            val segments = loadImportedSegments()
            if (segments.isEmpty()) {
                _state.update { it.withNotice(MemoryCopy.NOTICE_NO_IMPORT) }
                return@launch
            }
            // plan 里会读 DataStore 取模型名算价（AppContainer.costOf 是 runBlocking），不能在主线程做。
            // 「最近 90 天」0 片时自动回退「全部」（建议），
            // 否则「全部早于 90 天」的档案会永远没有入口整理。
            val planned = runCatching {
                withContext(Dispatchers.IO) { planConsolidation(segments) }
            }.getOrNull()
            if (planned == null) {
                _state.update { it.withNotice(MemoryCopy.NOTICE_CONSOLIDATE_FAILED) }
                return@launch
            }
            val plan = planned.plan
            if (plan.shards == 0) {
                // 90 天与全部都没有可整理的内容：给一句人话，不再引导用户反复点。
                _state.update { it.withNotice(MemoryCopy.NOTICE_NOTHING_AT_ALL) }
                return@launch
            }
            if (shouldRequireConsent(plan)) {
                _state.update { it.copy(consentPlan = plan, consentRange = planned.range) }
            } else {
                runConsolidate(plan, planned.range)
            }
        }
    }

    /** 费用确认框：true = 继续整理，false = 先不整理（两者都清掉待确认计划）。 */
    fun confirmConsolidate(proceed: Boolean) {
        val plan = _state.value.consentPlan ?: return
        // 回退后的范围随计划一起存；拿不到就按最保守的「全部」跑（不会漏整理）。
        val range = _state.value.consentRange ?: ConsolidateRange.ALL
        _state.update { it.copy(consentPlan = null, consentRange = null) }
        if (proceed) runConsolidate(plan, range)
    }

    /** 「停下」：协作式取消，已整理的保留（进度已按片写进 `ImportPrefs`，可以接着来）。 */
    fun cancelConsolidate() {
        if (_state.value.consolidating) cancelRequested.set(true)
    }

    /**
     * 点「让 TA 能按意思找旧事」：给导入的旧事补建向量。
     *
     * 与「整理往事」的区别：这里**不调用聊天 AI、不写记忆**，只用用户填的「找旧事」服务给
     * 已有检索块建向量，让 TA 以后能按意思找到导入的旧事；费用由用户的服务商收取（通常很少）。
     *
     * 纪律：
     * - **不自动执行**：必须用户点；没配「找旧事」只给一行提示，不发网络请求；
     * - 只处理 `dim=0 / model="none"` 的 IMPORT chunk（聊天里已经带向量的块不动，避免重复花钱）；
     * - 批 ≤16；失败批跳过、保留已成功的，按钮留在原地可重试；可「停下」。
     */
    fun backfillSemantic() {
        val current = _state.value
        if (current.backfilling) return
        _state.update { it.withNotice(null) }
        viewModelScope.launch {
            val configured = runCatching { container.embeddingConfigured() }.getOrDefault(false)
            if (!configured) {
                // 没有「找旧事」服务：一句话引导去**找旧事设置页**（noticeOpenTarget 在 withNotice
                // 里一起给出），**不**产生任何网络请求。
                _state.update {
                    it.copy(semanticBackfillNeeded = false)
                        .withNotice(MemoryCopy.NOTICE_BACKFILL_NEED_SERVICE)
                }
                return@launch
            }
            val chunks = runCatching { loadPendingBackfillChunks() }.getOrDefault(emptyList())
            if (chunks.isEmpty()) {
                _state.update {
                    it.copy(semanticBackfillNeeded = false)
                        .withNotice(MemoryCopy.NOTICE_BACKFILL_NOTHING)
                }
                return@launch
            }
            val model = runCatching { container.embeddingModel() }.getOrDefault("")
            if (model.isBlank()) {
                _state.update { it.withNotice(MemoryCopy.NOTICE_BACKFILL_NEED_SERVICE) }
                return@launch
            }
            runBackfill(chunks, model)
        }
    }

    /** 「停下」：停止补建；已经补好的块保留（下次再点只处理还是 dim=0 的块，不重复花钱）。 */
    fun cancelBackfill() {
        if (_state.value.backfilling) backfillCancelRequested.set(true)
    }

    /** 关掉行内提示（UI 对成功类反馈做几秒自动收起）。 */
    fun dismissNotice() {
        if (_state.value.notice != null) {
            _state.update { it.withNotice(null) }
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 跑整理（任务挂 `appScope`，离开面板不中断）。
     *
     * 失败策略沿用 失败片跳过、不写记忆、不记账，**断点保留**
     * （已成功片下次绝不重跑）；整体异常才给人话失败提示。结束后一定 [refresh]，把新记忆与进度
     * 读回面板。
     *
     * @param plan 费用确认用的计划（估价按全部片，续跑时偏保守）
     * @param range 实际整理范围：90 天空时已回退到「全部」，必须原样传给 `run`，否则又会 0 片
     */
    private fun runConsolidate(plan: ConsolidatePlan, range: ConsolidateRange) {
        if (_state.value.consolidating) return
        // v0.5.4 清空开始执行那一刻起，本 VM 的整理一律作废（见 [wipeEpochAtCreation]）。
        if (wipeChangedSince(wipeEpochAtCreation)) return
        val runWipeEpoch = container.wipeEpoch.value
        cancelRequested.set(false)
        _state.update {
            it.copy(
                consolidating = true,
                consentPlan = null,
                consentRange = null,
                consolidateProgress = formatConsolidateProgress(0, plan.shards),
            ).withNotice(null)
        }
        consolidateJob = container.appScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val segments = loadImportedSegments()
                    container.importConsolidator.run(
                        threadId = threadId,
                        segments = segments,
                        range = range,
                        plan = plan,
                        progress = { done, total ->
                            // 清空后不再刷新进度（死 VM 的 UI 无人看，写库本身也没有）
                            if (!wipeChangedSince(runWipeEpoch)) {
                                _state.update {
                                    it.copy(consolidateProgress = formatConsolidateProgress(done, total))
                                }
                            }
                        },
                        // 分片边界除了用户「停下」，也要在清空后停下（旧内容不写回）。
                        isCancelled = { cancelRequested.get() || wipeChangedSince(runWipeEpoch) },
                    )
                }
            }
            // v0.5.4 清空已发生 → 「已整理」标记/提示/刷新全部丢弃（写库前先收口）。
            if (wipeChangedSince(runWipeEpoch)) return@launch
            val result = outcome.getOrNull()
            // 整段成功才写「已整理」标记（与 DeepSeekImporter 同一判定，`）：
            // `!cancelled && failed == 0 && done == total` 且总分片数 > 0（0 片不标记，
            // 否则「全部早于 90 天」的档案会被永久标成已整理、再也找不到入口）。
            // 失败或取消不标记——断点在 ImportPrefs.consolidation，下次「再整理一些」只跑未完成片（§6）。
            if (result?.isFullyConsolidated() == true) {
                runCatching { container.importPrefs.markConsolidated(threadId) }
            }
            val notice = when {
                result == null -> MemoryCopy.NOTICE_CONSOLIDATE_FAILED
                result.cancelled -> MemoryCopy.NOTICE_CONSOLIDATE_CANCELLED
                result.shardsTotal == 0 -> MemoryCopy.NOTICE_NOTHING_AT_ALL
                result.failed > 0 -> MemoryCopy.NOTICE_CONSOLIDATE_FAILED
                else -> MemoryCopy.NOTICE_CONSOLIDATED
            }
            _state.update {
                it.copy(consolidating = false).withNotice(notice)
            }
            refresh()
        }
    }

    /**
     * 跑补建（任务挂 `appScope`，离开面板不中断；`）。
     *
     * 只更新检索块自己的 `dim/vec/scale/model`；不碰记忆表、不调聊天 AI、不写 `ImportPrefs`。
     * 失败批跳过、已成功的保留；取消保留已成功的。结束后一定 [refresh]（重新统计还有没有
     * `dim=0` 的块，决定入口要不要继续显示）。
     */
    private fun runBackfill(chunks: List<ChunkEntity>, model: String) {
        if (_state.value.backfilling) return
        // v0.5.4 清空开始执行那一刻起，本 VM 的补建一律作废。
        if (wipeChangedSince(wipeEpochAtCreation)) return
        val runWipeEpoch = container.wipeEpoch.value
        backfillCancelRequested.set(false)
        _state.update {
            it.copy(
                backfilling = true,
                backfillProgress = formatConsolidateProgress(0, chunks.size),
            ).withNotice(null)
        }
        backfillJob = container.appScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    SemanticBackfill.run(
                        chunks = chunks,
                        embed = container.importEmbedPort,
                        model = model,
                        // 整行 REPLACE（`ChunkDao.insertAll` 是 OnConflictStrategy.REPLACE）：
                        // 只改这一批 chunk 的向量字段，不新增行、不动 chunk_term。
                        // v0.5.4 清空已发生就不写（chunk 行已被 clearAllTables 删掉）。
                        saveChunks = { rows ->
                            if (!wipeChangedSince(runWipeEpoch)) {
                                container.memory.saveChunks(rows, emptyList())
                            }
                        },
                        progress = { done, total ->
                            if (!wipeChangedSince(runWipeEpoch)) {
                                _state.update {
                                    it.copy(backfillProgress = formatConsolidateProgress(done, total))
                                }
                            }
                        },
                        // 批边界除了用户「停下」，也要在清空后停下。
                        isCancelled = { backfillCancelRequested.get() || wipeChangedSince(runWipeEpoch) },
                    )
                }
            }
            // v0.5.4 清空已发生 → 提示/刷新丢弃（不复活 dim=0 统计、不再写库）。
            if (wipeChangedSince(runWipeEpoch)) return@launch
            val result = outcome.getOrNull()
            val notice = when {
                result == null -> MemoryCopy.NOTICE_BACKFILL_FAILED
                result.cancelled -> MemoryCopy.NOTICE_BACKFILL_CANCELLED
                result.embedded == 0 -> MemoryCopy.NOTICE_BACKFILL_FAILED
                result.failedChunks > 0 -> MemoryCopy.NOTICE_BACKFILL_FAILED
                else -> MemoryCopy.NOTICE_BACKFILLED
            }
            _state.update {
                it.copy(
                    backfilling = false,
                    backfillProgress = null,
                ).withNotice(notice)
            }
            refresh()
        }
    }

    /**
     * 读「还需要补建向量」的导入检索块。
     *
     * **只读**：`recentChunks` 是现有只读查询，`pendingChunks` 是纯过滤，不写库、不联网。
     * 扫描范围与召回候选集同口径（`ChatEngine.recallText` 用 `recentChunks(2000)`）：
     * 更早的块当前召回路径根本取不到，没必要为它们花钱。
     *
     * @param importSegments 已查到的段（[refresh] 顺手复用，避免重复查询）；null = 自己查
     */
    private suspend fun loadPendingBackfillChunks(
        threadId: String,
        importSegments: List<SegmentEntity>?,
    ): List<ChunkEntity> {
        if (threadId.isBlank()) return emptyList()
        val segments = importSegments
            ?: container.database.segmentDao().forThread(threadId)
        val importSegmentIds = segments
            .filter { it.batonReason == ImportRepository.BATON_REASON_IMPORT }
            .map { it.id }
            .toSet()
        if (importSegmentIds.isEmpty()) return emptyList()
        // 没配置「找旧事」：不显示入口（也不需要读块）；配置判断失败按没配置处理（不花钱）。
        if (!runCatching { container.embeddingConfigured() }.getOrDefault(false)) return emptyList()
        return SemanticBackfill.pendingChunks(
            chunks = container.memory.recentChunks(threadId, limit = BACKFILL_SCAN_LIMIT),
            importSegmentIds = importSegmentIds,
        )
    }

    /** [backfillSemantic] 用：拿当前 thread 的待补建块（`threadId` 由 [refresh] 填好）。 */
    private suspend fun loadPendingBackfillChunks(): List<ChunkEntity> =
        loadPendingBackfillChunks(threadId, importSegments = null)

    /**
     * 从 DB 重建整理输入：只取 `batonReason="IMPORT"` 的段，逐段按写入顺序读消息
     * （`messageDao.bySegment` 按 `createdAt ASC`；导入段内时间已严格递增）。
     *
     * `sourceSessionId` DB 不存，填空串—— 的 `plan/run` 都不读它（`DeepSeekImporter`
     * 的续导重建同口径）。
     */
    private suspend fun loadImportedSegments(): List<ImportedSegment> {
        if (threadId.isBlank()) return emptyList()
        return container.database.segmentDao().forThread(threadId)
            .filter { it.batonReason == ImportRepository.BATON_REASON_IMPORT }
            .mapNotNull { segment ->
                importedSegmentFrom(segment, container.database.messageDao().bySegment(segment.id))
            }
    }

    /**
     * 算面板的整理计划：先算「最近 90 天」；0 片时回退「全部」重算一次
     * （建议 默认范围 90 天，但导入的档案可能全部更早）。
     *
     * 必须在 IO 线程调用（`AppContainer.costOf` 里会 `runBlocking` 读 DataStore）。
     *
     * @return null = 计划本身算不出来（读取/估价异常）；`plan.shards == 0` = 两个范围都为空。
     */
    private suspend fun planConsolidation(segments: List<ImportedSegment>): RangePlan? {
        val within = runCatching {
            container.importConsolidator.plan(threadId, segments, ConsolidateRange.LAST_90_DAYS)
        }.getOrNull() ?: return null
        if (within.shards > 0) return RangePlan(within, ConsolidateRange.LAST_90_DAYS)
        val all = runCatching {
            container.importConsolidator.plan(threadId, segments, ConsolidateRange.ALL)
        }.getOrNull() ?: return null
        return RangePlan(all, ConsolidateRange.ALL)
    }

    companion object {
        fun factory(container: AppContainer, characterId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { MemoryViewModel(container, characterId) }
            }
    }
}

// ------------------------------------------------------------------ 纯函数（可单测，见 MemoryPanelLogicTest）

/**
 * 面板整理计划 + 实际生效范围（90 天空时回退全部）。
 *
 * 计划与范围必须成对传递：`run` 用的范围与 `plan` 算的范围不一致会得到 0 片/漏整理。
 */
private data class RangePlan(
    val plan: ConsolidatePlan,
    val range: ConsolidateRange,
)

/**
 * 整理进度文案：`done / total`（例："3 / 8"）。
 *
 * 越界值钳位（脏进度/取消竞态不该显示 "9 / 8" 或负数）。
 */
internal fun formatConsolidateProgress(done: Int, total: Int): String {
    val safeTotal = total.coerceAtLeast(0)
    val safeDone = done.coerceIn(0, safeTotal)
    return "$safeDone / $safeTotal"
}

/** 预估花费超过 ¥1 才二次确认（` 等于 ¥1 不打扰）。*/
internal fun shouldRequireConsent(plan: ConsolidatePlan): Boolean =
    plan.estimatedYuan > CONSOLIDATE_CONSENT_YUAN_THRESHOLD

/**
 * 面板是否还显示「导入未整理」提示块。
 *
 * 判定从「有没有 IMPORT 段」改成「有 IMPORT 段 **且** 这个 thread 还没被整段整理过」：
 * 导入段永不删除，只看前者会让整理完成后入口常驻，用户再点一次就从 0 重跑、重复花钱
 * 「已整理」标记由 `ImportPrefs.consolidatedThreads` 提供，
 * 只在全部分片成功且未取消时写入（见 `isFullyConsolidated`）。
 *
 * 有未完成断点时 [MemoryUiState.consolidateProgress] 仍有值，UI 显示「再整理一些」（续跑）。
 */
internal fun importPendingFor(
    hasImportSegments: Boolean,
    threadId: String,
    consolidatedThreads: Set<String>,
): Boolean = hasImportSegments && threadId !in consolidatedThreads

/**
 * 设置一条面板内提示，并一起给出右侧引导按钮的去向（`noticeOpenTarget`）。
 *
 * 依据 ——之前 UI 用「提示语是否等于某常量」决定进哪个设置页，
 * 新增/改提示语会静默走错页；现在去向在设提示语时**一起**写进状态，UI 只按枚举分派。
 * 普通反馈（删除/整理结果等）自动得到 null，按钮不会残留在下一条提示上。
 */
internal fun MemoryUiState.withNotice(notice: String?): MemoryUiState =
    copy(notice = notice, noticeOpenTarget = noticeOpenTargetFor(notice))

/**
 * 提示语 → 右侧引导按钮去向（纯函数，`MemoryPanelLogicTest` 锁定）。
 *
 * 只有明确要引导用户去某个设置页的提示才在这里登记：
 * - 未登记 = 不显示按钮（可见的「少一个入口」），不会静默走错页；
 * - 新增引导类提示必须在这里加一行，否则按钮不出现——比猜一个默认页面安全。
 */
internal fun noticeOpenTargetFor(notice: String?): NoticeOpenTarget? = when (notice) {
    MemoryCopy.NOTICE_NEED_AI -> NoticeOpenTarget.CHAT
    MemoryCopy.NOTICE_BACKFILL_NEED_SERVICE -> NoticeOpenTarget.FIND_OLD_THINGS
    else -> null
}

/**
 * 「¥X」金额显示：沿用 `「数字」口径（两位小数；≥100 不带小数）。
 *
 * 面板只在费用确认框用一次，所以自带一份实现，不跨页引用导入页的 internal 工具函数
 * （两个页面的文案口径仍一致：` 是唯一来源）。
 */
internal fun formatConsentYuan(yuan: Double): String =
    if (yuan >= 100.0) {
        "¥" + String.format(Locale.CHINA, "%.0f", yuan)
    } else {
        "¥" + String.format(Locale.CHINA, "%.2f", yuan)
    }

/**
 * 把「一个 IMPORT 段 + 它的消息」映射成 的 [ImportedSegment]（纯映射）。
 *
 * - `sourceSessionId` 填空串（DB 不存 不读它）；
 * - `messageIds` 保持**写入顺序**（按传入列表顺序，即 `bySegment` 的 `createdAt ASC`）；
 * - 首末时间取段内第一条/最后一条消息的源时间；
 * - 段里没有消息（导入后被删光）返回 null，调用方跳过它。
 */
internal fun importedSegmentFrom(
    segment: SegmentEntity,
    messages: List<MessageEntity>,
): ImportedSegment? {
    if (messages.isEmpty()) return null
    return ImportedSegment(
        sourceSessionId = "",
        segmentId = segment.id,
        messageIds = messages.map { it.id },
        firstMessageAt = messages.first().createdAt,
        lastMessageAt = messages.last().createdAt,
    )
}

// `MemoryTable.activeCount()` 是 `mem` 层的成员函数（有效条目总数，口径见），
// 这里直接用成员，不再写同名扩展（否则被成员遮蔽，形同死代码）。

// ------------------------------------------------------------------ 老数据补建（纯逻辑可单测）

/**
 * 「补建」扫描的检索块上限（v0.4.1）。
 *
 * 与召回候选集同口径：`ChatEngine.recallText` 只取 `recentChunks(threadId, 2000)`
 * （`「候选 = 最近 2000 条 chunk ∪ 时间窗」）。更早的块当前 1:1 召回路径取不到，
 * 为它们花钱没有意义；现有 data 层 API 也只有 `limit` 参数（不新增 DAO 查询，见报告「未覆盖」）。
 */
internal const val BACKFILL_SCAN_LIMIT: Int = 2000

/**
 * 老数据「补建向量」的纯逻辑。
 *
 * 为什么单独抽出来：`MemoryViewModel` 依赖 `AppContainer`（Context / DataStore / HTTP），
 * 在纯 JVM 单测里造不起来；把「选块 / 分批 / 调端口 / 整行更新」抽成只依赖 lambda 的
 * suspend 函数，就能用 fake `EmbedPort` + fake `saveChunks` 离线断言：
 * - 只处理 `dim=0 / model="none"` 的**导入** chunk（不重复处理已带向量的、不碰聊天块）；
 * - 批大小 ≤16；
 * - 失败批跳过、已成功的保留；可取消（保留已成功的）。
 *
 * 更新方式：整行 REPLACE（`ChunkDao.insertAll` 的 `OnConflictStrategy.REPLACE`），
 * 只改本批的 `dim/vec/scale/model`，不新增行、不动 `chunk_term`（关键词索引不受影响）。
 */
internal object SemanticBackfill {

    /** 每批条数（与导入索引一致：` 批大小 16）。*/
    const val BATCH_SIZE: Int = ImportIndexer.EMBED_BATCH_SIZE

    /**
     * 一次补建的统计。
     *
     * @property total 待处理块数
     * @property processed 已尝试处理（成功 + 失败）的块数，进度用
     * @property embedded 成功写入向量的块数
     * @property failedChunks 失败批里的块数（保留 dim=0，下次可重试）
     * @property cancelled 是否被用户「停下」
     */
    data class Result(
        val total: Int,
        val processed: Int,
        val embedded: Int,
        val failedChunks: Int,
        val cancelled: Boolean,
    )

    /**
     * 选出需要补建的块：**只**处理 `deletedAt == null && dim == 0 && vec 为空 &&
     * model == "none"` 且属于 IMPORT 段的块。
     *
     * - 已经带向量的块（聊天块 / 已补建过的导入块）不动 —— 避免重复花钱；
     * - 不在 IMPORT 段里的块不动 —— 需求只授权处理「导入的旧事」；
     * - 空正文块跳过（没东西可建，也不该写进 chunk）。
     */
    fun pendingChunks(
        chunks: List<ChunkEntity>,
        importSegmentIds: Set<String>,
    ): List<ChunkEntity> = chunks.filter { chunk ->
        chunk.deletedAt == null &&
            chunk.dim == 0 &&
            chunk.vec.isEmpty() &&
            chunk.model == ImportIndexer.MODEL_NONE &&
            chunk.text.isNotBlank() &&
            chunk.segmentId in importSegmentIds
    }

    /** 按 [BATCH_SIZE] 分批（纯函数，测试锁定批大小）。 */
    fun batches(chunks: List<ChunkEntity>, size: Int = BATCH_SIZE): List<List<ChunkEntity>> =
        chunks.chunked(size.coerceAtLeast(1))

    /**
     * 把一条检索块更新成带向量的行（量化规则与导入索引一致：`VectorMath.quantizeInt8`）。
     *
     * 空向量不写（保持 dim=0），避免写出 `dim>0/vec 空` 的坏行。
     */
    fun withVector(chunk: ChunkEntity, vector: FloatArray, model: String): ChunkEntity {
        val quantized = VectorMath.quantizeInt8(vector)
        return chunk.copy(
            dim = quantized.dim,
            vec = quantized.bytes,
            scale = quantized.scale,
            model = model,
        )
    }

    /**
     * 跑一次补建。
     *
     * 每批：整批调 [embed]（失败按 `embedWithRetry` 重试 2 次）→ 成功则整批量化 + 一次
     * `saveChunks`（REPLACE）；失败则跳过这一批（保留 dim=0，可重试），不抛异常。
     *
     * @param saveChunks 整批写回（生产为 `MemoryRepository.saveChunks(rows, emptyList())`）
     * @param progress 每批结束回调 `(processed, total)`；取消后不再回调
     * @param isCancelled 协作式取消；在批边界检查，已成功的批保留
     */
    suspend fun run(
        chunks: List<ChunkEntity>,
        embed: EmbedPort,
        model: String,
        saveChunks: suspend (List<ChunkEntity>) -> Unit,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result {
        val total = chunks.size
        var processed = 0
        var embedded = 0
        var failed = 0
        var cancelled = false
        progress(0, total)
        for (batch in batches(chunks)) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            val vectors = embedWithRetry(embed, batch.map { it.text })
            if (vectors == null) {
                // 整批失败：不写、不阻断；这些块保持 dim=0，下次「再试一次」会再选中。
                failed += batch.size
            } else {
                val rows = batch.mapIndexed { index, chunk ->
                    withVector(chunk, vectors[index], model)
                }
                val saved = runCatching { saveChunks(rows) }.isSuccess
                if (saved) embedded += rows.size else failed += batch.size
            }
            processed += batch.size
            progress(processed, total)
        }
        return Result(
            total = total,
            processed = processed,
            embedded = embedded,
            failedChunks = failed,
            cancelled = cancelled,
        )
    }
}

private fun MemoryTable.toGroups(): List<MemoryGroup> = listOf(
    MemoryGroup(MemoryCopy.GROUP_FACT, "FACT", facts.filter { it.isActive() }.map { it.value }),
    MemoryGroup(
        MemoryCopy.GROUP_RELATION,
        MEMORY_KIND_RELATION,
        listOfNotNull(
            relation?.stage?.let { "关系：$it" },
            relation?.nicknameByMe?.let { "TA 叫你$it" },
            relation?.nicknameForMe?.let { "你叫 TA $it" },
        ),
    ),
    MemoryGroup(MemoryCopy.GROUP_LOOP, "LOOP", loops.filter { it.isActive() }.map { it.value }),
    MemoryGroup(MemoryCopy.GROUP_PREF, "PREF", prefs.filter { it.isActive() }.map { it.value }),
    MemoryGroup(MemoryCopy.GROUP_BOUNDARY, "BOUNDARY", boundaries.filter { it.isActive() }.map { it.value }),
).filter { it.items.isNotEmpty() }
