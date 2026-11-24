package cc.pulsez.app.ui.config

/**
 * v0.5.2「密钥跟着服务商走」相关的用户可见文案。
 *
 * 收口成常量的原因同 [ChatConfigCopy] / [EmbedConfigCopy]：纯 JVM 测试能逐字断言、批量扫
 * 禁用词（`CredentialRulesTest`），Compose 字面量没法反射收集。
 *
 * 文案口径：
 * - 说清事实 + 给出路，不出现 token / 上下文 / 窗口 / 向量 / embedding / 模型 id；
 * - 角色代词只用 TA（本组文案没有角色主语）；
 * - 两页各自指向**本页真实存在的按钮**（聊天页「清除密钥」、找旧事页「停止使用找旧事」），
 *   不能指向对方页面的按钮。
 */
internal object CredentialCopy {

    // ---------------------------------------------------------------- 保存失败 / 拒绝

    /** L：DataStore / 密文写入失败。 */
    const val SAVE_FAILED = "没存上，再试一次。"

    /** G：找旧事三格（含聊天页模型框）没填全——不写、不假成功。 */
    const val INCOMPLETE = "地址和模型要填全才能保存。"

    /**
     * B（聊天页逐字）：换服务商但密钥留空 → 不写，要求重填这一家的密钥。
     *
     * 逐字取自 ` B；出口按钮是本页的「清除密钥」（E）。
     */
    const val REQUIRE_NEW_KEY_CHAT =
        "换了服务商。密钥是发给服务商的，换了就得重填这一家的；不想用这家可以点「清除密钥」。"

    /** B（找旧事页）：同一规则、同一文案口径，出口换成本页的「停止使用找旧事」（H）。 */
    const val REQUIRE_NEW_KEY_EMBED =
        "换了服务商。密钥是发给服务商的，换了就得重填这一家的；不想用这家可以点「停止使用找旧事」。"

    /** J/M 的公共前半句：地址要能解析出服务商。 */
    const val BAD_ORIGIN = "地址要填完整（https://……）。"

    /** M（聊天页、本来就有密钥）：给出「停用这家」的出路。 */
    const val BAD_ORIGIN_EXIT_CHAT = "想停用这家，点「清除密钥」。"

    /** M（找旧事页、本来就有配置）：给出「停用这项」的出路。 */
    const val BAD_ORIGIN_EXIT_EMBED = "想停用这项，点「停止使用找旧事」。"

    /**
     * M/J 的失败文案：地址无效时给「填完整」的出路；**本来就没有密钥的用户不指向清除按钮**
     * （按钮根本不存在，指了等于骗人；`）。
     */
    fun badOrigin(forEmbed: Boolean, hasKey: Boolean): String = when {
        !hasKey -> BAD_ORIGIN
        forEmbed -> BAD_ORIGIN + BAD_ORIGIN_EXIT_EMBED
        else -> BAD_ORIGIN + BAD_ORIGIN_EXIT_CHAT
    }

    // ---------------------------------------------------------------- 密钥状态行（F）

    /** F：`keyUsable` → 「密钥已保存（只发给 <host>）」，host 只显示主机名（含非默认端口）。 */
    fun keyStatusText(status: KeyStatus): String = when (status) {
        is KeyStatus.Usable -> "密钥已保存（只发给 ${hostForDisplay(status.origin)}）"
        KeyStatus.NeedsReentry -> "密钥要重填一次"
        KeyStatus.None -> "还没有密钥"
    }

    // ---------------------------------------------------------------- 清除密钥（E）

    /** E：「清除密钥」按钮（聊天页；找旧事页用「停止使用」，不出现本按钮）。 */
    const val CLEAR_KEY_BUTTON = "清除密钥"

    /** E：清掉之后的轻提示（Toast）。 */
    const val KEY_CLEARED_TOAST = "密钥清掉了。要用再填一次。"

    /** E 失败：不静默，留在本页可重试。 */
    const val CLEAR_FAILED = "没清掉，再试一次。"

    // ---------------------------------------------------------------- 停止使用找旧事（H）

    /** H：找旧事页的次要操作按钮。 */
    const val DISCONNECT_BUTTON = "停止使用找旧事"

    /** H：二次确认框标题。 */
    const val DISCONNECT_DIALOG_TITLE = "停止使用找旧事？"

    /**
     * H：二次确认框正文——说清断开后发生什么、不断开也能用。
     *
     * 不出现「关键词」（禁用词）：用「按原话找」这一既有用户口径（[EmbedConfigCopy.OPTIONAL_NOTE]）。
     */
    const val DISCONNECT_DIALOG_TEXT =
        "停止后，找旧事不会再把聊天片段发出去。聊天照常，也能按原话找旧事，只是找得没那么准。"

    /** H：确认框主按钮（危险动作，明确写动作名）。 */
    const val DISCONNECT_CONFIRM = "停止使用"

    /** H：确认框取消按钮。 */
    const val DISCONNECT_CANCEL = "取消"

    /** H：断开成功的轻提示（Toast）。 */
    const val DISCONNECTED_TOAST = "已经断开。找旧事不会再把聊天片段发出去。"

    /** H 失败：不静默，留在本页可重试。 */
    const val DISCONNECT_FAILED = "没断开，再试一次。"

    // ---------------------------------------------------------------- 升级一次性提示（I）

    /**
     * I：升级后显示一次。说清「发生了什么 + 用户要做什么」，不说内部机制。
     *
     * 依据 ` I 逐字：「这一版把密钥改成跟着服务商走，要重填一次。」
     */
    const val UPGRADE_NOTICE = "这一版把密钥改成跟着服务商走，要重填一次。"

    /** I：「知道了」（只清提示标记，不改任何配置）。 */
    const val UPGRADE_ACK = "知道了"

    /**
     * 本包全部用户可见文案，供纯 JVM 测试批量扫禁用词 / 错代词。
     *
     * 动态文案（含 host 的状态行、按页变化的坏地址出路）用固定样例展开，保证每个分支都被扫到。
     */
    val ALL: List<String> = listOf(
        SAVE_FAILED,
        INCOMPLETE,
        REQUIRE_NEW_KEY_CHAT,
        REQUIRE_NEW_KEY_EMBED,
        BAD_ORIGIN,
        BAD_ORIGIN_EXIT_CHAT,
        BAD_ORIGIN_EXIT_EMBED,
        badOrigin(forEmbed = false, hasKey = true),
        badOrigin(forEmbed = true, hasKey = true),
        badOrigin(forEmbed = false, hasKey = false),
        keyStatusText(KeyStatus.Usable("https://api.deepseek.com")),
        keyStatusText(KeyStatus.Usable("https://api.deepseek.com:8443")),
        keyStatusText(KeyStatus.NeedsReentry),
        keyStatusText(KeyStatus.None),
        CLEAR_KEY_BUTTON,
        KEY_CLEARED_TOAST,
        CLEAR_FAILED,
        DISCONNECT_BUTTON,
        DISCONNECT_DIALOG_TITLE,
        DISCONNECT_DIALOG_TEXT,
        DISCONNECT_CONFIRM,
        DISCONNECT_CANCEL,
        DISCONNECTED_TOAST,
        DISCONNECT_FAILED,
        UPGRADE_NOTICE,
        UPGRADE_ACK,
    )
}
