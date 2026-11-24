package cc.pulsez.app.core

/**
 * 群聊轮换账本的纯函数更新（step 3、§5.6）。
 *
 * 修复的问题：v0.2 只在主回**成功**时更新 `silentTurns` / `lastSpeakers`，
 * 主回失败或 `[PASS]` 的轮次里账本不动 → 其他人的冷落计数漏加、被计划的人也不进冷却，
 * 「2 轮不连任」与「连续 6 轮没人接就强制主回」都会失真。
 * 现在**每一轮结束**（成功 / 失败 / 沉默）都由 [GroupChatViewModel] 调用本函数结算一次。
 *
 * 规则（与 [GroupChatEngine] 的选人语义对齐）：
 * - 被计划到、且引擎真的调用过的成员：`silentTurns = 0`，并进入 `lastSpeakers`（新→旧）；
 * - 其余成员 `silentTurns += 1`；
 * - 第二人只有在主回真的开口后才会被调用（串行）；没轮到就不算「被计划到」，
 *   否则会白送一次免冷落；
 * - `Rejected`（`plan == null`）整轮未开始，账本不变。
 *
 * 纯逻辑、无副作用、无随机、不碰 DB，可直接单测。
 */
object SpeakerLedger {

    /** 账本结算结果：成员 id → 新的连续冷落轮数；新的最近主回队列（新→旧）。 */
    data class Update(
        val silentTurns: Map<String, Int>,
        val lastSpeakers: List<String>,
    )

    /**
     * @param members 本轮开始时的成员（只读 [GroupMember.silentTurns]）
     * @param lastSpeakers 本轮开始时的最近主回队列（新→旧）
     * @param plan 本轮选人计划；`null` 表示整轮被拒（人数不合法），账本不动
     * @param results 本轮实际产出（决定谁真的被轮到了）
     * @param cooldownTurns `lastSpeakers` 保留的最近主回人数（`speakerCooldownTurns`）
     */
    fun update(
        members: List<GroupMember>,
        lastSpeakers: List<String>,
        plan: Plan?,
        results: List<GroupTurnResult>,
        cooldownTurns: Int = GroupChatEngine.SPEAKER_COOLDOWN_TURNS,
    ): Update {
        val silent = LinkedHashMap<String, Int>(members.size * 2)
        for (member in members) silent[member.characterId] = member.silentTurns

        if (plan == null) return Update(silent, lastSpeakers)

        // Spoken / Failed / Passed 都说明这一轮真的轮到了 TA；Rejected 不计。
        val attempted = HashSet<String>(results.size * 2)
        for (result in results) {
            when (result) {
                is GroupTurnResult.Spoken -> attempted += result.characterId
                is GroupTurnResult.Failed -> attempted += result.characterId
                is GroupTurnResult.Passed -> attempted += result.characterId
                is GroupTurnResult.Rejected -> Unit
            }
        }

        val planned = ArrayList<String>(2)
        if (plan.primary.characterId in attempted) planned += plan.primary.characterId
        val secondaryId = plan.secondary?.characterId
        if (secondaryId != null && secondaryId in attempted) planned += secondaryId
        if (planned.isEmpty()) return Update(silent, lastSpeakers)

        for (member in members) {
            val id = member.characterId
            silent[id] = if (id in planned) 0 else (silent[id] ?: 0) + 1
        }

        // planned 是执行顺序（主回在前、短反应在后），lastSpeakers 是「新→旧」，所以反转。
        val updatedSpeakers = (planned.asReversed() + lastSpeakers)
            .distinct()
            .take(cooldownTurns.coerceAtLeast(0))
        return Update(silent, updatedSpeakers)
    }
}
