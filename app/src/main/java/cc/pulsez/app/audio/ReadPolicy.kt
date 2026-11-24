package cc.pulsez.app.audio

import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.data.settings.normalizeReadRate

/**
 * 「只读新完成的回复」判定（纯 Kotlin，纯 JVM 可测）。
 *
 * 依据「触发：只读新完成的 assistant 回复；流式过程中不读」、
 * §6 验收 4「只读新回复、可停、可关；切页/发消息停止」
 * （把状态机拆成纯类，ViewModel 只做接线） （v0.5 自动
 * 朗读只做 1:1） （离开聊天页/退后台/切角色之后才完成的回复不读）。
 *
 * 因为 `ChatViewModel` 只在 `LlmEvent.Finished`（流式结束、正文已定稿）时调用本判定，
 * 真实链路里的 `replyFinished` 恒为 true；把它显式做成参数，是为了让“流式中不读”这件事
 * 不是靠调用点位置保证，而是有一条可单测的规则。
 */
internal object ReadPolicy {

    /** 一次朗读请求：文本 + 语速（×100）+ 音色（空串 = 系统默认/第一个可用）。 */
    data class Plan(val text: String, val rateX100: Int, val voiceId: String)

    /**
     * 页面可见性参数。由 `ReadVisibility.Snapshot` 按「当前角色 id」换算而来，
     * 拆成两个布尔便于单测分别覆盖「退后台」与「不在本角色页/切到别的角色」。
     *
     * @param pageVisible 当前显示的 1:1 聊天页是不是这条回复的角色
     * @param appForeground App 是否在前台（退后台/锁屏 = false）
     */
    data class PageContext(
        val pageVisible: Boolean,
        val appForeground: Boolean,
    ) {
        companion object {
            /** 「就在本角色聊天页且前台」：测试里当基线用；生产由 `ReadVisibility` 现算。 */
            val VISIBLE = PageContext(pageVisible = true, appForeground = true)
        }
    }

    /**
     * 这条回复该不该自动读。
     *
     * @param autoRead 设置里「自动朗读 TA 的回复」是否开着（默认关）
     * @param isGroupChat 群聊不自动读（v0.5.0 自动朗读仅 1:1，群聊不碰）
     * @param replyText 最终回复正文（空正文不读）
     * @param replyFinished 是否已完整结束（流式过程中为 false）
     * @param page 页面可见性：不在本角色聊天页 / App 不在前台 → 不读
     */
    fun shouldRead(
        autoRead: Boolean,
        isGroupChat: Boolean,
        replyText: String,
        replyFinished: Boolean,
        page: PageContext,
    ): Boolean {
        if (!autoRead) return false
        // 依据 + 的 1:1 范围。
        if (isGroupChat) return false
        if (!replyFinished) return false
        // 依据 + 验收 4（切页停止的完整口径）：
        // 只有「本角色聊天页正显示」且「App 在前台」才允许出声；在后台/别的页面/别的角色
        // 完成的新回复不读——它们多半是用户已经离开时到达的，突然出声属隐私问题。
        if (!page.appForeground) return false
        if (!page.pageVisible) return false
        return replyText.isNotBlank()
    }

    /**
     * 组装朗读请求；不该读时返回 null（调用方直接用 `?.let { reader.speak(...) }`）。
     *
     * 语速从设置里读（80–150，即 0.8–1.5 倍）；越界值按范围收敛，
     * 避免脏数据把 `setSpeechRate` 推到不可听/无意义的倍率。
     */
    fun plan(
        settings: Settings,
        replyText: String,
        replyFinished: Boolean,
        page: PageContext,
        isGroupChat: Boolean = false,
    ): Plan? {
        if (!shouldRead(settings.autoRead, isGroupChat, replyText, replyFinished, page)) return null
        return Plan(
            text = replyText,
            rateX100 = normalizeReadRate(settings.readRateX100),
            voiceId = settings.readVoiceId,
        )
    }

    /**
     * `ChatViewModel.maybeAutoRead` 的接线入口：设置快照 + 最终正文 + 最新可见性快照 → 读不读。
     *
     * 单独抽出来的原因（修法 5 / §4）：`ChatViewModel` 没有
     * Compose UI/ViewModel 级测试栈，但「离开页面/退后台/切角色完成的新回复不读」这条**接线级**
     * 规则必须能被 JVM 单测直接断言——所以把「查可见性 + 组装判定」收成一个纯函数，
     * 删掉里面的可见性判定会让 `ReadPolicyTest` 的 用例变红。
     *
     * @param characterId 这个聊天 ViewModel 所属角色（`Route.Chat.characterId`）
     * @param visibility 读取时刻的最新可见性快照（ViewModel 会在读设置这个挂起点之后重新取，
     *   见 [ReadVisibility] 的 KDoc），保证「读设置期间用户退出页面/锁屏」也不会漏判。
     */
    fun planForFinishedReply(
        settings: Settings,
        replyText: String,
        characterId: String,
        visibility: ReadVisibility.Snapshot,
    ): Plan? = plan(
        settings = settings,
        replyText = replyText,
        replyFinished = true,
        isGroupChat = false,
        page = visibility.pageContext(characterId),
    )
}
