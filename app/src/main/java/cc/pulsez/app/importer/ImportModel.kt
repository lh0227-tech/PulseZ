package cc.pulsez.app.importer

/**
 * 导入渠道。见 。
 *
 * 渠道不改变解析逻辑（文件渠道只是多一步 [CharsetDetector.decode]），
 * 只影响个别提示文案：链接抓不到和粘贴为空说法不一样。
 */
enum class Source { PASTE, FILE, SHARE, LINK }

/**
 * 识别出的格式。见 。
 *
 * [ROLEPLAY_CHAT] 目前是占位：没有任何说话人标记、只有引号台词的纯角色扮演长文，
 * 以后可以归到这里；现在这类内容只要凑够轮次就按 [CHAT_LOG] 处理，不额外多一种状态。
 */
enum class Format { TEXT, MARKDOWN, CHAT_LOG, ST_CARD_JSON, ROLEPLAY_CHAT }

/**
 * 一条消息是谁说的。原文没有可判定标记时是 [UNKNOWN]（例如只有「」引号行），
 * 再由用户在"谁是她的"那一步确认，见 §2.5。
 */
enum class Speaker { USER, CHARACTER, UNKNOWN }

/**
 * 一条对话。
 *
 * [at] 是原文时间戳换算出的毫秒；只有时分没有日期的记录（例如 `21:03`）无法换算，留 null，
 * 界面不要硬编一个"今天"出来。
 */
data class Turn(val speaker: Speaker, val text: String, val at: Long? = null)

/**
 * 导入解析结果，也是预览页的数据源。
 *
 * 所有字段都允许为空/空列表：解析对坏输入不抛异常，只给 warning。
 * 这里产出的是**可编辑的草稿**，不是最终人设；"一次提炼"由 mem/net 层另做。
 */
data class ParsedImport(
    val name: String?,
    val persona: String?,
    val greeting: String?,
    val styleSamples: List<String>,
    val turns: List<Turn>,
    val format: Format,
    val warnings: List<String>,
)
