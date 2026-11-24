package cc.pulsez.app.core

/**
 * 角色身份锚：把 `character.name`（以及可选的自定义称呼）**显式**写进 prompt 的 ① 段人设层。
 *
 * 背景（v0.2.1 缺陷 用户实测）：旧实现只把 `persona` 拼进人设层。用户在创建页明明填了
 * "叫什么名字"，但只要人设正文里没写"我叫什么"，模型就不知道自己是谁（用户问"你叫什么"时
 * 会答"我是 AI 助手"或编一个名字）。名字必须由代码强制注入，不能依赖用户写进人设。
 *
 * 依据（人设层三层组成、用户可改但身份不能丢）、
 * （群聊里必须有显式「你是 X」身份锚）；
 * SillyTavern 官方文档对「多人设合并导致角色搞不清自己是谁」的警告。
 *
 * 纯函数、不碰 Android，可直接单测。
 */
object CharacterAnchor {

    /** 名字为空时的兜底展示名（绝不产出「你叫。」这种残句）。 */
    const val FALLBACK_NAME: String = "未命名"

    /** 名字归一化：去空白，空则用 [FALLBACK_NAME]。 */
    fun displayName(raw: String?): String = raw?.trim().orEmpty().ifBlank { FALLBACK_NAME }

    /**
     * 1:1 的身份行：固定两行以内的身份锚。
     *
     * - 永远包含「你叫{name}。别人问起你叫什么，就说{name}。」；
     * - `userName` 非空时追加「你称呼对方为{userName}。」（角色表里用户可自定义的称呼）。
     */
    fun singleIdentity(name: String?, userName: String?): String = buildString {
        val self = displayName(name)
        append("你叫").append(self).append("。")
        val other = userName?.trim().orEmpty()
        if (other.isNotEmpty()) {
            append('\n').append("你称呼对方为").append(other).append("。")
        }
    }

    /**
     * 1:1 的 ① 段人设层：身份锚在最前面，人设正文紧跟其后。
     *
     * @param persona 人设正文（调用方已决定用 `systemPromptOverride` 还是 `persona`）
     */
    fun singlePersonaLayer(persona: String?, name: String?, userName: String?): String = buildString {
        append(singleIdentity(name, userName))
        val body = persona?.trim().orEmpty()
        if (body.isNotEmpty()) append("\n\n").append(body)
    }

    /**
     * 群聊的身份行（群聊协议里必须有显式身份锚）。
     *
     * 旧文案只有「你是 X」，被点名/被问"你是谁"时模型仍可能转述别人或自称助手；
     * 这里改成「你的名字是 X，别人问起来就说 X。」，把"名字"这个事实说死。
     */
    fun groupNameLine(name: String?): String {
        val self = displayName(name)
        return "你的名字是 $self，别人问起来就说 $self。"
    }
}
