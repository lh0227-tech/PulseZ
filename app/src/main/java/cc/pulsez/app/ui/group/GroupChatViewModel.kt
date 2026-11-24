package cc.pulsez.app.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.pulsez.app.AppContainer
import cc.pulsez.app.core.CrisisReplyGuard
import cc.pulsez.app.core.GroupChatEngine
import cc.pulsez.app.core.GroupMember
import cc.pulsez.app.core.GroupMemoryExtractor
import cc.pulsez.app.core.GroupMemoryShard
import cc.pulsez.app.core.GroupTurn
import cc.pulsez.app.core.GroupTurnResult
import cc.pulsez.app.core.MentionParser
import cc.pulsez.app.core.Plan
import cc.pulsez.app.core.SpeakerLedger
import cc.pulsez.app.core.SpeakerLedgerRestore
import cc.pulsez.app.core.UsageLimits
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.group.LlmGroupReplyPort
import cc.pulsez.app.data.group.RoomGroupHistoryPort
import cc.pulsez.app.data.group.RoomGroupMemoryPort
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.mem.TokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 「一起聊」的真实 ViewModel。
 *
 * 数据来源全部是真库：`thread`（kind=GROUP）+ `thread_member` + `message.speakerCharacterId`；
 * 选人与生成本地/远端由 [GroupChatEngine] 承担（纯逻辑，端口实现见 `data/group/GroupPortsImpl.kt`）。
 *
 * 关键约定（逐条对应）：
 * - 一条用户消息**默认只 1 人主回**：`allowSecondResponder = false`；引擎本身默认也只出 1 人；
 * - **@ 点名**：发送时用纯函数 [MentionParser] 本地解析（0 次模型调用），把 addressedIds 交给引擎。
 *   被 @ 者必回、不换人；同时 @ 两人时第 2 人只给 ≤20 字短反应；`@所有人` 不广播；
 * - `Spoken`（primary）→ `MAIN`、`Spoken`（secondary）→ `REACTION`；
 *   `Failed(silent=false)` → 一条 `failed` 行（只有可点重试，**不是占位气泡**）；
 *   `Passed` 与 `Failed(silent=true)` **不映射任何 UI 元素**（沉默是一等功能），
 *   唯一例外是 `Passed(mentionMissed=true)`：被 @ 者重试后仍沉默，必须可见可重试；
 * - 每条 AI 回复单独落库并写 `speakerCharacterId`，一条消息只属于一个发言人；
 * - 轮换账本（`lastSpeakers` / `silentTurns`）每轮结束写 DataStore，加载时优先恢复，
 *   再从历史兜底重建（缺陷 最近 3 条 AI 回复仍只放内存，下一轮后可重建）；
 * - 发送前检查日/月花费上限，到顶不发请求并给可操作提示（缺陷）；
 * - 每轮主回成功后在后台（[onTurnLanded]）补两件事，均**不阻塞 UI、失败静默**：
 *   写一条群共享检索块（`ChatEngine.indexTurn`，`embed = false`），以及按 8 轮/3k token 节奏
 *   对主回角色做一次「各自记忆」增量抽取（输入只含用户与它自己的话，写入 ownerId = 它，
 *   见 [GroupMemoryShard] / [GroupMemoryExtractor]）。
 */
class GroupChatViewModel(
    private val container: AppContainer,
    private val threadId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupChatUiState())
    val state: StateFlow<GroupChatUiState> = _state.asStateFlow()

    private val chat get() = container.chat

    private var segmentId: String = ""

    /** Raw rows currently represented by the UI page; engine and ledger still use full groupHistory. */
    private var uiHistoryRows: List<MessageEntity> = emptyList()
    private var uiMemberOrder: List<ChatRepository.GroupUiMemberOrder> = emptyList()
    private var uiPrecedingUser: MessageEntity? = null
    private var uiPrecedingAssistantSeen: Boolean = false
    private var refreshJob: Job? = null
    private var olderPageJob: Job? = null

    /** 当前群成员（按 ordinal），带引擎用的 [GroupMember.silentTurns]。 */
    private var members: List<GroupMember> = emptyList()

    /** 最近若干轮的主回角色 id，**新→旧**（step 3）。*/
    private var lastSpeakers: List<String> = emptyList()

    /** 最近 3 条 AI 回复正文，旧→新（去重）。*/
    private var recentAiReplies: List<String> = emptyList()

    /** 主回失败后待重试的记录：UI messageId → 原始用户消息（重跑同一轮）。 */
    private val failedTurns = mutableMapOf<String, FailedMain>()

    /** 保证同一轮内多条消息的 createdAt 严格递增，否则 groupHistory 的同毫秒排序会打乱发言顺序。 */
    private var appendClock = 0L

    /**
     * 距上次群聊记忆抽取的主回轮数与累计 token 估算（≥8 轮或 3–5k token）。
     *
     * 群聊不能每轮都抽（成本）也不能不抽（"各自记得"落空）；[onTurnLanded] 里按阈值触发。
     */
    private var roundsSinceMemoryExtract = 0
    private var tokensSinceMemoryExtract = 0

    private var job: Job? = null

    /**
     * 本 ViewModel 创建时的清空代际（v0.5.4）：`AppContainer.wipeEpoch` 一旦变大 =
     * 用户在本 VM 存活期间**开始执行**清空所有数据（代际在进入 clearAll 前自增）→
     * 取消在途生成（[init] 订阅），迟到写入按代际丢弃。
     *
     * 与 `ChatViewModel` 同一口径（含「不用 drop(1)」的理由）：VM 构造与收集器启动之间
     * 若刚好发生清空，首帧就是新代际，drop(1) 会漏掉。
     */
    private val wipeEpochAtCreation: Long = container.wipeEpoch.value

    /**
     * 当前这一轮开始时的清空代际（v0.5.4）。[send] / [retry] 在同步段写入，
     * 落库/索引/账本/记忆写之前调用 [wipedDuringTurn] 比对。
     *
     * 为什么不在各写点现取代际：现取到的是「当前」值，检测不出「本轮开始 → 落库之间」的清空。
     */
    private var turnWipeEpoch: Long = wipeEpochAtCreation

    /** 本轮开始后是否发生过「清空所有数据」（v0.5.4）。*/
    private fun wipedDuringTurn(): Boolean = container.wipeEpoch.value != turnWipeEpoch

    private data class FailedMain(
        val userText: String,
        val characterId: String,
        /** 原始那轮用户点过 TA 的名：重试成功后仍要显示「点了 TA 的名」。*/
        val addressed: Boolean = false,
    )

    init {
        // v0.5.4 清空代际一变就取消在途生成（清空前发起的群聊回复不得在清空后落库）。
        // 本 VM 是 Activity 级、按 thread 缓存，清空后不一定立刻 onCleared，所以必须主动停。
        viewModelScope.launch {
            container.wipeEpoch.collect { epoch ->
                if (epoch != wipeEpochAtCreation) cancelTurnForWipe()
            }
        }
    }

    /**
     * 清空**开始执行**后收口本 VM 的在途生成（v0.5.4 代际在清空进入执行时自增，
     * 见 `AppContainer.notifyUserDataWiped` 与 `AppRoot.runClear`）。
     *
     * 只取消生成轮次并清「正在写」标记：清空会删库，正在跑的回复没有可写的父群；界面由
     * `AppRoot` 用清空 token 换全新 VM 负责。
     */
    private fun cancelTurnForWipe() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(streaming = false, typingSpeakerName = null)
    }

    // ------------------------------------------------------------------ 加载

    /** Called when this route becomes visible; a re-entry starts again from the newest UI page. */
    fun refresh() {
        if (_state.value.streaming) return
        refreshJob?.cancel()
        olderPageJob?.cancel()
        uiHistoryRows = emptyList()
        uiMemberOrder = emptyList()
        uiPrecedingUser = null
        uiPrecedingAssistantSeen = false
        val historySessionId = UUID.randomUUID().toString()
        _state.value = _state.value.copy(
            messages = emptyList(),
            historyLoaded = false,
            historySessionId = historySessionId,
            hasOlderHistory = false,
            loadingOlderHistory = false,
            error = null,
        )
        refreshJob = viewModelScope.launch { load() }
    }

    /** Invoked by the screen only after a user drag moves toward the older boundary. */
    fun loadOlderHistory() {
        val current = _state.value
        val oldest = uiHistoryRows.firstOrNull() ?: return
        if (!current.historyLoaded || !current.hasOlderHistory || current.loadingOlderHistory) return
        if (olderPageJob?.isActive == true) return
        _state.value = current.copy(loadingOlderHistory = true)
        olderPageJob = viewModelScope.launch {
            try {
                val page = chat.groupUiBeforePage(
                    threadId = threadId,
                    before = oldest,
                    memberOrder = uiMemberOrder,
                    limit = UI_OLDER_PAGE_SIZE,
                )
                if (page.messages.isEmpty()) {
                    _state.value = _state.value.copy(
                        hasOlderHistory = false,
                        loadingOlderHistory = false,
                    )
                    return@launch
                }

                val oldIds = uiHistoryRows.asSequence().map { it.id }.toHashSet()
                val addedRows = page.messages.filterNot { it.id in oldIds }
                val mergedRows = addedRows + uiHistoryRows
                val currentUi = _state.value.messages
                val preservedLiveTail = currentUi.filter { it.id !in oldIds }
                uiHistoryRows = mergedRows
                uiPrecedingUser = page.precedingUser
                uiPrecedingAssistantSeen = page.precedingAssistantSeen
                val mapped = toUiMessages(
                    history = mergedRows,
                    precedingUser = uiPrecedingUser,
                    precedingAssistantSeen = uiPrecedingAssistantSeen,
                )
                _state.value = _state.value.copy(
                    messages = mapped + preservedLiveTail,
                    hasOlderHistory = page.hasOlder,
                    loadingOlderHistory = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    loadingOlderHistory = false,
                    error = "更早的消息暂时没翻出来，再往上划一下试试。",
                )
            }
        }
    }

    private suspend fun load() {
        val thread = container.database.threadDao().byId(threadId)
        if (thread == null) {
            _state.value = _state.value.copy(error = "这个群已经不在了，退回上一页看看别的聊天吧。")
            return
        }
        segmentId = thread.activeSegmentId.orEmpty()
        val memberRows = chat.membersOf(threadId).sortedBy { it.ordinal }
        uiMemberOrder = memberRows.map { row ->
            ChatRepository.GroupUiMemberOrder(characterId = row.characterId, ordinal = row.ordinal)
        }
        val loaded = memberRows.mapNotNull { row ->
            val character = container.characters.get(row.characterId) ?: return@mapNotNull null
            GroupMember(
                characterId = character.id,
                name = character.name,
                // 与 1:1 的 ChatEngine.prepare 同源：优先 systemPromptOverride
                persona = character.systemPromptOverride?.takeIf { it.isNotBlank() }
                    ?: character.persona,
                styleSamples = character.styleSamples
                    ?.split('\u0001')
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
                ordinal = row.ordinal,
                // chattyScore：角色表暂无"话多程度"字段，用 的默认 0.5
                chattyScore = DEFAULT_CHATTY_SCORE,
            )
        }
        members = loaded
        val hasStoredLedger = restoreLedgerFromStore()
        if (!hasStoredLedger) {
            val fallback = chat.groupLedgerFallback(threadId, uiMemberOrder)
            lastSpeakers = fallback.lastSpeakers
            members = members.map { member ->
                member.copy(silentTurns = fallback.silentTurns[member.characterId] ?: 0)
            }
        }
        // The only prompt de-duplication state needed at open is the final three persisted assistant replies.
        recentAiReplies = chat.groupUiRecentAssistantReplies(
            threadId = threadId,
            memberOrder = uiMemberOrder,
            limit = RECENT_AI_REPLY_LIMIT,
        )
        val page = chat.groupUiLatestPage(
            threadId = threadId,
            memberOrder = uiMemberOrder,
            limit = UI_INITIAL_PAGE_SIZE,
        )
        uiHistoryRows = page.messages
        uiPrecedingUser = page.precedingUser
        uiPrecedingAssistantSeen = page.precedingAssistantSeen
        _state.value = _state.value.copy(
            title = thread.title?.takeIf { it.isNotBlank() } ?: GroupChatUiState.TITLE_DEFAULT,
            members = loaded.map { GroupMemberUi(id = it.characterId, name = it.name) },
            messages = toUiMessages(
                history = page.messages,
                precedingUser = page.precedingUser,
                precedingAssistantSeen = page.precedingAssistantSeen,
            ),
            historyLoaded = true,
            hasOlderHistory = page.hasOlder,
            loadingOlderHistory = false,
            showAiNotice = !container.aiBadgeDismissed,
            error = if (loaded.size < GroupRules.MIN_MEMBERS) {
                "这个群里现在只剩 ${loaded.size} 个角色，至少两个角色才聊得起来。"
            } else {
                null
            },
        )
    }

    /**
     * 从 DataStore 恢复轮换账本（缺陷）。
     *
     * 存过就用存过的：失败、`[PASS]` 这些不落库的轮次只存在于账本里，历史重建看不到。
     * 没存过 / 读取失败（静默）时由 [ChatRepository.groupLedgerFallback] 对已存历史做 SQL 聚合，
     * 只返回账本数值，不把旧消息读进 UI。
     */
    private suspend fun restoreLedgerFromStore(): Boolean {
        val snapshot = container.groupLedger.load(threadId) ?: return false
        val restored = SpeakerLedgerRestore.restore(members, snapshot)
        members = restored.members
        lastSpeakers = restored.lastSpeakers
        return true
    }

    /** 历史 → UI 行；同一轮里第一条 AI 是 MAIN，之后的短反应是 REACTION（UI 把 REACTION 折叠到主回下）。 */
    private suspend fun toUiMessages(
        history: List<MessageEntity>,
        precedingUser: MessageEntity? = null,
        precedingAssistantSeen: Boolean = false,
    ): List<GroupMessageUi> {
        return mapGroupHistoryToUiMessages(
            history = history,
            members = members,
            speakerNames = resolveSpeakerNames(history),
            precedingUser = precedingUser,
            precedingAssistantSeen = precedingAssistantSeen,
        )
    }

    private suspend fun resolveSpeakerNames(history: List<MessageEntity>): Map<String, String> {
        val ids = history.asSequence().mapNotNull { it.speakerCharacterId }.distinct().toList()
        if (ids.isEmpty()) return emptyMap()
        return ids.associateWith { id ->
            groupSpeakerDisplayName(container.characters.get(id)?.name)
        }
    }

    // ------------------------------------------------------------------ 发送

    fun onInputChange(value: String) {
        _state.value = _state.value.copy(input = value)
    }

    fun send(onAccepted: (() -> Unit)? = null) {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.streaming) return
        if (members.size !in GroupRules.MIN_MEMBERS..GroupRules.MAX_MEMBERS) {
            _state.value = _state.value.copy(
                error = "群里至少要有 ${GroupRules.MIN_MEMBERS} 个角色才聊得起来。",
            )
            return
        }
        _state.value = _state.value.copy(
            streaming = true,
            error = null,
            typingSpeakerName = null,
        )
        // v0.5.4 这一轮的起点代际（必须在发起处取，见 [turnWipeEpoch] 注释）。
        turnWipeEpoch = container.wipeEpoch.value
        job = viewModelScope.launch {
            try {
                // 0) 花费上限（缺陷）：到顶就不发起请求，输入框内容原样留在输入框里。
                //    上限改完立即生效：每次发送都重新读当前设置与当前记账快照，不缓存。
                usageLimitMessage()?.let { message ->
                    _state.value = _state.value.copy(streaming = false, error = message)
                    return@launch
                }
                // v0.5.4 花费检查是挂起点，期间可能已清空 → 整轮不发（不落用户消息）。
                if (wipedDuringTurn()) return@launch
                _state.value = _state.value.copy(input = "")
                // 1) 先落用户消息（speaker = null 步骤 1）
                val userMessage = chat.append(
                    threadId = threadId,
                    segmentId = segmentId,
                    role = "user",
                    text = text,
                    tokens = text.length / 2 + 4,
                    createdAt = nextCreatedAt(),
                )
                _state.value = _state.value.copy(
                    messages = _state.value.messages + GroupMessageUi(
                        id = userMessage.id,
                        role = GroupMessageRole.USER,
                        text = text,
                    ),
                )
                // 依据：群发送门禁已通过，用户消息已落库并提交到 UI state。
                runCatching { onAccepted?.invoke() }
                if (container.llmClient() == null) {
                    _state.value = _state.value.copy(error = "还没连上 AI。去设置里填一下？")
                }
                // 2) 一轮：本地 @ 解析（纯本地、0 次模型调用）+ 引擎选人
                //    @所有人不广播：解析器返回空 addressedIds，按普通消息走默认选人
                val mention = MentionParser.parse(text, members)
                val turn = runEngine(
                    userText = text,
                    addressedIds = mention.addressedIds,
                )
                applyTurn(
                    turn = turn,
                    userMessageId = userMessage.id,
                    userText = text,
                    addressedIds = mention.addressedIds.toSet(),
                )
            } catch (cancelled: CancellationException) {
                // 用户停下：已落库的保持原样，不再补任何内容（用户永远优先）
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = "这轮没走完。再发一次试试？")
            } finally {
                _state.value = _state.value.copy(streaming = false, typingSpeakerName = null)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(streaming = false, typingSpeakerName = null)
    }

    /**
     * 主回失败后的重试：**只重跑那一条主回**（点名式锁定原发言人），成功后按同一 messageId
     * 原位替换，不追加第二条。
     */
    fun retry(messageId: String) {
        val pending = failedTurns[messageId] ?: return
        if (_state.value.streaming) return
        if (members.none { it.characterId == pending.characterId }) {
            failedTurns.remove(messageId)
            _state.value = _state.value.copy(error = "TA 已经不在这个群里了。")
            return
        }
        _state.value = _state.value.copy(streaming = true, error = null, typingSpeakerName = null)
        // v0.5.4 重试同样是「新的一轮」，起点代际重新取。
        turnWipeEpoch = container.wipeEpoch.value
        job = viewModelScope.launch {
            try {
                // 重试也是一次真实请求：同样受日/月上限约束（缺陷）
                usageLimitMessage()?.let { message ->
                    _state.value = _state.value.copy(streaming = false, error = message)
                    return@launch
                }
                // 依据：GroupChatEngine.kt:179-180,203-209：被 @ 点名那轮重试需保留点名语义与 2 次尝试机会；
                // 非点名轮次只锁定原发言人，不启用点名语义（原文没点名，prompt 不能假称「TA 点了你的名字」）。
                val turn = if (pending.addressed) {
                    runEngine(
                        userText = pending.userText,
                        addressedIds = listOf(pending.characterId),
                        lockedSpeakerId = null,
                    )
                } else {
                    runEngine(
                        userText = pending.userText,
                        addressedIds = emptyList(),
                        lockedSpeakerId = pending.characterId,
                    )
                }
                // v0.5.4 生成期间可能已清空 → 不落库、不改 UI、不补索引/记忆。
                if (wipedDuringTurn()) return@launch
                val spoken = turn.results.filterIsInstance<GroupTurnResult.Spoken>().firstOrNull()
                if (spoken != null) {
                    // 重试同样要过危机兜底（原轮失败时用户那条消息还没得到该有的兜底话术）
                    val creativeMode = container.settings.flow.first().creativeMode
                    val text = CrisisReplyGuard.guard(spoken.text, recentUserTexts(), creativeMode)
                    val now = nextCreatedAt()
                    chat.append(
                        threadId = threadId,
                        segmentId = segmentId,
                        role = "assistant",
                        text = text,
                        tokens = text.length / 2 + 4,
                        createdAt = now,
                        speakerCharacterId = spoken.characterId,
                    )
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.map { existing ->
                            if (existing.id == messageId) {
                                GroupMessageUi(
                                    id = messageId,
                                    role = GroupMessageRole.MEMBER,
                                    text = text,
                                    speakerId = spoken.characterId,
                                    speakerName = spoken.name.ifBlank { existing.speakerName ?: "TA" },
                                    kind = GroupMessageKind.MAIN,
                                    addressed = pending.addressed,
                                )
                            } else {
                                existing
                            }
                        },
                        error = null,
                    )
                    failedTurns.remove(messageId)
                    recentAiReplies = (recentAiReplies + text).takeLast(RECENT_AI_REPLY_LIMIT)
                    // 轮换账本在原始那轮结束时就已结算（见 applyLedger），重试是同一轮的重跑，
                    // 不再重复 +1，否则一条用户消息会把别人的冷落轮数算两遍。
                    // 重试成功同样要补检索块与记忆（失败那轮没走到后处理）
                    onTurnLanded(spoken.characterId, pending.userText, text)
                } else {
                    // 仍失败/沉默：保留重试入口并刷新失败原因，不追加消息
                    val failedMain = turn.results.filterIsInstance<GroupTurnResult.Failed>()
                        .firstOrNull { !it.silent }
                    val mentionMissed = turn.results.filterIsInstance<GroupTurnResult.Passed>()
                        .any { it.mentionMissed }
                    val nextHint = when {
                        failedMain != null -> failedMain.errorMessage
                        mentionMissed -> null
                        else -> null
                    }
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.map { existing ->
                            if (existing.id == messageId) {
                                existing.copy(failureHint = nextHint)
                            } else {
                                existing
                            }
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = "没接上。再点一次试试？")
            } finally {
                _state.value = _state.value.copy(streaming = false, typingSpeakerName = null)
            }
        }
    }

    fun dismissAiNotice() {
        container.aiBadgeDismissed = true
        _state.value = _state.value.copy(showAiNotice = false)
    }

    // ------------------------------------------------------------------ 引擎与落库

    private suspend fun runEngine(
        userText: String,
        addressedIds: List<String> = emptyList(),
        lockedSpeakerId: String? = null,
    ): GroupTurn {
        val engine = GroupChatEngine(
            history = RoomGroupHistoryPort(chat, container.characters),
            memory = RoomGroupMemoryPort(container),
            reply = LlmGroupReplyPort(container) { member ->
                // 引擎不提前暴露选人计划：从生成端口拿"当前是谁在写"（只显示一个）
                _state.value = _state.value.copy(
                    typingSpeakerName = member.name.ifBlank { member.characterId },
                )
            },
        )
        return engine.runTurn(
            threadId = threadId,
            members = members,
            userText = userText,
            addressedToIds = addressedIds,
            lockedSpeakerId = lockedSpeakerId,
            lastSpeakers = lastSpeakers,
            recentAiReplies = recentAiReplies,
            // 默认只 1 人主回（紧凑模式）；「多陪一句」开关 v0.2 未做入口。
            // 点名两人时不看这个开关：第 2 个被 @ 者固定给一条短反应
            allowSecondResponder = false,
        )
    }

    private suspend fun applyTurn(
        turn: GroupTurn,
        userMessageId: String,
        userText: String,
        addressedIds: Set<String> = emptySet(),
    ) {
        // v0.5.4 生成期间发生过清空 → 这一轮结果整份丢弃（不落库、不改 UI、不结算账本）。
        if (wipedDuringTurn()) return
        val plan = turn.plan
        if (plan == null) {
            _state.value = _state.value.copy(
                error = "群里至少要有 ${GroupRules.MIN_MEMBERS} 个角色才聊得起来。",
            )
        } else {
            // 危机兜底取最近 3 条用户消息（含本轮）；send() 已先落库并加进 UI 列表
            val recentUserMessages = recentUserTexts()
            val creativeMode = container.settings.flow.first().creativeMode
            var mainSpeakerId: String? = null
            var mainReplyText: String? = null
            for (result in turn.results) {
                when (result) {
                    is GroupTurnResult.Spoken -> {
                        // v0.5.4 多轮生成会跨挂起点，每条落库前再比对一次（清空后不写孤儿消息）。
                        if (wipedDuringTurn()) return
                        val isMain = result.characterId == plan.primary.characterId
                        // 群聊也必须先过危机兜底再落库/上屏
                        val text = if (isMain) {
                            CrisisReplyGuard.guard(result.text, recentUserMessages, creativeMode)
                        } else {
                            result.text
                        }
                        val entity = chat.append(
                            threadId = threadId,
                            segmentId = segmentId,
                            role = "assistant",
                            text = text,
                            tokens = text.length / 2 + 4,
                            createdAt = nextCreatedAt(),
                            speakerCharacterId = result.characterId,
                        )
                        _state.value = _state.value.copy(
                            messages = _state.value.messages + GroupMessageUi(
                                id = entity.id,
                                role = GroupMessageRole.MEMBER,
                                text = text,
                                speakerId = result.characterId,
                                speakerName = result.name.ifBlank { "TA" },
                                kind = if (isMain) GroupMessageKind.MAIN else GroupMessageKind.REACTION,
                                // 「被叫到」标记属于**被 @ 的人**，不是属于主回。
                                // 同时 @ 两人时第 2 人只有一条 ≤20 字短反应，TA 也是被用户点名叫到的，
                                // 必须和主回一样带 addressed=true；未被 @ 的人一律不带。
                                addressed = isAddressedReply(result.characterId, addressedIds),
                            ),
                        )
                        recentAiReplies = (recentAiReplies + text).takeLast(RECENT_AI_REPLY_LIMIT)
                        if (isMain) {
                            mainSpeakerId = result.characterId
                            mainReplyText = text
                        }
                    }

                    is GroupTurnResult.Failed -> {
                        // 短反应失败必须无痕；只有主回失败留一行可点重试，不出现占位气泡
                        if (!result.silent) {
                            val addressed = result.characterId in addressedIds
                            val id = failedMessageId(userMessageId)
                            failedTurns[id] = FailedMain(
                                userText = userText,
                                characterId = result.characterId,
                                addressed = addressed,
                            )
                            _state.value = _state.value.copy(
                                messages = _state.value.messages + GroupMessageUi(
                                    id = id,
                                    role = GroupMessageRole.MEMBER,
                                    speakerId = result.characterId,
                                    speakerName = result.name.ifBlank { "TA" },
                                    failed = true,
                                    addressed = addressed,
                                    failureHint = result.errorMessage,
                                ),
                            )
                        }
                    }

                    is GroupTurnResult.Passed -> {
                        // 普通 [PASS] / 去重 / 太短：不落库、不显示（沉默是一等功能）。
                        // 例外：被 @ 者重试后仍沉默 → 点名是用户的显式意愿，给一次可见可重试的行，
                        // 但**不换人**。
                        if (result.mentionMissed) {
                            val addressed = result.characterId in addressedIds
                            val id = failedMessageId(userMessageId)
                            failedTurns[id] = FailedMain(
                                userText = userText,
                                characterId = result.characterId,
                                addressed = addressed,
                            )
                            _state.value = _state.value.copy(
                                messages = _state.value.messages + GroupMessageUi(
                                    id = id,
                                    role = GroupMessageRole.MEMBER,
                                    speakerId = result.characterId,
                                    speakerName = result.name.ifBlank { "TA" },
                                    failed = true,
                                    addressed = addressed,
                                ),
                            )
                        }
                    }

                    is GroupTurnResult.Rejected -> _state.value = _state.value.copy(
                        error = "群里至少要有 ${GroupRules.MIN_MEMBERS} 个角色才聊得起来。",
                    )
                }
            }
            mainSpeakerId?.let { primaryId ->
                onTurnLanded(primaryId, userText, mainReplyText.orEmpty())
            }
        }
        // 账本结算放在所有分支之后：主回成功 / 失败 / [PASS] 都算这一轮结束
        applyLedger(turn.plan, turn.results)
    }

    // ------------------------------------------------------------------ 后处理（记忆 + 检索块）

    /**
     * 一轮群聊成功后的两件后台后处理（**不阻塞 UI、失败静默**）：
     *
     * 1. **检索块**：把「用户消息 + 主回」写成群 thread 的 chunk，新群从第二轮起就能被关键词
     *    召回（此前群聊完全不写 chunk，召回永远为空）。`chunk` 表没有 characterId 维度
     *    （未改检索块 schema），所以按群共享历史写—— 允许成员共享历史；
     *    `embed = false`：群召回只走关键词倒排，不做 embedding（省一次调用）。
     * 2. **各自记忆**：每 [MEMORY_EXTRACT_EVERY_ROUNDS] 轮或累计
     *    [MEMORY_EXTRACT_TOKEN_BUDGET] token，对**主回角色**做一次轻量增量抽取
     *    。每次只抽一个人，避免成本随群人数线性上涨。
     *
     * 轮换账本（`silentTurns` / `lastSpeakers`）仍由 [applyLedger] 维护，不受影响。
     */
    private fun onTurnLanded(primaryId: String, userText: String, replyText: String) {
        if (replyText.isBlank()) return
        // v0.5.4 清空后不再补检索块/记忆（否则给已删群写孤儿索引、往已清空的库写记忆）。
        if (wipedDuringTurn()) return
        // v0.5.5 （群聊同类）：把**这一刻**的代际固定成局部值贯穿进后台协程与 ChatEngine。
        // 不能到协程里再读 `turnWipeEpoch`：它是 var，下一轮 send/retry 会改写；也不能让引擎
        // 内部现取当前代际——那样「VM 检查通过 → 引擎真正写 chunk」之间的清空会被漏掉，
        // 旧群的正文就会被写成已清空库里的 chunk（与 1:1 的 同一窗口）。
        val landedWipeEpoch = turnWipeEpoch
        roundsSinceMemoryExtract++
        tokensSinceMemoryExtract += TokenEstimator.estimate(userText) + TokenEstimator.estimate(replyText)
        val due = roundsSinceMemoryExtract >= MEMORY_EXTRACT_EVERY_ROUNDS ||
            tokensSinceMemoryExtract >= MEMORY_EXTRACT_TOKEN_BUDGET
        if (due) {
            roundsSinceMemoryExtract = 0
            tokensSinceMemoryExtract = 0
        }
        // 用应用级作用域：切页/锁屏不打断，且不占用 UI 协程（「全程不阻塞发送」）
        container.appScope.launch {
            // v0.5.4 到协程真正开始之间可能刚被清空，再比对一次（与 1:1 同一写法）。
            if (wipedDuringTurn()) return@launch
            runCatching {
                container.engine.indexTurn(
                    threadId = threadId,
                    segmentId = segmentId,
                    userText = userText,
                    assistantText = replyText,
                    embed = false,
                    // v0.5.5 把本轮捕获的同一份代际交给引擎，内部判定不重新取样。
                    expectedWipeEpoch = landedWipeEpoch,
                )
            }
            if (due) runCatching { extractMemberMemory(primaryId) }
        }
    }

    /**
     * 对 [characterId] 做一次轻量记忆抽取（"各自记得"的实际落点）。
     *
     * 隔离的双重保证：
     * - 输入侧：[GroupMemoryShard.select] 只保留「用户的话 + 该角色自己的话」，
     *   别人的台词在 prompt 里根本不存在；
     * - 写入侧：[cc.pulsez.app.data.repo.MemoryRepository.applyPatch] 固定传
     *   `ownerId = characterId`，只写该角色的 `(threadId, characterId)` 维度
     *   （「记忆按角色隔离」）。
     */
    private suspend fun extractMemberMemory(characterId: String) {
        // v0.5.4 请求跨挂起点，落库/记账前都要按本轮代际收口。
        if (wipedDuringTurn()) return
        val thread = container.database.threadDao().byId(threadId) ?: return
        val settings = container.settings.flow.first()
        if (settings.chatModel.isBlank()) return
        val client = container.llmClient() ?: return
        val history = RoomGroupHistoryPort(chat, container.characters).history(threadId)
        val shard = GroupMemoryShard.select(history, characterId)
        if (shard.isEmpty()) return
        GroupMemoryExtractor(
            completeJson = { request -> client.completeJson(request) },
            applyPatch = { json ->
                // 清空后不得写记忆（父群已删）；跳过写入即可，提取器本身不因跳过而抛错。
                if (!wipedDuringTurn()) {
                    container.memory.applyPatch(
                        threadId = threadId,
                        patchJson = json,
                        segmentId = thread.activeSegmentId,
                        ownerId = characterId,
                    )
                }
            },
            recordUsage = { usage ->
                // 清空后不再记这笔用量（否则用量页会重新出现清空前的花费）。
                if (!wipedDuringTurn()) container.engine.recordUsage(settings.chatModel, usage)
            },
        ).extract(
            model = settings.chatModel,
            shard = shard,
            // 群聊记忆抽取是内部请求：DeepSeek 端点必须显式关思考。
            internalBaseUrl = settings.chatBaseUrl,
        )
    }

    /**
     * 一轮结束时的轮换账本结算。
     *
     * 与旧实现的区别：不再只在主回成功时记账。**成功、失败、`[PASS]` 三种结局都按同一规则结算**
     * （被计划到的人清零并进冷却、其余人 +1），纯逻辑在 [SpeakerLedger] 里，可直接单测。
     *
     * 结算完立即写 DataStore（缺陷）：账本是跨进程状态，杀进程后要能恢复，
     * 否则失败 / `[PASS]` 轮次对冷落计数的影响会丢。写入失败静默（[cc.pulsez.app.data.group.GroupLedgerStore] 内部吞掉）。
     */
    private suspend fun applyLedger(plan: Plan?, results: List<GroupTurnResult>) {
        // v0.5.4 清空时 `groupLedger.clearAll()` 已把账本删掉；这一轮是清空前的，不得写回。
        if (wipedDuringTurn()) return
        val update = SpeakerLedger.update(
            members = members,
            lastSpeakers = lastSpeakers,
            plan = plan,
            results = results,
        )
        lastSpeakers = update.lastSpeakers
        members = members.map { member ->
            member.copy(silentTurns = update.silentTurns[member.characterId] ?: member.silentTurns)
        }
        container.groupLedger.save(
            threadId = threadId,
            snapshot = SpeakerLedgerRestore.snapshot(members, lastSpeakers),
        )
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 发送前的日/月上限检查（缺陷 「到 100% 停止发起新请求」）。
     *
     * **每次都重新读当前值，不缓存**：用户在设置里调高上限后，下一句立刻能发出去。
     *
     * @return 超限时给 UI 的提示文案；没超限返回 null
     */
    private suspend fun usageLimitMessage(): String? {
        val settings = container.settings.flow.first()
        val snapshot = container.usage.flow.first()
        return UsageLimits.message(
            snapshot = snapshot,
            dailyLimitYuanX100 = settings.dailyLimitYuanX100,
            monthlyLimitYuanX100 = settings.monthlyLimitYuanX100,
        )
    }

    /** 最近 3 条用户消息（旧→新，含本轮；危机兜底用，与 1:1 口径一致）。 */
    private fun recentUserTexts(): List<String> = _state.value.messages
        .filter { it.role == GroupMessageRole.USER }
        .map { it.text }
        .takeLast(CrisisReplyGuard.RECENT_USER_LIMIT)

    /** 同轮多条消息严格递增的 createdAt（同毫秒会破坏 groupHistory 的稳定顺序）。 */
    private fun nextCreatedAt(): Long {
        val now = System.currentTimeMillis()
        appendClock = maxOf(now, appendClock + 1)
        return appendClock
    }

    /**
     * 点名解析已收口到纯函数 [MentionParser]（最长匹配 / 全角 ＠ / 标点后缀 / 去重 / 最多 2 人 /
     * `@所有人` 不广播）。这里不再手写 `indexOf`：子串匹配会把 `@阿淮` 误中 `@阿淮淮` 的前缀。
     */
    private fun failedMessageId(userMessageId: String): String = "failed-$userMessageId"

    companion object {
        /** 角色表暂无"话多程度"字段，取 的默认值。*/
        private const val DEFAULT_CHATTY_SCORE: Double = 0.5

        /** 参与去重的最近 AI 回复条数（「最近 3 条」）。*/
        private const val RECENT_AI_REPLY_LIMIT: Int = 3

        /** 群聊 UI 首次仅载入最新 80 条；向前每页 60 条。 */
        private const val UI_INITIAL_PAGE_SIZE: Int = 80
        private const val UI_OLDER_PAGE_SIZE: Int = 60

        /** 群聊记忆增量抽取节奏：每 8 轮一次（「≥8 轮」）。*/
        private const val MEMORY_EXTRACT_EVERY_ROUNDS: Int = 8

        /** 群聊记忆增量抽取节奏：新消息累计 3k token 也触发（「3–5k token」取低值控成本）。*/
        private const val MEMORY_EXTRACT_TOKEN_BUDGET: Int = 3_000

        fun factory(container: AppContainer, threadId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { GroupChatViewModel(container, threadId) }
            }
    }
}
