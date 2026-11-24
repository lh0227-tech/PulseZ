package cc.pulsez.app.core

import cc.pulsez.app.compliance.CrisisDetector

/**
 * 群聊回合的本地危机兜底（与 1:1 的 `ChatViewModel` 同一套规则）。
 *
 * v0.2 的群聊路径完全没有接危机兜底：1:1 在 `ChatViewModel` 里用
 * [CrisisDetector.evaluateRecent] + [CrisisDetector.fallbackLine] 在落库前追加一句「找个人聊聊」，
 * 群聊的 send / retry 却没有，形成安全绕过。这里把追加逻辑抽成纯函数，
 * **在群聊主回落库与上屏之前**调用（见 `GroupChatViewModel.applyTurn` / `retry`）。
 *
 * 规则：
 * - 取最近 [RECENT_USER_LIMIT] 条用户消息（含本轮）做判定，与 1:1 的口径一致；
 * - `Level == NONE` 原样返回；
 * - 正文里已经出现「找个人 / 联系人」时不重复追加（只要求出现一次）；
 * - 追加在正文末尾，不打断对话（「不阻断对话」）。
 */
object CrisisReplyGuard {

    /** 参与判定的最近用户消息条数（与 1:1 `ChatViewModel` 一致：最近 3 条，含本轮）。 */
    const val RECENT_USER_LIMIT: Int = 3

    /** 正文里已有这些字样就视为已经说过兜底话术，不再重复。 */
    private val ALREADY_COVERED: List<String> = listOf("找个人聊聊", "联系人", "12356")

    /**
     * @param replyText 模型生成的主回正文（已 trim）
     * @param recentUserMessages 最近用户消息（旧→新，**含本轮**；允许多传，内部只取最近 3 条）
     * @param creativeMode 是否处于创作模式（默认 false）
     * @return 可落库 / 上屏的正文；未命中危机信号时与 [replyText] 完全相同
     */
    fun guard(
        replyText: String,
        recentUserMessages: List<String>,
        creativeMode: Boolean = false,
    ): String {
        val level = CrisisDetector.evaluateRecent(
            recentUserMessages.takeLast(RECENT_USER_LIMIT),
        )
        val line = CrisisDetector.fallbackLine(level, creativeMode) ?: return replyText
        if (ALREADY_COVERED.any { replyText.contains(it) }) return replyText
        if (replyText.isBlank()) return line
        return replyText.trimEnd() + "\n\n" + line
    }
}
