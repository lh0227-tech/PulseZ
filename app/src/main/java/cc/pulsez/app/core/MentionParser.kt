package cc.pulsez.app.core

/**
 * 一次「@ 点名」的解析结果。
 *
 * @property addressedIds 被点到的成员 id，**按正文出现顺序**、去重、最多 [MentionParser.MAX_TARGETS] 个。
 *   `@所有人` / `@全体成员` 不广播，所以不会被放进来（见 [broadcast]）。
 * @property cleanedText 给模型看的正文。v0.3 仍返回原文：用户自己写的东西不能改写，模型也需要
 *   「TA 点了谁」这个上下文（「保留原文」）。字段保留是为了以后若 证明模型会
 *   模仿 `@名字` 时，可以只切到「只留名字、去掉 @」而不用改调用方。
 * @property unknownTokens 没匹配上任何成员、也没命中广播词的 `@token`（**不含** `@`，按出现顺序去重）。
 *   发送前不拦截，UI 只在输入框下方提示一句「没找到 @xxx，会当成普通文字」（规则 10）。
 * @property broadcast 是否出现 `@所有人` / `@全体` / `@全体成员`。按普通消息处理，不广播、不改概率
 *   。它不算 [unknownTokens]，UI 不该提示「没找到」。
 */
data class MentionResult(
    val addressedIds: List<String>,
    val cleanedText: String,
    val unknownTokens: List<String>,
    val broadcast: Boolean = false,
)

/**
 * 本地 `@点名` 解析（纯函数、无 Android 依赖，可直接 JVM 单测）。
 *
 * 规则来自 （全部是工程权衡，其中"最长匹配 + 不做词边界"标 **推断**，
 * 用 `MentionParserTest` 把行为锁死）：
 *
 * 1. **全角归一**：`＠`（U+FF20）与 `@` 等价；英文名大小写不敏感。
 * 2. **最长匹配优先**：对每个 `@` 位置，在成员名里找「是 @ 之后文本前缀」的**最长**那个。
 *    "阿淮" 与 "阿" 同时存在时，`@阿淮` 命中 "阿淮"；`@阿` 命中 "阿"。
 * 3. **不做词边界**：`@小美你觉得呢` 必须命中（中文没有空格）；标点结尾
 *    `@小美，` / `@小美。` 也命中，因为前缀匹配天然成立。
 * 4. **广播词先于成员匹配**：`@所有人` / `@全体` / `@全体成员` 置 [MentionResult.broadcast]，
 *    不产生 addressedIds（绝不全员连发）。
 * 5. **多 @ 按出现顺序，去重，最多 2 个**；第 3 个起静默忽略。
 * 6. **同名**：取 `ordinal` 最小的那个（规则 5；建群时已禁止同名，这里只是兜底）。
 * 7. **未匹配不阻断**：进 [MentionResult.unknownTokens]，正文照发。
 *
 * 解析发生在发送时（不落库、不加列），UI 高亮在渲染时用 [highlightRanges] 重算
 * （规则 9）。
 */
object MentionParser {

    /** 一次最多认几个人（`mentionMaxTargets` = 2）。*/
    const val MAX_TARGETS: Int = 2

    private const val AT: Char = '@'
    private const val FULLWIDTH_AT: Char = '＠'

    /**
     * 广播词，**必须按长度倒序**：`@全体成员` 要先于 `@全体` 命中。
     * 与 `GroupChatEngine` 的 `IGNORED_BROADCAST_TARGETS` 保持一致。
     */
    private val BROADCAST_TOKENS: List<String> = listOf("全体成员", "所有人", "全体")

    /**
     * 解析一条用户消息里的 @ 点名。
     *
     * @param text 用户输入原文（可以有全角 `＠`、标点后缀、无空格中文）
     * @param members 当前群成员；只按 [GroupMember.name] 匹配，`characterId` 原样返回
     * @return 点名结果；永不抛异常，也**永不阻断发送**
     */
    fun parse(text: String, members: List<GroupMember>): MentionResult {
        val hits = scan(text, members)
        val addressedIds = ArrayList<String>(MAX_TARGETS)
        val unknown = ArrayList<String>(2)
        var broadcast = false
        for (hit in hits) {
            when {
                hit.broadcast -> broadcast = true
                hit.member != null -> {
                    val id = hit.member.characterId
                    if (id !in addressedIds && addressedIds.size < MAX_TARGETS) addressedIds += id
                }

                else -> if (hit.token !in unknown) unknown += hit.token
            }
        }
        return MentionResult(
            addressedIds = addressedIds,
            cleanedText = text,
            unknownTokens = unknown,
            broadcast = broadcast,
        )
    }

    /**
     * UI 高亮用的命中区间（`@名字` 的 `[start, end)`，不含插入的尾空格）。
     *
     * 只返回**成员名**命中；`@所有人` 与未知 token 不高亮（前者不是具体的人，后者不是人）。
     * 输入框与用户气泡都用它在**渲染时**重算，所以改名/退群后旧消息只是不再高亮，
     * 不需要任何 schema 变更（规则 9）。
     */
    fun highlightRanges(text: String, members: List<GroupMember>): List<IntRange> {
        val ranges = ArrayList<IntRange>(2)
        for (hit in scan(text, members)) {
            if (hit.member != null) ranges += hit.start until hit.end
        }
        return ranges
    }

    // ------------------------------------------------------------------ 内部扫描

    /** 一次 `@` 命中：广播词、成员名、或未知 token。 */
    private data class Hit(
        val start: Int,
        val end: Int,
        val member: GroupMember?,
        val broadcast: Boolean,
        val token: String,
    )

    /**
     * 单遍扫描：找到每个 `@` 后先在广播词里试（最长优先），再在成员名里取最长前缀，
     * 都不中就把后面的连续文字收成 unknown token。命中后跳过整个 token，避免同一段被重复计。
     */
    private fun scan(text: String, members: List<GroupMember>): List<Hit> {
        if (text.indexOf(AT) < 0 && text.indexOf(FULLWIDTH_AT) < 0) return emptyList()
        // 全角 ＠ 与半角 @ 都是单个 UTF-16 char，替换后所有下标保持不变，UI 可直接用原文本渲染
        val normalized = text.replace(FULLWIDTH_AT, AT)
        val names = members.filter { it.name.isNotBlank() }
        val hits = ArrayList<Hit>(2)
        var index = 0
        while (index < normalized.length) {
            if (normalized[index] != AT) {
                index++
                continue
            }
            val afterAt = index + 1
            val broadcast = BROADCAST_TOKENS.firstOrNull { normalized.startsWith(it, afterAt) }
            if (broadcast != null) {
                hits += Hit(index, afterAt + broadcast.length, member = null, broadcast = true, token = broadcast)
                index = afterAt + broadcast.length
                continue
            }
            val matched = longestCandidate(normalized, afterAt, names)
            if (matched != null) {
                // 名字可能带首尾空格（不建议，但历史数据可能有）：命中长度按 trim 后的名字算
                val token = matched.name.trim()
                hits += Hit(index, afterAt + token.length, matched, broadcast = false, token = token)
                index = afterAt + token.length
                continue
            }
            // 没匹配上：收集 @ 后的连续文字（字母/数字/下划线，含中文），只用于 UI 提示
            var tokenEnd = afterAt
            while (tokenEnd < normalized.length && isTokenChar(normalized[tokenEnd])) tokenEnd++
            if (tokenEnd > afterAt) {
                hits += Hit(index, tokenEnd, member = null, broadcast = false, token = normalized.substring(afterAt, tokenEnd))
                index = tokenEnd
            } else {
                // 孤零零一个 @：按普通文本处理，不算 unknown
                index = afterAt
            }
        }
        return hits
    }

    /**
     * 从 [start] 开始的**最长**成员名前缀匹配。
     *
     * 等长同名（历史脏数据）取 `ordinal` 最小的，保证结果稳定（规则 5）。
     */
    private fun longestCandidate(text: String, start: Int, members: List<GroupMember>): GroupMember? {
        var best: GroupMember? = null
        var bestLength = -1
        for (member in members) {
            val name = member.name.trim()
            if (name.isEmpty()) continue
            if (!text.startsWith(name, start, ignoreCase = true)) continue
            val length = name.length
            if (length > bestLength || (length == bestLength && member.ordinal < (best?.ordinal ?: Int.MAX_VALUE))) {
                best = member
                bestLength = length
            }
        }
        return best
    }

    private fun isTokenChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'
}
