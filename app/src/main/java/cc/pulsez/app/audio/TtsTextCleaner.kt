package cc.pulsez.app.audio

/**
 * 朗读前的文本清洗（纯 Kotlin，无 Android 依赖，纯 JVM 可测）。
 *
 * 依据「speak(text)（剥 [[pz:…]]、去 Markdown 记号、截断 2000 字）」；
 * 「送 TTS 前剥离 [[pz:emo=...|rel=...]]，
 * 否则会被念出来」（单条上限 [android.speech.tts.TextToSpeech.getMaxSpeechInputLength]）。
 *
 * 设计口径：
 * - **协议标签**：`[[pz:emo=…|rel=…]]` 整段删掉；末尾未闭合的半截 `[[pz:…` 也删（流式渲染会截，
 *   但朗读输入不依赖调用方是否已经 parse 过）。
 * - **Markdown**：代码块整块删（念代码没有意义）、行内代码保留内容去反引号、链接保留文字去地址、
 *   图片整段删、标题/列表/引用记号去掉、粗体/斜体/删除线记号去掉。聊天回复本来不该有 Markdown
 *   （角色锚要求不用列表/加粗），这里是防御性清洗。
 * - **截断**：先按句末标点（。！？；… 换行）找不超过上限的最后一个整句；没有句末标点才退到逗号/顿号，
 *   仍没有才硬切——**不把一句话切一半**是这里的第一原则。
 * - 空文本 / 只有标签的文本清洗后为空串，调用方（[Reader]）据此不发 speak。
 */
internal object TtsTextCleaner {

    /**
     * 单条朗读的字数上限：与 `TextToSpeech.getMaxSpeechInputLength()` 取小。
     *
     * 2000 是产品口径（「截断 2000 字」）；平台常量见
     * https://developer.android.com/reference/android/speech/tts/TextToSpeech#getMaxSpeechInputLength()
     * （API 18+；`speak` 文档写明文本不得超过它）。AOSP 当前实现固定返回 4000：
     * `android.speech.tts.TextToSpeech#getMaxSpeechInputLength()` 直接 `return 4000`
     * （https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/tts/TextToSpeech.java）。
     * 不同引擎可能不同，所以运行时再和平台值取小（见 [Reader.maxChars]）。
     */
    const val MAX_CHARS = 2_000

    // ---------------------------------------------------------------- 协议标签

    /** 完整协议标签：`[[pz:emo=shy|rel=close]]`（允许出现在正文任意位置）。 */
    private val PROTOCOL_FULL = Regex("""\[\[pz:[^\]]*]]""", RegexOption.IGNORE_CASE)

    /** 末尾未闭合的半截标签：`……[[pz:emo=shy`（流式截断/异常输出都可能出现）。 */
    private val PROTOCOL_HALF = Regex("""\[\[pz:[^\]]*$""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- Markdown

    /** 围栏代码块：` ```…``` `（含语言标记），整块删除。 */
    private val FENCED_CODE = Regex("""```[\s\S]*?```""")

    /** 行内代码：`` `code` `` → 去反引号保留内容。 */
    private val INLINE_CODE = Regex("""`([^`]*)`""")

    /** 图片：`![alt](url)` 整段删除（朗读 alt 不像人话）。 */
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")

    /** 链接：`[文字](url)` → 只留文字（地址念出来是噪音）。 */
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")

    /** 标题：行首 `#` 记号去掉，保留标题文字。 */
    private val HEADING = Regex("""(?m)^\s{0,3}#{1,6}\s+""")

    /** 标题末尾的闭合 `###`。 */
    private val HEADING_CLOSE = Regex("""(?m)\s+#{1,6}\s*$""")

    /** 无序列表记号：`- ` / `* ` / `+ `。 */
    private val BULLET = Regex("""(?m)^\s{0,3}[-*+]\s+""")

    /** 有序列表记号：`1. ` / `1) ` → 保留序号（改成顿号，念起来自然）。 */
    private val ORDERED = Regex("""(?m)^\s{0,3}(\d{1,3})[.)]\s+""")

    /** 引用记号：`> `。 */
    private val QUOTE_MARKER = Regex("""(?m)^\s{0,3}>\s?""")

    /** 分隔线：`---` / `***` / `___`（整行）。 */
    private val HR = Regex("""(?m)^\s{0,3}([-*_])(?:\s*\1){2,}\s*$""")

    /** 表格分隔行：`|---|---|`（整行删除）。 */
    private val TABLE_SEPARATOR = Regex("""(?m)^\s*\|(?:\s*:?-{3,}:?\s*\|)+\s*$""")

    /** 粗体：`**文字**` / `__文字__`。 */
    private val BOLD = Regex("""(\*\*|__)(.+?)\1""")

    /** 删除线：`~~文字~~`。 */
    private val STRIKE = Regex("""~~(.+?)~~""")

    /** 斜体（星号）：`*文字*`（不误伤 `**`，也不误伤 `2*3` 这类附近无空白的算式）。 */
    private val ITALIC_STAR = Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])""")

    /** 斜体（下划线）：`_文字_`（两侧不接英文/数字/下划线，避免误伤标识符）。 */
    private val ITALIC_UNDERSCORE = Regex("""(?<![A-Za-z0-9_])_(?!\s)(.+?)(?<!\s)_(?![A-Za-z0-9_])""")

    private val WHITESPACE = Regex("""\s+""")

    /** 标点前的多余空格：`你好 ，` → `你好，`。 */
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([，。！？；：、）”』」])""")

    /** 句末标点：找到它就可以整句截断。换行也算（段落级停顿）。 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '；', ';', '…', '\n')

    /** 半句标点：只有整句放不下时才退到这里，避免从中文词的中间切断。 */
    private val CLAUSE_END = charArrayOf('，', ',', '、', '：', ':', '—', ' ')

    /**
     * 清洗朗读文本。
     *
     * @param raw 模型最终正文（可能含协议标签 / Markdown）
     * @param maxChars 单条上限（一般传 `min(2000, TextToSpeech.getMaxSpeechInputLength())`）
     * @return 可直接交给 `TextToSpeech.speak` 的文本；可能为空串（调用方不要读空串）
     */
    fun clean(raw: String, maxChars: Int = MAX_CHARS): String {
        if (raw.isBlank()) return ""
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')

        // 顺序有讲究：代码块/图片先整段删，否则内部的反引号、方括号会把后面的规则带跑。
        text = FENCED_CODE.replace(text, " ")
        text = PROTOCOL_FULL.replace(text, " ")
        text = PROTOCOL_HALF.replace(text, " ")
        text = IMAGE.replace(text, " ")
        text = LINK.replace(text, "\$1")
        text = HR.replace(text, " ")
        text = TABLE_SEPARATOR.replace(text, " ")
        text = HEADING.replace(text, "")
        text = HEADING_CLOSE.replace(text, "")
        text = BULLET.replace(text, "")
        text = ORDERED.replace(text, "\$1、")
        text = QUOTE_MARKER.replace(text, "")
        text = BOLD.replace(text, "\$2")
        text = STRIKE.replace(text, "\$1")
        text = ITALIC_STAR.replace(text, "\$1")
        text = ITALIC_UNDERSCORE.replace(text, "\$1")
        text = INLINE_CODE.replace(text, "\$1")

        // 表格竖线：留成空格比念“竖线”自然；后面统一压空白。
        text = text.replace('|', ' ')
        text = text.replace('\n', ' ')
        text = WHITESPACE.replace(text, " ")
        text = SPACE_BEFORE_PUNCT.replace(text, "\$1")
        text = text.trim()
        if (text.isEmpty()) return ""
        return truncate(text, maxChars)
    }

    /**
     * 按句截断（不截半句）。
     *
     * 1. 长度没超就原样返回；
     * 2. 在上限内找**最后一个句末标点**，截到它（含标点）——这是“整句”；
     * 3. 一句都没有（超长单句）：退到最后一个逗号/顿号/空格，至少不从词中间断；
     * 4. 连标点都没有：硬切到上限（唯一无法避免的情况）。
     */
    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        var cut = -1
        var i = maxChars - 1
        while (i >= 0) {
            if (text[i] in SENTENCE_END) {
                cut = i + 1
                break
            }
            i--
        }
        if (cut <= 0) {
            i = maxChars - 1
            while (i >= 0) {
                if (text[i] in CLAUSE_END) {
                    cut = i + 1
                    break
                }
                i--
            }
        }
        if (cut <= 0) cut = maxChars
        return text.substring(0, cut).trimEnd()
    }
}
