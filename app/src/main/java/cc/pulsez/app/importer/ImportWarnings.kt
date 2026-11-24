package cc.pulsez.app.importer

/**
 * 导入链路的提示文案。
 *
 * 语气照 说人话、不吓人、顺手给下一步；条目本身来自
 * 「错误与边界」。这里只给字符串，不碰 UI。
 */
object ImportWarnings {

    /** 空内容：粘贴空了、分享空了、文件是空的。 */
    const val EMPTY: String = "没读到内容。再看看是不是复制漏了？"

    /** 链接渠道读到空正文：比空粘贴多一句"复制过来更稳"。 */
    const val LINK_EMPTY: String = "这个页面读不到内容。直接把文字复制过来更稳。"

    /** 两种编码都解不干净，文本里已经出现替换字符（§2.1 最后一步）。 */
    const val GARBLED: String = "这文件像是别的编码。换成 UTF-8 或 GB18030 试试？"

    /** 识别出的对话不足 [ImportPipeline.MIN_CHAT_TURNS] 轮：放弃拆轮次，整段当人设。 */
    const val TOO_FEW_TURNS: String = "这段对话太短，直接当作角色介绍了。"

    /** 超过 5MB 字符（§7）。 */
    const val TOO_LARGE: String = "文件有点大。试试只复制最重要的部分。"

    /** 50000 字到 5MB 之间（§6）：能处理，但先说清等待和额度。 */
    const val LONG_CONTENT: String = "这段内容很长，整理可能要一两分钟，也会用掉一点额度。"

    /** 角色卡缺了最要紧的字段。 */
    const val MISSING_NAME: String = "这张卡缺了名字。替 TA 起一个？"
    const val MISSING_PERSONA: String = "这张卡没写 TA 是谁。你先补两句？"
    const val MISSING_GREETING: String = "这张卡缺了开场白。第一句话，你想说什么？"
    const val MISSING_SCENARIO: String = "这张卡缺了场景。留着空也行。"
    const val MISSING_EXAMPLES: String = "这张卡缺了示范对话。语气样本可以先空着。"

    /** 以 `{` 开头但认不出角色卡字段：当成文字，别硬塞进卡片模板。 */
    const val NOT_CARD: String = "这段看起来像角色文件，但没认出格式，先按普通文字处理了。"

    /** 有对话形状，但一个说话人都没认出来。 */
    const val NO_SPEAKER: String = "这段里没认出谁是谁。你确认一下哪边是 TA。"

    /** 人设超过 40000 字上限。*/
    const val PERSONA_TOO_LONG: String = "人设超过 40000 字了。精简一下，不然会挤掉别的内容。"
}
