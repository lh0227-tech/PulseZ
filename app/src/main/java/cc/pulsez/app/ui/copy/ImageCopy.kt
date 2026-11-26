package cc.pulsez.app.ui.copy

import cc.pulsez.app.net.ApiError

/**
 * 「发图片（识图）」用户可见文案。
 *
 * 常量集中在此处，便于维护首次说明、错误提示、无相机提示和辅助说明的一致措辞。
 * 首次说明包含图片用途、费用、是否可选和隐私提醒；角色代词统一使用 TA。
 *
 * 收口成常量的原因同 `ReadCopy` / `CreativeModeCopy`：文案是产品定稿，纯 JVM 逐字断言 +
 * 禁用词回归（`ImageCopyTest`），改动必须显式。
 */
internal object ImageCopy {

    // ------------------------------------------------------------ 首次说明（逐字 + 隐私补充一句）

    /** 首次说明标题。 */
    const val DIALOG_TITLE = "发图片"

    /** 首次说明第 1 句：是什么 / 发给谁。 */
    const val INTRO_LINE_1 = "TA 这次能看图了。图片会发给你填的 AI 服务商。"

    /** 首次说明第 2 句：费用怎么算 / 只带哪些图。 */
    const val INTRO_LINE_2 = "每张图按大约 1000 字算，每次对话都会重新算钱；只带当前和上一轮的图。"

    /** 首次说明第 3 句：不用会怎样。 */
    const val INTRO_LINE_3 = "不想看图，就不加图；不影响打字聊天。"

    /**
     * 首次说明第 4 句：**隐私提示**。
     *
     * 隐私提醒要求用户先遮挡图片中的他人信息，并避免发送身份证或银行卡。
     */
    const val INTRO_LINE_4 = "图里有别人的名字、电话或聊天记录，先遮掉再发；身份证、银行卡别发。"

    /** 首次说明正文四行，顺序即展示顺序（测试断言恰好四行：§3.7 三段 + 隐私补充一句）。 */
    val INTRO_LINES: List<String> = listOf(INTRO_LINE_1, INTRO_LINE_2, INTRO_LINE_3, INTRO_LINE_4)

    /** 主按钮：知道了（照常发送，非阻断）。 */
    const val CONFIRM = "知道了"

    /** 次按钮：以后再说（只记录看过，本次仍照常发送，非阻断）。 */
    const val DISMISS = "以后再说"

    /** 回看说明时的唯一按钮（不改任何状态）。 */
    const val CLOSE = "知道了"

    // ------------------------------------------------------------ 入口 / 选择面板 / 门禁

    /** 输入条内部右下角「＋」的读屏描述（48dp）。 */
    const val ADD = "添加图片"

    /**
     * 模型看不了图时「＋」的读屏描述（入口置灰但仍可点，点了给说明）。
     *
     * 「模型」是 ` 禁用词（仅配置页例外），聊天页用户可见
     * 文案改说人话「AI」。
     */
    const val ADD_DISABLED = "添加图片（现在这个 AI 看不了图）"

    /** 底部面板标题。 */
    const val SOURCE_TITLE = "发图片"

    /** 拍照入口。 */
    const val SOURCE_CAMERA = "拍照"

    /** 相册入口。 */
    const val SOURCE_GALLERY = "从相册选"

    /** 面板上一句费用说明（选图前就说清，不等发出去才说钱）。 */
    const val SOURCE_DESCRIPTION = "图片会先缩小再发给你填的服务商；每张按大约 1000 字算钱。"

    /**
     * 现在这个 AI 看不了图（DeepSeek 端点 + 非 vision 模型）：入口禁用并说明。
     *
     * 原文案「这个模型看不了图。换成 deepseek-flash 才能发。」
     * 有两处违反 `（「模型」禁用词、聊天页不出现模型 id），改成说人话：
     * 说清现状（看不了图）+ 出路（去设置里换一个能看图的 AI）。
     * 依据：`「入口隐藏或禁用并说明」 （不要让用户选完图才 400）。
     */
    const val VISION_MODEL_DISABLED = "现在这个 AI 看不了图。去「设置 → 聊天」换一个能看图的 AI，再发图片。"

    /** 非 DeepSeek 端点：入口隐藏，设置里给一句说明（与「深度思考/智能搜索」同一措辞）。 */
    const val OTHER_PROVIDER = "发图片只对 DeepSeek 的地址有效。"

    /**
     * 手机没有可用的相机（启动系统相机失败，如 `ActivityNotFoundException`）。
     *
     * 依据：` （「失败态要有出路」）。用户取消（返回 false）
     * 保持静默，不弹这句；临时文件无论成败都照旧清理。
     */
    const val NO_CAMERA = "这台手机没有可用的相机。用相册选一张，或者打字？"

    // ------------------------------------------------------------ 图片消息 / 预览 / 重发

    /** 纯图消息的正文占位（落库/导出/降级都有可读正文）。*/
    const val IMAGE_PLACEHOLDER = "[图片]"

    /** 用户消息里缩略图的读屏描述。 */
    const val IMAGE_CONTENT_DESCRIPTION = "你发的图片，点开看大图"

    /** 本地文件被清/丢失时的占位。 */
    const val IMAGE_MISSING = "图片已删除"

    /** 大图预览关闭按钮。 */
    const val PREVIEW_CLOSE = "关闭"

    /** 大图预览里的「再发一次」（重发同一张本地图）。*/
    const val PREVIEW_RESEND = "再用这张图问一次"

    // ------------------------------------------------------------ 草稿

    /** 草稿项准备中。 */
    const val DRAFT_PREPARING = "正在处理……"

    /** 草稿项失败（给一条出路，不指责用户）。 */
    const val DRAFT_FAILED = "这张没处理好。换一张试试？"

    /** 删掉草稿项的读屏描述。 */
    const val DRAFT_REMOVE = "删掉这张"

    /** 动图只取第一帧的说明。*/
    const val GIF_FIRST_FRAME = "动图只发第一帧。"

    /** 一次最多 3 张。 */
    const val LIMIT_REACHED = "一次最多 3 张。"

    /** 图片还在处理中时点发送的提示。 */
    const val SEND_BLOCKED_PREPARING = "图片还在处理，等一下再发。"

    // ------------------------------------------------------------ 发送前体积上限（可触发口径）

    /**
     * 单图压缩后 >2MB。
     *
     * 聊天页用户可见文案不出现「模型」，改成「AI」。
     */
    const val SINGLE_TOO_LARGE = "这张图太大了，AI 可能收不下。换一张小一点的？"

    /** 3 图合计 >8MB。 */
    const val TOTAL_TOO_LARGE = "这几张加起来太大了。少发一张试试？"

    // ------------------------------------------------------------ 失败人话

    /**
     * 图片被服务商内容安全拦下（`content_filter` / `delta.refusal`）。
     *
     * 按 ` 只给人话 + 替代（换个方式说 / 换一张），
     * **不冒称危机干预**，也不假设用户做错了什么。
     */
    const val CONTENT_FILTER = "这张图服务商不收。换个方式说说，或者换一张？"

    /** DeepSeek 终态拒绝可能发生在正文已流出之后；聊天页撤下前缀并用这句说明结果。 */
    const val FILTERED_REPLY = "服务商拦下了这次回复。换个说法再试试。"

    /**
     * 带图 + 联网搜索时服务端 400 的降级说明（决策 「不阻断，但映射成明确人话」）。
     *
     * 真实 API 未验证（无 Key/未授权）：文案只提示"可以先把搜索关掉再试"，
     * 不声称服务端一定不允许。
     */
    const val SEARCH_CONFLICT = "带图的时候，联网搜索有时会和服务商打架。先把「智能搜索」关掉再试？"

    /** 图片读取/解码失败（通用）。 */
    const val IMPORT_FAILED = "这张图没读出来。换一张试试？"

    /** 图片太大，手机处理不了。 */
    const val IMPORT_OOM = "这张图太大，手机处理不了。换一张小一点的？"

    /** 输入超过 10MB 上限。 */
    const val IMPORT_TOO_LARGE = "图片超过 10MB 了，换一张小一点的。"

    // ------------------------------------------------------------ 设置页回看入口

    /** 首用说明回看入口标题（设置 → 连接）。 */
    const val INTRO_ENTRY_TITLE = "发图片说明"

    /** 首用说明回看入口右侧值。 */
    const val INTRO_ENTRY_VALUE = "再看一遍"

    // ------------------------------------------------------------ 映射

    /** 图片导入失败码 → 人话（[cc.pulsez.app.media.ImageErrorCodes]）。 */
    fun importFailure(errorCode: String?): String = when (errorCode) {
        cc.pulsez.app.media.ImageErrorCodes.OOM -> IMPORT_OOM
        cc.pulsez.app.media.ImageErrorCodes.TOO_LARGE -> IMPORT_TOO_LARGE
        else -> IMPORT_FAILED
    }

    /**
     * 带图发送失败时的专属人话；返回 null 表示"不是图片特有的问题"，由 `ErrorCopy` 兜底。
     *
     * @param hasImages 本轮是否带了图
     * @param webSearchOn 本轮请求是否开着联网搜索（允许同开，但失败要提示可以先关）
     */
    fun sendFailureOf(error: ApiError, hasImages: Boolean, webSearchOn: Boolean): String? {
        if (!hasImages) return null
        if (error.kind == ApiError.Kind.FILTERED) return CONTENT_FILTER
        // 只把"服务端没听懂这个请求"的 400 归到搜索冲突；上下文超长有自己的文案，不能张冠李戴。
        if (webSearchOn && error.httpCode == 400 && error.kind == ApiError.Kind.BAD_RESPONSE) {
            return SEARCH_CONFLICT
        }
        return null
    }
}
