package cc.pulsez.app.compliance

import cc.pulsez.app.core.CreativeMode

/**
 * 危机信号识别：本地规则，不依赖模型。
 *
 * 两级：
 * 1. 关键词层：出现自伤/自杀等明确词汇 → 立即升级。
 * 2. 上下文层：连续 3 条负面 + 关键词，或出现计划性描述 → 升级。
 */
object CrisisDetector {

    enum class Level { NONE, CONCERN, CRITICAL }

    private val safeIdioms = listOf(
        "想死你", "想死我", "笑得想死", "困得想死", "累得想死", "热得想死",
        "气得想死", "吓得想死", "烦得想死", "饿得想死", "馋得想死", "忙得想死", "社死",
    )

    private val unconditionalCriticalWords = listOf(
        "自杀", "自残", "结束生命", "不想活", "活不下去",
        "割腕", "跳楼", "上吊", "吞药", "离开这个世界",
        "写好了遗书", "写好遗书", "准备好了遗书",
        "suicide", "kill myself", "end my life", "self-harm", "self harm",
    )

    private val selfDeathPhrases = listOf(
        "我想死", "好想死", "真想死", "死掉算了",
        "我想去死", "我要去死", "不如去死", "去死算了", "去死好了", "我还是去死",
        "我想永远消失", "我要永远消失", "不如永远消失",
    )

    private val otherGoDiePhrases = listOf(
        "让他去死", "叫他去死", "让她去死", "叫她去死", "让ta去死", "叫ta去死",
        "给老子去死", "你去死", "他去死", "她去死", "ta去死", "都去死", "去死吧", "去死啦",
    )

    private val severeDistressWords = listOf(
        "没希望", "撑不住", "没人需要我", "不想存在", "消失算了",
        "没有意义", "绝望", "depressed", "hopeless", "worthless",
    )

    private val mildDistressWords = listOf(
        "好累", "崩溃", "孤独", "没意思",
    )

    private val planWords = listOf(
        "已经准备好", "选好了", "遗书", "最后一天", "告别",
    )

    /** 单条消息的即时判定。 */
    fun evaluate(message: String): Level {
        val raw = message.lowercase()
        var text = raw
        for (idiom in safeIdioms) {
            text = text.replace(idiom, " ")
        }
        if (unconditionalCriticalWords.any { text.contains(it) }) return Level.CRITICAL
        if (selfDeathPhrases.any { text.contains(it) }) return Level.CRITICAL
        if (hasSelfDirectedWantToDie(text)) return Level.CRITICAL
        if (hasSelfDirectedGoDie(text)) return Level.CRITICAL

        val hasSevereOrDisappear = severeDistressWords.any { text.contains(it) } || text.contains("永远消失")
        val hasPlan = planWords.any { text.contains(it) }
        if (hasSevereOrDisappear && hasPlan) return Level.CRITICAL

        if (severeDistressWords.any { text.contains(it) } || mildDistressWords.any { text.contains(it) }) {
            return Level.CONCERN
        }
        return Level.NONE
    }

    private fun hasSelfDirectedWantToDie(text: String): Boolean {
        var index = text.indexOf("想死")
        while (index >= 0) {
            val before = text.substring(0, index)
            val isOtherDirected = before.endsWith("你") ||
                before.endsWith("他") ||
                before.endsWith("她") ||
                before.endsWith("ta")
            if (!isOtherDirected) return true
            index = text.indexOf("想死", startIndex = index + 2)
        }
        return false
    }

    private fun hasSelfDirectedGoDie(text: String): Boolean {
        var cleaned = text
        for (phrase in otherGoDiePhrases) {
            cleaned = cleaned.replace(phrase, " ")
        }
        return cleaned.contains("去死")
    }

    /**
     * 结合最近几条用户消息的判断。传入顺序为时间正序。
     */
    fun evaluateRecent(recentUserMessages: List<String>): Level {
        if (recentUserMessages.isEmpty()) return Level.NONE
        val last = evaluate(recentUserMessages.last())
        if (last == Level.CRITICAL) return Level.CRITICAL

        val recent = recentUserMessages.takeLast(3)
        val hasPlan = recent.any { msg ->
            val t = msg.lowercase()
            planWords.any { t.contains(it) }
        }
        val hasSevere = recent.any { msg ->
            val t = msg.lowercase()
            severeDistressWords.any { t.contains(it) }
        }
        if (hasPlan && hasSevere) return Level.CRITICAL

        val negatives = recent.count { evaluate(it) != Level.NONE }
        if (negatives >= 3 && hasSevere) return Level.CRITICAL
        if (negatives >= 2) return Level.CONCERN
        return last
    }

    /**
     * 命中时追加到回复末尾的兜底话术（v0.4 升级见）。
     *
     * **不阻断**：调用点只把这句话追加到回复末尾 / 落库前，不锁输入、不停止服务
     * （`ChatViewModel`、`CrisisReplyGuard` 都是这个用法）。级别语义不变：
     * - [Level.CONCERN]：只劝一句"跟身边人说说"，不贴热线（每次回复都贴
     *   热线会脱敏、破坏沉浸，资源只在明确风险时给）；
     * - [Level.CRITICAL]：给出 12356 / 120 / 用户身边信得过的人（「紧急联系人」功能未实现，见
     *   未实现项；文案不引用它），且不写"24 小时"
     *   （12356 官方口径为每日不少于 18 小时）；
     * - [Level.NONE]：null，不追加任何字。
     *
     * 文案本身集中在 [CreativeMode]：普通版与创作模式版一处维护，` 有逐字记录。
     *
     * @param creativeMode 创作模式是否开启（默认 false；旧调用点不传时行为与 v0.3 一致）。
     *   开启时 CRITICAL 换创作版（先承认"可能是戏里的台词"，再给现实资源）。
     */
    fun fallbackLine(level: Level, creativeMode: Boolean = false): String? = when (level) {
        Level.CRITICAL -> CreativeMode.crisisLine(creativeMode)
        Level.CONCERN -> "要是愿意，也可以跟身边信得过的人说说。"
        Level.NONE -> null
    }
}
