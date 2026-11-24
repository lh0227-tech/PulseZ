package cc.pulsez.app.core

import cc.pulsez.app.mem.MemMessage

/**
 * 群聊成员。
 *
 * 纯数据，不依赖 Room / HTTP；由数据层根据 `character` 和 `thread_member` 表映射而来。
 * 选人只在本地用这些字段打分，**不花任何模型调用**。
 *
 * @property characterId 角色 id（与 `character.id`、`message.speakerCharacterId` 同域）
 * @property name 展示名（在场名单、「你是 X」、`@名字` 匹配、后处理剪名字用）
 * @property persona 人设层正文；同时作为选人「相关度」的关键词来源（step 2）
 * @property styleSamples 可选语气样本，也参与相关度关键词匹配
 * @property ordinal 发言顺序 / 平票时的稳定次序
 * @property silentTurns 连续多少轮没当主回；达到 6 轮后下一轮强制主回。缺省为 0
 * @property chattyScore 话多程度 0–1（talkativeness），默认 0.5
 */
data class GroupMember(
    val characterId: String,
    val name: String,
    val persona: String,
    val styleSamples: List<String> = emptyList(),
    val ordinal: Int = 0,
    val silentTurns: Int = 0,
    val chattyScore: Double = 0.5,
)

/**
 * 群聊历史中的一条消息，由 [GroupHistoryPort] 返回。
 *
 * `assistant` 消息必须带 [speakerCharacterId]，否则无法区分「谁说的」；一条消息只能属于
 * 一个发言人。
 *
 * @property role [MemMessage.ROLE_USER] / [MemMessage.ROLE_ASSISTANT] / [MemMessage.ROLE_SYSTEM]
 * @property content 正文
 * @property speakerCharacterId assistant 消息的发言人角色 id；用户消息为 null
 * @property speakerName 发言人展示名（可选；成员已不在当前名单时用于兜底标注）
 * @property createdAt epochMillis，0 表示未知
 */
data class GroupMessage(
    val role: String,
    val content: String,
    val speakerCharacterId: String? = null,
    val speakerName: String? = null,
    val createdAt: Long = 0L,
) {
    /** 是不是用户说的。 */
    val isUser: Boolean get() = role == MemMessage.ROLE_USER

    companion object {
        /** 创建一条用户消息，其发言人角色 id 为空。 */
        fun user(content: String, createdAt: Long = 0L): GroupMessage =
            GroupMessage(MemMessage.ROLE_USER, content, null, null, createdAt)

        /** 创建一条由指定成员发出的消息；落库时会写入 `speakerCharacterId`。 */
        fun fromCharacter(
            characterId: String,
            name: String,
            content: String,
            createdAt: Long = 0L,
        ): GroupMessage =
            GroupMessage(MemMessage.ROLE_ASSISTANT, content, characterId, name, createdAt)
    }
}

/**
 * 一次成员生成的结果（[GroupReplyPort] 返回）。
 *
 * @property text 回复正文（可以是 `[PASS]`；失败时可为空串）
 * @property failed 是否失败；true 时引擎产出 [GroupTurnResult.Failed]，不落库
 * @property usageTokens 本次调用消耗的 token（成本记账用，0 表示未知）
 * @property errorMessage 面向用户的简短失败原因（由 [cc.pulsez.app.wire.ErrorCopy] 生成，可选）
 */
data class GroupReply(
    val text: String,
    val failed: Boolean,
    val usageTokens: Int = 0,
    val errorMessage: String? = null,
)

/**
 * 群聊共享历史的读取端口；纯逻辑层不直接访问数据库。
 *
 * 数据层按 thread 从 `message` 表读取，并原样带出 `speakerCharacterId`。
 */
interface GroupHistoryPort {
    /**
     * 读取本轮开始前的群聊历史，按时间正序。
     *
     * 契约：**允许**包含本轮刚落库的用户消息（步骤 1 先落库再组装）——
     * 引擎会丢弃末尾与本轮 `userText` 相同的 user 消息，避免与 ⑤ 段重复。
     *
     * @param threadId 群聊 thread id
     * @return 历史消息；无历史返回空列表
     */
    suspend fun history(threadId: String): List<GroupMessage>
}

/**
 * 群聊记忆端口（「隔离原则」：记忆按角色隔离）。
 *
 * 数据层复用 `MemoryRepository`，按 `characterId` 过滤记忆条目和召回 chunk。
 */
interface GroupMemoryPort {
    /**
     * 渲染**该成员自己的**记忆表。
     *
     * @param threadId 群聊 thread id
     * @param characterId 当前发言成员；不同成员必须返回不同记忆（隔离原则）
     * @param maxTokens 渲染上限，引擎默认传 [cc.pulsez.app.mem.PromptAssembler.MEMORY_RENDER_TOKENS]（900）
     * @return 已渲染的 ④ 段文本；没有记忆返回空串
     */
    suspend fun renderMemory(threadId: String, characterId: String, maxTokens: Int): String

    /**
     * 为该成员召回相关往事（格式）。
     *
     * @param query 由引擎用最近历史 + 本轮用户消息构造（[cc.pulsez.app.mem.Recall.buildQuery]）
     * @return 召回文本；没有可注入内容返回 null
     */
    suspend fun recall(threadId: String, characterId: String, query: String): String?
}

/**
 * 单个成员的生成端口（[GroupChatEngine] 串行调用，每轮 1 次主回 + 最多 1 次短反应）。
 *
 * 数据层通过 `LlmClient` 实现；失败时返回 `GroupReply(failed = true)` 或抛异常，
 * 两者都会被引擎降级成 [GroupTurnResult.Failed]，不影响其它流程）。
 */
interface GroupReplyPort {
    /**
     * 让 [character] 基于组装好的 prompt 生成一条回复。
     *
     * @param character 当前发言成员（主回或被选中的短反应者）
     * @param messages 五段组装结果（[cc.pulsez.app.mem.PromptAssembler.assemble] 输出）
     * @param maxTokens 生成长度硬限制：主回 256、短反应 48。
     *   实现方应把它作为 `ChatRequest.maxTokens` 透传；引擎仍会按汉字数二次截断兜底
     */
    suspend fun reply(character: GroupMember, messages: List<MemMessage>, maxTokens: Int): GroupReply
}
