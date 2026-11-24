package cc.pulsez.app.core

import cc.pulsez.app.mem.AssembleInput
import cc.pulsez.app.mem.BigramIndex
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.mem.PromptAssembler
import cc.pulsez.app.mem.Recall
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/**
 * 本轮「谁说话」的本地选人结果（0 次模型调用）。
 *
 * @property primary 主回角色，必有；主回必须回复，沉默时会换人或重试
 * @property secondary 短反应角色（可选，≤20 字）：开启「多陪一句」且命中概率时才有；
 *   **点名两人时**一定是第 2 个被 @ 者，不受概率影响
 */
data class Plan(
    val primary: GroupMember,
    val secondary: GroupMember? = null,
)

/**
 * 一轮群聊的完整产出：选人计划 + 生成结果。
 *
 * `plan == null` 表示输入不合法（成员数不在 `2..hardCap`），此时 [results] 只有一条
 * [GroupTurnResult.Rejected]，任何端口都没被调用。
 *
 * `results` 可能比 `plan` 少：短反应只有在主回成功、本轮字数还有余量、且没被判为沉默时才执行。
 * 顺序固定为「主回在前，短反应在后」。
 */
data class GroupTurn(
    val plan: Plan?,
    val results: List<GroupTurnResult>,
)

/**
 * 群聊一轮中单个发言人的结果。
 *
 * - [Spoken]：成功发言，写入数据库时保存 `speakerCharacterId`（一条消息只属于一个发言人）；
 * - [Passed]：`[PASS]` / 去重丢弃 / 太短，视为**本轮不说话**——不落库、UI 不显示任何东西，
 *   这是正常状态不是失败；
 * - [Failed]：主回失败时 `silent=false`（UI 显示一次可点重试，**不显示「暂时没说话」占位**）；
 *   短反应失败时 `silent=true`（无任何 UI 变化）；
 * - [Rejected]：整轮未开始（成员数不合法），`plan` 为 null。
 */
sealed interface GroupTurnResult {

    /**
     * 成功发言。
     *
     * @property characterId 发言人角色 id
     * @property name 发言人展示名
     * @property text 正文（已 trim、已按句截断，非空）
     * @property usageTokens 本次调用消耗 token（成本记账用，0 表示未知）
     */
    data class Spoken(
        val characterId: String,
        val name: String,
        val text: String,
        val usageTokens: Int = 0,
    ) : GroupTurnResult

    /**
     * 该发言人失败。
     *
     * @property reason 面向诊断的简短原因（不含正文/密钥；UI 文案自行生成）
     * @property usageTokens 失败前已消耗 token（0 表示未知）
     * @property silent true = 短反应失败，UI **不得**产生任何元素；
     *   false = 主回失败，UI 显示一次「重试」入口
     */
    data class Failed(
        val characterId: String,
        val name: String,
        val reason: String,
        val usageTokens: Int = 0,
        val silent: Boolean = false,
        val errorMessage: String? = null,
    ) : GroupTurnResult

    /**
     * 本轮不说话（`[PASS]`、去重丢弃、或内容太短）。不落库、不显示，不要当失败处理。
     *
     * @property mentionMissed 仅当**被 @ 者重试后仍然沉默**时为 true：
     *   点名是用户的显式意愿，不能像普通 `[PASS]` 一样无声无息。UI 据此显示一次可点重试，
     *   但**绝不换人**（换人会让用户以为"我点错了"）。
     */
    data class Passed(
        val characterId: String,
        val name: String,
        val mentionMissed: Boolean = false,
    ) : GroupTurnResult

    /**
     * 输入不合法，整轮未执行。
     *
     * @property reason 面向诊断的原因
     */
    data class Rejected(val reason: String) : GroupTurnResult
}

/**
 * 「一起聊」的纯逻辑编排，确保群聊中每个角色不会抢话。
 *
 * 一条用户消息的处理：
 * 1. **本地选人**（0 次模型调用）：点名优先 → 打分（相关度/冷落/话多/抖动）→
 *    2 轮冷却 → 冷落兜底 → 可选短反应；点名到 2 人时第 2 人只能是短反应，
 *    `@所有人` 不广播、按普通消息走默认选人；
 * 2. 只给选中的人组装 prompt：①（协议+人设+样本+**稳定群聊规则**）跨轮字节不变，
 *    ④ 才放「这一轮轮到谁 / 主回还是短反应 / 最多多少字 / 是否被点名」这类**易变信息**；
 * 3. 主回 ≤120 汉字、短反应 ≤20 汉字、单轮合计 ≤180 汉字，引擎按句末标点截断；
 * 4. `[PASS]` 是一等公民：返回 [GroupTurnResult.Passed]，不落库、不显示；主回失败
 *    [GroupTurnResult.Failed]（UI 只多一次重试），短反应失败 `silent=true` 无痕；
 *    **被 @ 者例外**：不许 PASS，同一角色重试 1 次，仍失败才给可见可重试的
 *    [GroupTurnResult.Passed.mentionMissed] / [GroupTurnResult.Failed]，绝不换人；
 * 5. 后处理：剪掉「其他角色名：」起的部分，与最近 3 条 AI 回复做字符 3-gram Jaccard，
 *    相似度 >0.5 整条丢弃视为 `[PASS]`。
 *
 * 本类不碰 DB / HTTP / Android，全部依赖通过 [GroupHistoryPort] / [GroupMemoryPort] /
 * [GroupReplyPort] 注入。
 *
 * @param history 群聊共享历史端口
 * @param memory 按角色隔离的记忆端口
 * @param reply 单成员生成端口，由调用方接入 `LlmClient`
 * @param hardCap 群成员硬上限（`GROUP_MAX_MEMBERS`=4）
 * @param secondResponderProbability 「多陪一句」开启时出现短反应的概率
 * @param random 抖动与第二人概率的随机源；测试可注入固定种子保证可复现
 */
class GroupChatEngine(
    private val history: GroupHistoryPort,
    private val memory: GroupMemoryPort,
    private val reply: GroupReplyPort,
    private val hardCap: Int = HARD_CAP,
    private val secondResponderProbability: Double = SECOND_RESPONDER_PROBABILITY,
    private val random: Random = Random.Default,
) {

    init {
        require(hardCap >= MIN_MEMBERS) { "hardCap 至少 $MIN_MEMBERS，当前 $hardCap" }
        require(secondResponderProbability in 0.0..1.0) {
            "secondResponderProbability 必须在 0.0..1.0，当前 $secondResponderProbability"
        }
    }

    /**
     * 执行一轮群聊：本地选人 → 主回 →（可选）短反应。
     *
     * 成员数不在 `[MIN_MEMBERS]..[hardCap]` 时**不抛异常**，返回
     * `GroupTurn(plan = null, results = [Rejected])` 且不调用任何端口。
     *
     * @param threadId 群聊 thread id
     * @param members 当前群成员（内部按 [GroupMember.ordinal] 排序）
     * @param userText 本轮用户消息（会作为 ⑤ 段注入）
     * @param addressedTo 旧的点名参数：角色 id 或成员展示名（可带前导 `@`）。
     *   等价于只含一个元素的 [addressedToIds]（命中时走点名语义：压过打分/冷却/冷落兜底，
     *   不许 `[PASS]`，失败同角色重试 1 次）；`@所有人`/`@全体` 按未点名处理。
     *   传 null 且 [addressedToIds] 为空时走默认选人
     * @param lastSpeakers 最近若干轮的主回角色 id，**新→旧**；引擎只看最近
     *   [SPEAKER_COOLDOWN_TURNS] 个做「2 轮内不连任」（step 3）
     * @param recentAiReplies 最近几条 AI 回复正文（**旧→新**，引擎取最后
     *   [GroupPostProcess.DEDUPE_RECENT_LIMIT] 条做 3-gram Jaccard 去重）
     * @param allowSecondResponder 是否开启「多陪一句」：true 时按
     *   [secondResponderProbability] 追加 1 条 ≤20 字短反应（点名已达 2 人时不看这个开关）
     * @param addressedToIds 被 @ 的成员 id，**按正文出现顺序**、已去重、最多 2 个
     *   （[MentionParser] 的输出）。第 1 个是主回且不许 `[PASS]`（重试 1 次、不换人），
     *   第 2 个只给 ≤20 字短反应，第 3 个起忽略。空列表走默认选人
     * @param lockedSpeakerId 重试专用：只锁定某位成员当主回，**不启用点名语义**
     *   （不出「点了你的名字」prompt、`[PASS]` 不换人）。用于 UI「刚刚没接上，点这里重试」
     * @return 选人计划 + 按「主回→短反应」顺序排列的结果列表
     */
    suspend fun runTurn(
        threadId: String,
        members: List<GroupMember>,
        userText: String,
        addressedTo: String? = null,
        lastSpeakers: List<String> = emptyList(),
        recentAiReplies: List<String> = emptyList(),
        allowSecondResponder: Boolean = false,
        addressedToIds: List<String> = emptyList(),
        lockedSpeakerId: String? = null,
    ): GroupTurn {
        if (members.size !in MIN_MEMBERS..hardCap) {
            return GroupTurn(
                plan = null,
                results = listOf(
                    GroupTurnResult.Rejected(
                        "群聊需要 $MIN_MEMBERS–$hardCap 位成员，当前 ${members.size} 位",
                    ),
                ),
            )
        }

        val ordered = members.sortedBy { it.ordinal }
        // 点名目标：新参数优先（按正文出现顺序、最多 2 个，[MentionParser] 已去掉广播词）；
        // 旧参数 `addressedTo` 等价于只含一个元素的 addressedToIds。
        // 都解析不到人（例如被 @ 者已退群）时静默降级为默认选人，不报错。
        val mentionTargets = resolveMentionTargets(addressedToIds, ordered)
            .ifEmpty { listOfNotNull(resolveAddressed(addressedTo, ordered)) }
        val isMentionTurn = mentionTargets.isNotEmpty()
        // 重试专用锁定：只锁定发言人，不启用点名语义（不出「点了你的名字」、PASS 不换人）
        val lockedSpeaker = if (isMentionTurn) null else resolveAddressed(lockedSpeakerId, ordered)
        var plan = when {
            isMentionTurn -> Plan(
                primary = mentionTargets[0],
                // 第 2 个被 @ 者：只给短反应（≤20 字），与「多陪一句」的概率开关无关
                secondary = mentionTargets.getOrNull(1),
            )

            lockedSpeaker != null -> Plan(primary = lockedSpeaker, secondary = null)
            else -> selectPlan(ordered, userText, lastSpeakers, allowSecondResponder)
        }
        val shared = loadSharedHistory(threadId, ordered, userText)
        val allNames = ordered.map { displayName(it) }
        val results = ArrayList<GroupTurnResult>(3)
        var usedChars = 0
        var mainMember = plan.primary
        var primarySpokenText: String? = null

        // ---------------------------------------------------------- 主回
        if (isMentionTurn) {
            // 被 @ 者必回、不许 PASS：只给它自己重试 1 次，**绝不换人**。
            // 重试仍沉默才给 Passed(mentionMissed=true)，由 UI 显示一次「没接上，重试」。
            when (
                val outcome = generateMentionMain(
                    threadId = threadId,
                    member = mainMember,
                    roster = ordered,
                    shared = shared,
                    userText = userText,
                    recentAiReplies = recentAiReplies,
                    allNames = allNames,
                )
            ) {
                is MentionOutcome.Spoken -> {
                    results += GroupTurnResult.Spoken(
                        characterId = outcome.member.characterId,
                        name = displayName(outcome.member),
                        text = outcome.text,
                        usageTokens = outcome.usageTokens,
                    )
                    usedChars = GroupPostProcess.visibleLength(outcome.text)
                    shared += MemMessage.assistant(historyLine(outcome.member.name, outcome.text))
                    primarySpokenText = outcome.text
                }

                is MentionOutcome.Failure -> results += GroupTurnResult.Failed(
                    characterId = outcome.member.characterId,
                    name = displayName(outcome.member),
                    reason = outcome.reason,
                    usageTokens = outcome.usageTokens,
                    errorMessage = outcome.errorMessage,
                )

                is MentionOutcome.Silent -> results += GroupTurnResult.Passed(
                    characterId = outcome.member.characterId,
                    name = displayName(outcome.member),
                    mentionMissed = true,
                )
            }
        } else {
            val firstAttempt = generateMain(
                threadId = threadId,
                member = mainMember,
                roster = ordered,
                shared = shared,
                userText = userText,
                recentAiReplies = recentAiReplies,
                allNames = allNames,
            )
            when (firstAttempt) {
                is MainAttempt.Spoken -> {
                    results += firstAttempt.toResult()
                    usedChars = GroupPostProcess.visibleLength(firstAttempt.text)
                    // 共享历史只追加，下一位（以及下一轮）能看到（隔离原则）
                    shared += MemMessage.assistant(historyLine(firstAttempt.member.name, firstAttempt.text))
                    primarySpokenText = firstAttempt.text
                }

                is MainAttempt.Failure -> results += firstAttempt.toResult()

                is MainAttempt.Silent -> {
                    results += GroupTurnResult.Passed(firstAttempt.member.characterId, displayName(firstAttempt.member))
                    // 缺陷 （「主回不能沉默」）：旧实现主回输出 [PASS] 就整轮静默，用户这轮
                    // 什么都看不到。现在按同一套选人打分换下一个候选再说一次，**最多换 1 次**（整轮调用 ≤2）；
                    // 换人不是追加第二条：一旦换人就不再出短反应，保持「默认 1 人可见」。
                    // 只有真正的 [PASS] 才换人；去重丢弃 / 太短是内容质量问题，换人也只会再丢一次，不换。
                    // 重试锁定（lockedSpeakerId）的轮次同样不换人：用户点的是「再试一次」，不是「换个人」。
                    if (firstAttempt.pass && lockedSpeaker == null) {
                        val fallback = selectFallback(ordered, userText, firstAttempt.member.characterId, lastSpeakers)
                        if (fallback != null) {
                            mainMember = fallback
                            plan = Plan(primary = fallback, secondary = null)
                            val secondAttempt = generateMain(
                                threadId = threadId,
                                member = fallback,
                                roster = ordered,
                                shared = shared,
                                userText = userText,
                                recentAiReplies = recentAiReplies,
                                allNames = allNames,
                            )
                            when (secondAttempt) {
                                is MainAttempt.Spoken -> {
                                    results += secondAttempt.toResult()
                                    usedChars = GroupPostProcess.visibleLength(secondAttempt.text)
                                    shared += MemMessage.assistant(
                                        historyLine(secondAttempt.member.name, secondAttempt.text),
                                    )
                                    primarySpokenText = secondAttempt.text
                                }

                                is MainAttempt.Failure -> results += secondAttempt.toResult()

                                is MainAttempt.Silent -> results += GroupTurnResult.Passed(
                                    secondAttempt.member.characterId,
                                    displayName(secondAttempt.member),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- 短反应
        val secondary = plan.secondary
        if (secondary != null && primarySpokenText != null) {
            val remaining = TOTAL_MAX_CHARS - usedChars
            if (remaining > 0) {
                val maxChars = minOf(SHORT_MAX_CHARS, remaining)
                val prompt = assemblePrompt(
                    threadId = threadId,
                    member = secondary,
                    roster = ordered,
                    shared = shared,
                    userText = userText,
                    role = GroupTurnRole.SHORT_REACTION,
                    primaryName = displayName(mainMember),
                    mentioned = isMentionTurn,
                )
                val generated = callReply(secondary, prompt, SHORT_MAX_TOKENS)
                if (generated.isFailure) {
                    // 短反应本来就是可选的：失败必须无痕
                    results += GroupTurnResult.Failed(
                        characterId = secondary.characterId,
                        name = displayName(secondary),
                        reason = failureReason(generated.exceptionOrNull()),
                        silent = true,
                    )
                } else {
                    val reply = generated.getOrThrow()
                    if (reply.failed) {
                        results += GroupTurnResult.Failed(
                            characterId = secondary.characterId,
                            name = displayName(secondary),
                            reason = FAILURE_REASON_REPORTED,
                            usageTokens = reply.usageTokens,
                            silent = true,
                            errorMessage = reply.errorMessage,
                        )
                    } else {
                        val text = GroupPostProcess.finalize(
                            text = reply.text,
                            maxChars = maxChars,
                            minChars = 0,
                            foreignNames = allNames - displayName(secondary),
                            recent = (recentAiReplies.takeLast(GroupPostProcess.DEDUPE_RECENT_LIMIT) +
                                listOfNotNull(primarySpokenText))
                                .takeLast(GroupPostProcess.DEDUPE_RECENT_LIMIT),
                        )
                        if (text == null) {
                            results += GroupTurnResult.Passed(secondary.characterId, displayName(secondary))
                        } else {
                            results += GroupTurnResult.Spoken(
                                characterId = secondary.characterId,
                                name = displayName(secondary),
                                text = text,
                                usageTokens = reply.usageTokens,
                            )
                        }
                    }
                }
            }
        }

        return GroupTurn(plan = plan, results = results)
    }


    // 主回生成（含 `[PASS]` 换人，缺陷）


    /** 一次主回尝试的结局；`[PASS]` 与「去重/太短」分开，因为只有前者允许换人。 */
    private sealed interface MainAttempt {
        val member: GroupMember

        data class Spoken(
            override val member: GroupMember,
            val text: String,
            val usageTokens: Int,
        ) : MainAttempt

        /** 没有说话：`pass = true` 表示模型输出 `[PASS]`；false 表示去重丢弃 / 太短。 */
        data class Silent(
            override val member: GroupMember,
            val pass: Boolean,
        ) : MainAttempt

        data class Failure(
            override val member: GroupMember,
            val reason: String,
            val usageTokens: Int = 0,
            val errorMessage: String? = null,
        ) : MainAttempt
    }

    private fun MainAttempt.Spoken.toResult(): GroupTurnResult.Spoken = GroupTurnResult.Spoken(
        characterId = member.characterId,
        name = displayName(member),
        text = text,
        usageTokens = usageTokens,
    )

    private fun MainAttempt.Failure.toResult(): GroupTurnResult.Failed = GroupTurnResult.Failed(
        characterId = member.characterId,
        name = displayName(member),
        reason = reason,
        usageTokens = usageTokens,
        errorMessage = errorMessage,
    )

    /**
     * 生成一次主回并做后处理。
     *
     * 与短反应共用同一套 prompt 分层和截断规则；区别只是主回的 `[PASS]` 会被上层用来触发换人。
     *
     * @param mentioned 是否「被 @ 者」：true 时 ④ 段换成点名指令（不许 PASS），只影响 ④，不进 ①
     *   （点名信息只放易变段，进了稳定段会打断前缀缓存 ≈32× 成本）
     */
    private suspend fun generateMain(
        threadId: String,
        member: GroupMember,
        roster: List<GroupMember>,
        shared: List<MemMessage>,
        userText: String,
        recentAiReplies: List<String>,
        allNames: List<String>,
        mentioned: Boolean = false,
    ): MainAttempt {
        val prompt = assemblePrompt(
            threadId = threadId,
            member = member,
            roster = roster,
            shared = shared,
            userText = userText,
            role = GroupTurnRole.MAIN,
            primaryName = null,
            mentioned = mentioned,
        )
        val generated = callReply(member, prompt, MAIN_MAX_TOKENS)
        if (generated.isFailure) {
            return MainAttempt.Failure(member, failureReason(generated.exceptionOrNull()))
        }
        val reply = generated.getOrThrow()
        if (reply.failed) {
            return MainAttempt.Failure(
                member = member,
                reason = FAILURE_REASON_REPORTED,
                usageTokens = reply.usageTokens,
                errorMessage = reply.errorMessage,
            )
        }
        val text = GroupPostProcess.finalize(
            text = reply.text,
            maxChars = MAIN_MAX_CHARS,
            minChars = MIN_MAIN_CHARS,
            foreignNames = allNames - displayName(member),
            recent = recentAiReplies.takeLast(GroupPostProcess.DEDUPE_RECENT_LIMIT),
        )
        return if (text == null) {
            MainAttempt.Silent(member, pass = GroupPostProcess.isPass(reply.text))
        } else {
            MainAttempt.Spoken(member, text, reply.usageTokens)
        }
    }


    // 被 @ 者的主回（必回、失败重试一次、绝不换人）


    /** 被 @ 者主回的最终结局；中间那次尝试不对外暴露，避免 UI 出现「失败 + 回复」两行。 */
    private sealed interface MentionOutcome {
        val member: GroupMember

        data class Spoken(
            override val member: GroupMember,
            val text: String,
            val usageTokens: Int,
        ) : MentionOutcome

        data class Failure(
            override val member: GroupMember,
            val reason: String,
            val usageTokens: Int,
            val errorMessage: String? = null,
        ) : MentionOutcome

        /** 两次尝试都没说出口（`[PASS]` / 太短 / 去重丢弃）：整轮失败可见、可重试，但不换人。 */
        data class Silent(override val member: GroupMember) : MentionOutcome
    }

    /**
     * 被 @ 者的主回：同一角色**最多调用 2 次**（首次 + 失败/PASS 后重试 1 次）。
     *
     * 为什么重试同一个人而不是换人：用户点的是 A，A 没接上却由 B 回，
     * 用户第一反应会是"我是不是点错了 / 这个 App 不认我点的名字"。点名的价值是确定性。
     *
     * 只对外返回**最终**结局；两次调用的 usage 合并计入最终结局，成本记账不丢。
     */
    private suspend fun generateMentionMain(
        threadId: String,
        member: GroupMember,
        roster: List<GroupMember>,
        shared: List<MemMessage>,
        userText: String,
        recentAiReplies: List<String>,
        allNames: List<String>,
    ): MentionOutcome {
        var usageTotal = 0
        var lastAttempt: MainAttempt? = null
        repeat(MENTION_ATTEMPTS) {
            val attempt = generateMain(
                threadId = threadId,
                member = member,
                roster = roster,
                shared = shared,
                userText = userText,
                recentAiReplies = recentAiReplies,
                allNames = allNames,
                mentioned = true,
            )
            lastAttempt = attempt
            usageTotal += when (attempt) {
                is MainAttempt.Spoken -> attempt.usageTokens
                is MainAttempt.Failure -> attempt.usageTokens
                is MainAttempt.Silent -> 0
            }
            // 第 1 次成功就结束；失败/PASS 则再给同一个人 1 次机会（MENTION_ATTEMPTS = 2）
            if (attempt is MainAttempt.Spoken) return MentionOutcome.Spoken(member, attempt.text, usageTotal)
        }
        return finalMentionOutcome(member, lastAttempt, usageTotal)
    }

    private fun finalMentionOutcome(
        member: GroupMember,
        lastAttempt: MainAttempt?,
        usageTotal: Int,
    ): MentionOutcome = when (lastAttempt) {
        is MainAttempt.Failure -> MentionOutcome.Failure(
            member = member,
            reason = lastAttempt.reason,
            usageTokens = usageTotal,
            errorMessage = lastAttempt.errorMessage,
        )
        // Spoken 在上层已提前返回；这里剩下 Silent（PASS / 太短 / 去重）或理论上的 null
        else -> MentionOutcome.Silent(member)
    }

    /**
     * 主回 `[PASS]` 时的「下一个候选」：同一套本地打分（相关度/冷落/话多/抖动），
     * 排除刚刚沉默的那位；仍受 2 轮冷却约束（全被冷却时放宽）。
     *
     * 群成员下限是 2，所以只要存在其他成员就一定有人可换；都不存在时返回 null（整轮沉默）。
     */
    private fun selectFallback(
        ordered: List<GroupMember>,
        userText: String,
        excludeId: String,
        lastSpeakers: List<String>,
    ): GroupMember? {
        val candidates = ordered.filter { it.characterId != excludeId }
        if (candidates.isEmpty()) return null
        val queryTerms = BigramIndex.terms(userText)
        val cooldown = lastSpeakers.take(SPEAKER_COOLDOWN_TURNS).toSet()
        val scored = candidates.map { ScoredMember(it, scoreMember(it, queryTerms)) }
        val pool = scored.filterNot { it.member.characterId in cooldown }.ifEmpty { scored }
        return pool.maxByOrNull { it.score }?.member ?: candidates.first()
    }


    // 本地选人（0 次模型调用）


    private data class ScoredMember(val member: GroupMember, val score: Double)

    private fun selectPlan(
        ordered: List<GroupMember>,
        userText: String,
        lastSpeakers: List<String>,
        allowSecondResponder: Boolean,
    ): Plan {
        // 点名（含旧参数）在 [runTurn] 里已经处理完，这里只剩默认流水线
        // step 2：本地打分（相关度 0.55 + 冷落 0.25 + 话多 0.20 + 抖动 0~0.10）
        val queryTerms = BigramIndex.terms(userText)
        val scored = ordered.map { member -> ScoredMember(member, scoreMember(member, queryTerms)) }
        val cooldown = lastSpeakers.take(SPEAKER_COOLDOWN_TURNS).toSet()

        // step 4：冷落兜底是硬规则，先于打分
        val starved = ordered.firstOrNull { it.silentTurns >= STARVE_TURNS }
        val primary = starved ?: run {
            val eligible = scored.filterNot { it.member.characterId in cooldown }
            val pool = eligible.ifEmpty { scored } // step 3：全被冷却就放宽
            pool.maxByOrNull { it.score }?.member ?: ordered.first()
        }

        // step 5：短反应（可选），同样受冷却约束
        val secondary = when {
            !allowSecondResponder || ordered.size < 2 -> null
            random.nextDouble() >= secondResponderProbability -> null
            else -> scored.asSequence()
                .filter { it.member.characterId != primary.characterId && it.member.characterId !in cooldown }
                .maxByOrNull { it.score }
                ?.member
        }
        return Plan(primary = primary, secondary = secondary)
    }

    private fun scoreMember(member: GroupMember, queryTerms: Set<String>): Double {
        val memberTerms = BigramIndex.terms(
            buildString {
                append(member.persona)
                for (sample in member.styleSamples) append('\n').append(sample)
            },
        )
        val relevance = if (queryTerms.isEmpty() || memberTerms.isEmpty()) {
            0.0
        } else {
            BigramIndex.score(queryTerms, memberTerms).toDouble() / queryTerms.size
        }
        val recency = minOf(member.silentTurns.coerceAtLeast(0), STARVE_TURNS).toDouble() / STARVE_TURNS
        val talkative = member.chattyScore.coerceIn(0.0, 1.0)
        return WEIGHT_RELEVANCE * relevance +
            WEIGHT_RECENCY * recency +
            WEIGHT_TALKATIVE * talkative +
            random.nextDouble() * JITTER_MAX
    }

    /**
     * 点名解析：只做精确匹配（角色 id 或完整展示名），不做模糊匹配
     * （模糊/最长匹配在 [MentionParser] 里做，那一步更懂「中文名字 + 全角 @」）。
     * `@所有人` / `@全体` 一律按未点名处理（绝不广播）。
     */
    private fun resolveAddressed(raw: String?, members: List<GroupMember>): GroupMember? {
        val target = raw?.trim()?.removePrefix("@")?.trim().orEmpty()
        if (target.isEmpty() || target in IGNORED_BROADCAST_TARGETS) return null
        return members.firstOrNull { it.characterId == target }
            ?: members.firstOrNull { it.name == target }
    }

    /**
     * 把 [MentionParser] 给出的成员 id 列表解析成成员，**按传入顺序去重、最多 2 个**。
     *
     * 找不到的 id 静默跳过（被 @ 者已退群 / 被关闭 → 降级为默认选人，不报错、不提示，
     * ）；`@所有人` 这类广播词不会出现在这里（解析器已单独标记）。
     */
    private fun resolveMentionTargets(
        addressedToIds: List<String>,
        members: List<GroupMember>,
    ): List<GroupMember> {
        if (addressedToIds.isEmpty()) return emptyList()
        val byId = members.associateBy { it.characterId }
        val byName = members.associateBy { it.name }
        val targets = ArrayList<GroupMember>(MentionParser.MAX_TARGETS)
        for (raw in addressedToIds) {
            val key = raw.trim().removePrefix("@").trim()
            if (key.isEmpty() || key in IGNORED_BROADCAST_TARGETS) continue
            val member = byId[key] ?: byName[key] ?: continue
            if (targets.none { it.characterId == member.characterId }) targets += member
            if (targets.size == MentionParser.MAX_TARGETS) break
        }
        return targets
    }


    // 共享历史（③ 段）


    private suspend fun loadSharedHistory(
        threadId: String,
        members: List<GroupMember>,
        userText: String,
    ): MutableList<MemMessage> {
        val names = members.associate { it.characterId to displayName(it) }
        val raw = degrade(emptyList()) { history.history(threadId) }
        val shared = ArrayList<MemMessage>(raw.size)
        raw.mapTo(shared) { it.toMem(names) }
        // 步骤 1：用户消息先落库；若端口返回的历史已含本轮消息，去掉**尾部这一条**
        // （⑤ 会再放一次）。只剔一条而不是循环剔到底：上一轮同文且整轮失败（没有 assistant 行）
        // 时，那条是合法历史，不能连坐删掉。依据 。
        val trimmedUser = userText.trim()
        if (
            shared.isNotEmpty() &&
            shared.last().role == MemMessage.ROLE_USER &&
            shared.last().content.trim() == trimmedUser
        ) {
            shared.removeAt(shared.lastIndex)
        }
        return shared
    }

    private fun GroupMessage.toMem(names: Map<String, String>): MemMessage = when (role) {
        MemMessage.ROLE_USER -> MemMessage.user(content, createdAt)
        MemMessage.ROLE_ASSISTANT -> {
            val label = speakerCharacterId?.let(names::get)
                ?: speakerName?.takeIf { it.isNotBlank() }
            MemMessage.assistant(
                if (label != null) historyLine(label, content) else content,
                createdAt,
            )
        }
        else -> MemMessage.system(content, createdAt)
    }

    /**
     * 历史/回复统一写成「名字：正文」。
     *
     * 依据 隔离原则「角色锚里写死不要替别人说话，不要复述别人的台词」——共享历史里的
     * assistant 消息必须带发言人名字，模型才分得清哪句是自己说过的（persona 串味）。
     */
    private fun historyLine(name: String, text: String): String = "${displayName(name)}：$text"


    // 单成员 prompt（①②③④⑤）


    private suspend fun assemblePrompt(
        threadId: String,
        member: GroupMember,
        roster: List<GroupMember>,
        shared: List<MemMessage>,
        userText: String,
        role: GroupTurnRole,
        primaryName: String?,
        mentioned: Boolean = false,
    ): List<MemMessage> {
        // 记忆按角色隔离（「隔离原则」）；记忆失败降级为空，不拖垮整轮
        val memoryText = degrade("") {
            memory.renderMemory(threadId, member.characterId, PromptAssembler.MEMORY_RENDER_TOKENS)
        }.trim()

        // 召回同样按角色隔离；招呼 / 短句不召回，省一次调用
        val recallText = degrade<String?>(null) {
            if (!Recall.shouldRecall(userText, turnsSinceLastRecall = 0)) {
                null
            } else {
                val query = Recall.buildQuery(shared, MemMessage.user(userText))
                if (query.isBlank()) null else memory.recall(threadId, member.characterId, query)
            }
        }.orEmpty().trim()

        // 每轮易变信息（轮到谁 / 主回还是短反应 / 字数上限 / 是否被点名）只进 ④
        // （步骤 3点名信息进了 ① 会打断前缀缓存 ≈32× 成本）
        val turnInstruction = GroupPromptBuilder.buildTurnInstruction(
            member = member,
            roster = roster,
            role = role,
            primaryName = primaryName,
            mentioned = mentioned,
        )

        return PromptAssembler.assemble(
            AssembleInput(
                // ① 只放跨轮稳定的内容，保证同一角色相邻两轮前缀字节一致（可命中 KV 缓存）
                personaLayer = GroupPromptBuilder.buildStablePersonaLayer(member, roster),
                styleSamples = member.styleSamples
                    .filter { it.isNotBlank() }
                    .map { MemMessage.assistant(it) },
                protocolEnabled = true,
                chronicle = null,
                history = shared,
                memory = null,
                // 记忆与召回是文本（端口已渲染），统一走 recall 通道进 ④，由 PromptAssembler 封顶 ≤1.5k
                recall = GroupPromptBuilder.buildEphemeral(turnInstruction, memoryText, recallText),
                userMessage = userText,
                budget = PromptAssembler.DEFAULT_CONTEXT_BUDGET,
            ),
        )
    }

    private suspend fun callReply(
        member: GroupMember,
        messages: List<MemMessage>,
        maxTokens: Int,
    ): Result<GroupReply> = try {
        Result.success(reply.reply(member, messages, maxTokens))
    } catch (cancelled: CancellationException) {
        // 用户打断 / 协程取消不是「角色失败」：必须向上传播（用户永远优先）
        throw cancelled
    } catch (exception: Exception) {
        // 只隔离普通异常；Error（OOM 等）不吞
        Result.failure(exception)
    }

    private fun failureReason(exception: Throwable?): String =
        "生成失败（${exception?.let { it::class.simpleName } ?: "Unknown"}）"

    private fun displayName(member: GroupMember): String =
        member.name.ifBlank { member.characterId }

    private fun displayName(name: String): String = name.ifBlank { "群友" }

    /** 可选数据源失败时的降级：取消要传播，其它异常按默认值继续（聊天可用性优先 的精神）。*/
    private suspend fun <T> degrade(default: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        default
    }

    companion object {
        /** 群聊最少成员数（成员选择 2–4 人）。*/
        const val MIN_MEMBERS: Int = 2

        /** 群成员硬上限（`GROUP_MAX_MEMBERS`）。*/
        const val HARD_CAP: Int = 4

        /** 同一角色多少轮内不得再当主回（`speakerCooldownTurns`）。*/
        const val SPEAKER_COOLDOWN_TURNS: Int = 2

        /** 连续多少轮没被轮到就强制主回（`starveTurns`）。 */
        const val STARVE_TURNS: Int = 6

        /** 被 @ 者的主回最多调用几次：首次 + 失败/`[PASS]` 后同角色重试 1 次。*/
        const val MENTION_ATTEMPTS: Int = 2

        /** 主回汉字数上限（`mainReplyMaxChars`）。*/
        const val MAIN_MAX_CHARS: Int = 120

        /** 短反应汉字数上限（`shortReactionMaxChars`）。*/
        const val SHORT_MAX_CHARS: Int = 20

        /** 单轮所有 AI 发言的汉字总数上限（`totalCharsPerTurn`）。*/
        const val TOTAL_MAX_CHARS: Int = 180

        /** 主回太短（<4 字）视为没说话，返回 [GroupTurnResult.Passed]。*/
        const val MIN_MAIN_CHARS: Int = 4

        /** 主回生成 token 上限（≈120 汉字）。*/
        const val MAIN_MAX_TOKENS: Int = 256

        /** 短反应生成 token 上限（≈20 汉字）。*/
        const val SHORT_MAX_TOKENS: Int = 48

        /** 选人抖动上限。*/
        const val JITTER_MAX: Double = 0.10

        /** 「多陪一句」开启时出现短反应的概率。*/
        const val SECOND_RESPONDER_PROBABILITY: Double = 0.35

        /** 打分权重：相关度（step 2）。*/
        const val WEIGHT_RELEVANCE: Double = 0.55

        /** 打分权重：冷落轮数。 */
        const val WEIGHT_RECENCY: Double = 0.25

        /** 打分权重：话多程度。 */
        const val WEIGHT_TALKATIVE: Double = 0.20

        /** 模型「本轮不说」的唯一输出。*/
        const val PASS_TOKEN: String = "[PASS]"

        private const val FAILURE_REASON_REPORTED: String = "没有生成回复"

        /** `@所有人` / `@全体` 按未点名处理，绝不广播。*/
        private val IGNORED_BROADCAST_TARGETS: Set<String> =
            setOf("所有人", "全体", "全体成员")
    }
}

/** 本轮的发言类型：主回 / 短反应。*/
internal enum class GroupTurnRole {
    MAIN,
    SHORT_REACTION,
}

/**
 * 群聊 prompt 的片段构造（内部实现；对外行为见 [GroupChatEngine]）。
 *
 * 分层原则（步骤 3 成本结论）：
 * - **① 跨轮稳定**：人设 + 在场名单 + 「你是 X」+ 不抢戏规则 + `[PASS]` 约定 → 可命中前缀缓存；
 * - **④ 每轮可变成分**：轮到谁 / 主回还是短反应 / 本轮字数上限 / 成员记忆 / 召回。
 */
internal object GroupPromptBuilder {

    /**
     * 稳定的群聊规则：在场名单、身份锚、不替别人说话、不复述不评价、`[PASS]`。
     *
     * 身份锚用「你的名字是 X，别人问起来就说 X。」而不是旧版的「你是 X」：后者在群聊里
     * 只说明"轮到谁"，模型被问"你是谁/你叫什么"时仍可能自称助手或转述别人的名字
     * （缺陷 依据 SillyTavern「角色搞不清自己是谁」的警告）。
     *
     * 同一角色跨轮字节完全一致（名单按 ordinal 固定、名字来自角色表），放在 ① 段。
     */
    fun buildStableGroupRules(member: GroupMember, roster: List<GroupMember>): String {
        val ordered = roster.sortedBy { it.ordinal }
        val rosterText = ordered.joinToString("、") { displayName(it) }
        return buildString {
            appendLine("【群聊规则】")
            appendLine(
                "这是一个多人聊天。在场的人：$rosterText。" +
                    CharacterAnchor.groupNameLine(displayName(member)),
            )
            appendLine("你只写你自己要说的话。不要写别人的台词，不要替别人说话，不要写「名字：」这种格式。")
            appendLine("不要复述别人刚说过的内容；可以自然回应或提及在场的人，但不要描写别人的动作或心理。")
            appendLine("说话风格以你的【人设】为准，一次只说一小段。")
            append("如果这次你没什么想说的，只输出：$PASS_TOKEN_TEXT")
        }
    }

    /**
     * ① 段人设层：人设正文 + 稳定群聊规则。
     *
     * 群聊规则必须始终注入（即使人设为空），否则模型会把自己当成唯一角色替别人说话。
     */
    fun buildStablePersonaLayer(member: GroupMember, roster: List<GroupMember>): String = buildString {
        val persona = member.persona.trim()
        if (persona.isNotEmpty()) {
            append(persona).append("\n\n")
        }
        append(buildStableGroupRules(member, roster))
    }

    /**
     * ④ 段的每轮易变指令（步骤 3：轮到谁必须放 ④，不能放 ①）。
     *
     * @param primaryName 短反应时的主回名字（用于「不要重复 TA 说的内容」）
     * @param mentioned 是否被用户点名。true 时换成 的点名指令：
     *   主回不能被 `[PASS]` 掉；第 2 个被 @ 者只补一句 ≤20 字。**只进 ④，绝不进 ①**
     *   （点名信息进稳定段会让同一角色相邻两轮前缀分叉 → 缓存全灭 ≈32× 成本）。
     */
    fun buildTurnInstruction(
        member: GroupMember,
        roster: List<GroupMember>,
        role: GroupTurnRole,
        primaryName: String?,
        mentioned: Boolean = false,
    ): String = when (role) {
        GroupTurnRole.MAIN ->
            if (mentioned) {
                "【这一轮】TA 刚才点了你的名字，这句话是对你说的。直接接 TA 的话，" +
                    "别问「你叫我吗」，别客套，也别提「你艾特我」这件事。" +
                    "你只写你自己的一条，最多 ${GroupChatEngine.MAIN_MAX_CHARS} 字，超过会被截断；" +
                    "这一次不能沉默，不要输出 $PASS_TOKEN_TEXT。"
            } else {
                "【这一轮】现在轮到你（${displayName(member)}）主回。只写你自己的一条，" +
                    "最多 ${GroupChatEngine.MAIN_MAX_CHARS} 字，超过会被截断；" +
                    "没话说只输出 $PASS_TOKEN_TEXT。"
            }

        GroupTurnRole.SHORT_REACTION ->
            if (mentioned) {
                "【这一轮】TA 也点了你的名字。你只补一句，最多 ${GroupChatEngine.SHORT_MAX_CHARS} 字，" +
                    "超过会被截断；不要重复、不要评价 ${primaryName ?: "前面那个人"} 刚说的内容；" +
                    "没话说才输出 $PASS_TOKEN_TEXT。"
            } else {
                "【这一轮】你（${displayName(member)}）只补一句短反应，紧跟在 ${primaryName ?: "对方"} 后面。" +
                    "只写你自己的这一句，最多 ${GroupChatEngine.SHORT_MAX_CHARS} 字，超过会被截断；" +
                    "不要重复、不要评价 ${primaryName ?: "对方"} 刚说的内容；" +
                    "没话说只输出 $PASS_TOKEN_TEXT。"
            }
    }

    /**
     * ④ 段临场资料：每轮指令 + 该角色隔离记忆 + 召回。
     *
     * 每轮指令放最前面，保证 `PromptAssembler` 的 ④ 截断永远不会先把「轮到你 / 字数上限」裁掉。
     *
     * 记忆与召回非空时加一层「只有你知道」的隔离抬头：`PromptAssembler` 会丢弃 <32 token 的
     * recall 片段（`MIN_RECALL_TOKENS`，见 PromptAssembler.kt:74），一条短边界可能只有十几
     * token，原样走 recall 通道会被静默丢掉（安全相关，不能接受）；抬头 41 token，越过阈值。
     *
     * @return 可放进 ④ 的文本；恒定非空（至少有每轮指令）
     */
    fun buildEphemeral(turnInstruction: String, memoryText: String, recallText: String): String {
        val parts = ArrayList<String>(4)
        if (turnInstruction.isNotBlank()) parts.add(turnInstruction)
        if (memoryText.isNotBlank() || recallText.isNotBlank()) {
            parts.add(EPHEMERAL_ISOLATION_HEADER)
            if (memoryText.isNotBlank()) parts.add(memoryText)
            if (recallText.isNotBlank()) parts.add(recallText)
        }
        return parts.joinToString("\n\n")
    }

    /** `[PASS]` 在 prompt 里的字面量（与 [GroupChatEngine.PASS_TOKEN] 保持一致）。 */
    private const val PASS_TOKEN_TEXT: String = GroupChatEngine.PASS_TOKEN

    /**
     * ④ 段隔离抬头（41 token，超过 `PromptAssembler.MIN_RECALL_TOKENS` 的 32）。
     *
     * 依据：PromptAssembler.kt `MIN_RECALL_TOKENS = 32`「记忆按角色隔离」。
     */
    const val EPHEMERAL_ISOLATION_HEADER: String =
        "【只有你知道的事】\n以下资料只有你记得，群里的其他成员并不知道；自然相关时再提起，" +
            "不要逐条复述，也不要替别人说出这些。"

    private fun displayName(member: GroupMember): String =
        member.name.ifBlank { member.characterId }

    private fun displayName(name: String): String = name.ifBlank { "群友" }
}

/**
 * 群聊回复的本地后处理，包括长度限制、去重和避免抢话。
 *
 * 全部零成本、纯函数；因为「prompt 里写长度约束」不可靠，必须在引擎侧再兜一层。
 */
internal object GroupPostProcess {

    /** 与最近 AI 回复的 3-gram Jaccard 超过该值整条丢弃（`dedupeJaccard`）。*/
    const val DEDUPE_JACCARD: Double = 0.5

    /** 参与去重的最近 AI 回复条数（「最近 3 条」）。*/
    const val DEDUPE_RECENT_LIMIT: Int = 3

    /** 字符 3-gram。*/
    private const val NGRAM_SIZE: Int = 3

    private val SENTENCE_ENDINGS: Set<Char> =
        setOf('。', '！', '？', '!', '?', '…', '；', ';', '\n')

    /**
     * 判断模型是否输出 `[PASS]`：去掉首尾空白后大小写不敏感地**以** [GroupChatEngine.PASS_TOKEN] 开头。
     *
     * 用前缀而不是全等：模型常写成 `[PASS]。`、`[PASS] 这次不接`
     * 或 `[PASS]\n这次没什么想说的`，全等判定会把这些漏成正文落库。按
     * `[PASS]` 只是「本轮不说」的协议标记，后面的解释文本也一律按沉默处理，不落库、不上屏。
     */
    fun isPass(text: String): Boolean =
        text.trim().startsWith(GroupChatEngine.PASS_TOKEN, ignoreCase = true)

    /**
     * 完整后处理流水线：`[PASS]` → 剪他人口播 → 按句截断 → 太短判 PASS → 3-gram 去重。
     *
     * @param maxChars 本条的汉字上限（主回 120 / 短反应 20）
     * @param minChars 少于该字数视为没说话（主回 4；短反应传 0，允许纯情绪）
     * @param foreignNames 其他角色展示名（「名字：」从这里起截断）
     * @param recent 最近 AI 回复（旧→新）
     * @return 可落库的正文；null 表示本轮不说话（[GroupTurnResult.Passed]）
     */
    fun finalize(
        text: String,
        maxChars: Int,
        minChars: Int,
        foreignNames: List<String>,
        recent: List<String>,
    ): String? {
        if (isPass(text)) return null
        val trimmed = trimForeignSpeakerLines(text, foreignNames).trim()
        if (trimmed.isEmpty()) return null
        val truncated = truncateBySentence(trimmed, maxChars)
        val length = visibleLength(truncated)
        if (length == 0) return null
        if (minChars > 0 && length < minChars) return null
        if (isTooSimilar(truncated, recent)) return null
        return truncated
    }

    /**
     * 剪掉「其他角色名：」起的全部内容（含行首与句中，借鉴 SillyTavern message trim）。
     *
     * 出现其他角色名 + 冒号即从该处截断，避免一条消息里出现两个人的台词。
     */
    fun trimForeignSpeakerLines(text: String, foreignNames: List<String>): String {
        var cut = text.length
        for (name in foreignNames) {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty()) continue
            for (marker in arrayOf("$trimmedName：", "$trimmedName:")) {
                val position = text.indexOf(marker)
                if (position in 0 until cut) cut = position
            }
        }
        return if (cut >= text.length) text else text.substring(0, cut)
    }

    /**
     * 按句末标点截断到 [maxChars] 个可见字符以内（不切半个句子 第 3 步）。
     *
     * 找不到句末标点时退化为硬截断（绝不越界）。
     */
    fun truncateBySentence(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (visibleLength(text) <= maxChars) return text
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!isWhitespace(codePoint)) {
                if (count == maxChars) break
                count++
            }
            index += codePointCharCount(codePoint)
        }
        val budgeted = text.substring(0, index)
        val lastEnding = budgeted.indexOfLast { it in SENTENCE_ENDINGS }
        return if (lastEnding >= 0) {
            budgeted.substring(0, lastEnding + 1).trimEnd()
        } else {
            budgeted.trimEnd()
        }
    }

    /** 可见字数（不计空白）；引擎的 120/20/180 都按这个口径算。 */
    fun visibleLength(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!isWhitespace(codePoint)) count++
            index += codePointCharCount(codePoint)
        }
        return count
    }

    /**
     * 与最近 AI 回复的字符 3-gram Jaccard 是否 > [DEDUPE_JACCARD]。
     *
     * 任一命中即视为「复述/水话」，整条丢弃。
     */
    fun isTooSimilar(text: String, recent: List<String>): Boolean {
        val grams = ngrams(text)
        if (grams.isEmpty()) return false
        return recent.takeLast(DEDUPE_RECENT_LIMIT).any { other ->
            val otherGrams = ngrams(other)
            if (otherGrams.isEmpty()) {
                false
            } else {
                val intersection = grams.count { it in otherGrams }
                val union = grams.size + otherGrams.size - intersection
                union > 0 && intersection.toDouble() / union > DEDUPE_JACCARD
            }
        }
    }

    private fun ngrams(text: String): Set<String> {
        val compact = buildString {
            for (character in text) {
                if (!character.isWhitespace()) append(character)
            }
        }
        if (compact.length < NGRAM_SIZE) return emptySet()
        val grams = HashSet<String>(compact.length)
        for (index in 0..compact.length - NGRAM_SIZE) {
            grams.add(compact.substring(index, index + NGRAM_SIZE))
        }
        return grams
    }

    private fun isWhitespace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint)

    private fun codePointCharCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1
}
