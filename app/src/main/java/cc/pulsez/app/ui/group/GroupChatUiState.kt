package cc.pulsez.app.ui.group

internal const val DELETED_GROUP_SPEAKER_LABEL: String = "已删除角色"

internal fun groupSpeakerDisplayName(name: String?): String =
    name?.takeIf { it.isNotBlank() } ?: DELETED_GROUP_SPEAKER_LABEL

/**
 * 群聊页的纯 UI 状态（v0.2 界面层先行，已按 定稿）。
 *
 * 这里的所有类型都定义在 ui 层，**不依赖 data/core 里尚未存在的群聊类型**
 * 由数据层根据 `thread_member`、`thread.kind` 和 `speakerCharacterId` 映射得到。
 * 好处：本页现在就能用 `GroupChatUiState.preview()` 纯内存渲染与预览，接线时只换数据来源。
 */

/** 群成员：映射自 character（+ 未来的 thread_member.ordinal 决定发言顺序）。 */
data class GroupMemberUi(
    val id: String,
    val name: String,
)

enum class GroupMessageRole { USER, MEMBER }

/**
 * 一条角色消息是主回还是短反应：
 * - [MAIN]：默认的那一条，≤120 字，正文 `bodyLarge`、无气泡；
 * - [REACTION]：可选的"多陪一句"，≤20 字，视觉更轻（`caption` + `onSurfaceVariant`，行内带名字）。
 *
 * 一条用户消息最多 1 条 [MAIN] + 1 条 [REACTION]；**默认只有 [MAIN]**。
 */
enum class GroupMessageKind { MAIN, REACTION }

/**
 * 群聊消息。
 *
 * @param speakerId 角色消息来自哪个成员；用户消息为 null。
 * @param speakerName 名字标签用；UI 层冗余一份名字，避免渲染时再查成员表。
 * @param failed 仅对主回有意义：这轮没接上。UI 只给一行可点的"重试"，**不显示任何占位气泡**。
 *   短反应失败 / 角色选择沉默（`[PASS]`）在状态里根本不存在这条消息，因此 UI 零痕迹。
 * @param addressed 这条角色回复是不是**被用户 @ 出来的**：true 时在回复上方显示
 *   一行 caption「点了 TA 的名」。只有被 @ 的那条才有；其它任何角色都**不能**带这个标记。
 * @param failureHint 仅对 [failed] == true 有意义：由错误映射透传给用户的简短失败原因（可选）。
 */
data class GroupMessageUi(
    val id: String,
    val role: GroupMessageRole,
    val text: String = "",
    val speakerId: String? = null,
    val speakerName: String? = null,
    val kind: GroupMessageKind = GroupMessageKind.MAIN,
    val failed: Boolean = false,
    val addressed: Boolean = false,
    val failureHint: String? = null,
)

/**
 * 「被叫到」标记的判定。
 *
 * 标记属于**被用户 @ 的人**，不属于"主回"这个位置：
 * - `@A` → A 的主回带标记；
 * - `@A @B` → A 的主回带标记，B 的短反应**也带**（B 同样是被点名叫到的）；
 * - 没被 @ 的人不管说没说话都不带。
 *
 * 抽成纯函数的原因：实时发送（`GroupChatViewModel.applyTurn`）与历史重建
 * （`GroupChatViewModel.toUiMessages`）必须用同一条规则，否则重启 App 后标记会丢。
 */
internal fun isAddressedReply(characterId: String, addressedIds: Set<String>): Boolean =
    characterId in addressedIds

/**
 * 群聊页状态。
 *
 * @param typingSpeakerName 当前正在写的**那一个**成员名；null 表示没有人在写。
 *   不允许多个"正在写"同时存在（多人同时正在写 = 抢戏的视觉预告）。
 * @param streaming 生成中（发送键变"停下"）。
 */
data class GroupChatUiState(
    val title: String = TITLE_DEFAULT,
    val members: List<GroupMemberUi> = emptyList(),
    val messages: List<GroupMessageUi> = emptyList(),
    /** True only after this screen entry has fetched a fresh latest-first UI window. */
    val historyLoaded: Boolean = false,
    val historySessionId: String = "",
    val hasOlderHistory: Boolean = false,
    val loadingOlderHistory: Boolean = false,
    val input: String = "",
    val typingSpeakerName: String? = null,
    val streaming: Boolean = false,
    val showAiNotice: Boolean = true,
    val error: String? = null,
) {
    companion object {
        const val TITLE_DEFAULT = "你们几个"

        /** 与 06-copy §5 的 AI 标识一致；群聊用复数"TA 们"。*/
        const val AI_NOTICE = "这是 AI 生成的。TA 们说的话不是真人说的。"

        /**
         * 纯内存假状态：只给 Compose 预览与占位 ViewModel 用。
         * 不读数据库、不发网络请求，**不要**在真实导航路径里当默认值（真实空态用 `GroupChatUiState()`）。
         *
         * 这里刻意演了三件事：连续同一人的名字标签合并、短反应更轻、主回失败只留一行重试。
         */
        fun preview(): GroupChatUiState = GroupChatUiState(
            historyLoaded = true,
            historySessionId = "preview",
            members = listOf(
                GroupMemberUi(id = "m1", name = "阿淮"),
                GroupMemberUi(id = "m2", name = "林野"),
                GroupMemberUi(id = "m3", name = "小满"),
            ),
            messages = listOf(
                GroupMessageUi(
                    id = "g1",
                    role = GroupMessageRole.USER,
                    text = "今天有点累，@阿淮 说说明天的事吧。",
                ),
                GroupMessageUi(
                    id = "g2",
                    role = GroupMessageRole.MEMBER,
                    text = "那就别想太远。明天几点起？",
                    speakerId = "m1",
                    speakerName = "阿淮",
                    addressed = true,
                ),
                GroupMessageUi(
                    id = "g3",
                    role = GroupMessageRole.MEMBER,
                    text = "我把闹钟设七点半。",
                    speakerId = "m1",
                    speakerName = "阿淮",
                ),
                GroupMessageUi(
                    id = "g4",
                    role = GroupMessageRole.MEMBER,
                    text = "我猜你会赖到十点。",
                    speakerId = "m2",
                    speakerName = "林野",
                    kind = GroupMessageKind.REACTION,
                ),
                GroupMessageUi(
                    id = "g5",
                    role = GroupMessageRole.MEMBER,
                    speakerId = "m3",
                    speakerName = "小满",
                    failed = true,
                ),
                GroupMessageUi(
                    id = "g6",
                    role = GroupMessageRole.USER,
                    text = "你们几个……",
                ),
                GroupMessageUi(
                    id = "g7",
                    role = GroupMessageRole.MEMBER,
                    text = "在。",
                    speakerId = "m1",
                    speakerName = "阿淮",
                ),
            ),
            typingSpeakerName = "林野",
            streaming = true,
        )
    }
}

/**
 * 「一起聊」的人数规则：2–4 人；超过 4 人 UI 直接禁选。
 * 放在 ui 层是因为 v0.2 先由界面兜住，数据层接线后再复用它做二次校验。
 */
object GroupRules {
    const val MIN_MEMBERS = 2
    const val MAX_MEMBERS = 4
}
