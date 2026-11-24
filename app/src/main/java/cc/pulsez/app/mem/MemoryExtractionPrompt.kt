package cc.pulsez.app.mem

/**
 * 增量抽取 / 分片抽取的 prompt 构造（03 §6.0、§6.2、§6.3）。
 *
 * 硬约束（03 §6.3）：
 * - 只允许六个顶层字段：facts / events / loops / relation / boundaries / prefs
 * - 每条 `v` ≤ 200 字
 * - 只写用户明确说过的；推测不写；不确定的给低 conf
 * - 不要把角色自己的话写成用户事实
 * - 单层 JSON，剥掉 markdown 包裹（解析侧 [MemorySchema.parse] 也会兜底）
 */
object MemoryExtractionPrompt {

    /**
     * 抽取系统提示词（03 §6.3）。
     *
     * 返回固定字符串，包含六字段白名单、每条 ≤200 字、只写用户明说事实、
     * 单层 JSON、无 markdown 包裹等要求。
     */
    fun buildSystem(): String = buildString {
        appendLine("你是长期记忆抽取器。阅读下面用户与 AI 角色的对话原文，只抽取「用户明确说过」的长期记忆，输出一个单层 JSON 对象。")
        appendLine()
        appendLine("【顶层字段】只允许以下六个，一个都不能少、一个都不能多：")
        appendLine("- facts：用户的稳定事实（称呼、年龄、城市、职业、家人、宠物等）。元素：{\"k\":\"归一化键\",\"v\":\"内容\",\"conf\":0.0-1.0,\"valid_from\":\"YYYY-MM-DD\"}")
        appendLine("- events：用户经历或计划过的具体事件。元素：{\"t\":\"YYYY-MM-DD\",\"v\":\"内容\",\"imp\":1-5}")
        appendLine("- loops：用户提过、但还没有结果的事（未完结线索）。元素：{\"v\":\"内容\",\"due\":\"YYYY-MM-DD 或 null\"}")
        appendLine("- relation：角色与用户的关系，单个对象：{\"stage\":\"陌生|熟悉|暧昧|疏远|吵架中\",\"nickname_by_me\":\"角色叫用户什么\",\"nickname_for_me\":\"用户叫角色什么\",\"mood\":\"当前气氛\"}")
        appendLine("- boundaries：用户明确要求不要碰的话题或底线。元素：{\"v\":\"内容\"}")
        appendLine("- prefs：用户明确说过的喜好与厌恶。元素：{\"v\":\"内容\"}")
        appendLine()
        appendLine("【硬规则】")
        appendLine("1. 只写用户明说的事实。推测、常识补全、角色自己的话都不写；不要把角色说的话写成用户事实。")
        appendLine("2. conf 是你的确信度（0–1）。低于 0.6 的条目不要输出；不确定就给低 conf。")
        appendLine("3. 每条 v 不超过 200 个汉字，超了自行概括。")
        appendLine("4. 某类没有内容时输出空数组 []（relation 无法判断时输出 {}），不要省略字段名。")
        appendLine("5. 只输出单层 JSON：不要 markdown 代码块，不要解释，不要任何前后缀文本。")
        appendLine("6. 日期一律写 YYYY-MM-DD；不确定的事件日期把 t 写 null。")
        append("7. 不要创造用户没提过的 key；facts 的 k 用简短归一化名词（如「称呼」「城市」）。")
    }

    /**
     * 构造单次抽取的用户提示词（03 §6.2：每片一次非流式调用）。
     *
     * 会把消息格式化成带时间与角色的原文，并给出六字段空 JSON 骨架。
     *
     * @param shard 本次要抽取的消息分片（按时间正序）
     */
    fun buildUser(shard: List<MemMessage>): String = buildString {
        appendLine("以下是需要抽取的对话原文（user = 用户；assistant = 角色）。只抽取用户说的内容，不要抽取角色说的话。")
        appendLine()
        if (shard.isEmpty()) {
            appendLine("（本片没有消息。）")
        } else {
            for (message in shard) {
                append('[').append(timeLabel(message.createdAt)).append("] ")
                append(roleLabel(message.role)).append('：')
                appendLine(message.content.trim())
            }
        }
        appendLine()
        appendLine("请输出记忆补丁 JSON，六个字段齐全：")
        append("{\"facts\":[],\"events\":[],\"loops\":[],\"relation\":{},\"boundaries\":[],\"prefs\":[]}")
    }

    private fun roleLabel(role: String): String = when (role) {
        MemMessage.ROLE_USER -> "用户"
        MemMessage.ROLE_ASSISTANT -> "TA"
        else -> "系统"
    }

    private fun timeLabel(createdAt: Long): String =
        MemTime.formatDateTime(createdAt) ?: "时间未知"
}
