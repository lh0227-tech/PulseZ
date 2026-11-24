package cc.pulsez.app.importer

/**
 * 去粘在开头的 U+FEFF。
 *
 * 文件渠道的 BOM 由 [CharsetDetector] 负责；但粘贴/share 进来的字符串也可能带一个，
 * 留着会让"以 { 开头"之类的格式判别全部失手。
 */
internal fun String.withoutBom(): String = trimStart('\uFEFF')

/** 去掉首尾空白后为空，就当没有这个字段。 */
internal fun String?.cleanedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 按字符数截断，尽量不切在代理对中间。
 *
 * 用于"人设草稿不超过 40000 字"和"超长输入先截断再解析"；不负责加省略号，
 * 是否提示由调用方决定。
 */
internal fun truncateChars(text: String, maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (text.length <= maxChars) return text
    var end = maxChars
    if (text[end - 1].isHighSurrogate()) end -= 1
    return text.substring(0, end)
}
