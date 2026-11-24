package cc.pulsez.app.importer

/**
 * Markdown + YAML frontmatter 角色卡。字段映射见 。
 *
 * 只解析平铺的 `key: value`（含 `|` / `>` 块和带引号的值），不引入 YAML 库：
 * 导入链路的依赖越少越好，而角色卡的 frontmatter 实际形状很有限。
 *
 * 正文按规范作为 persona；frontmatter 里的 `personality/description/scenario`
 * 也并进去，避免"正文有内容就把字段丢了"。
 * 没有 frontmatter 时退化为纯文本（[Format.TEXT]）。
 */
object MarkdownParser {

    /** `name:` 或 `name_zh:` 都算（只写中文名的卡在国内很常见）。 */
    private val NAME_LINE = Regex("""(?m)^[ \t]*name(?:_zh)?[ \t]*:""")
    private val FIELD = Regex("""^([A-Za-z0-9_-]+)[ \t]*:[ \t]?(.*)$""")

    /** 是否该走 Markdown 解析：`---` 开头、frontmatter 闭合、里面有 `name:`/`name_zh:`。 */
    fun detect(text: String): Boolean {
        val t = text.withoutBom().trimStart()
        if (!t.startsWith("---")) return false
        val parts = frontmatterParts(t) ?: return false
        return NAME_LINE.containsMatchIn(parts.first)
    }

    fun parse(text: String): ParsedImport {
        val clean = text.withoutBom()
        val parts = frontmatterParts(clean.trimStart()) ?: return plainText(clean)

        val fields = parseFields(parts.first)
        val body = parts.second.trim()

        // 中文名优先：产品界面是中文，name 常是英文 id。
        val name = fields["name_zh"].cleanedOrNull() ?: fields["name"].cleanedOrNull()
        val personality = fields["personality"].cleanedOrNull()
        val description = fields["description"].cleanedOrNull()
        val scenario = fields["scenario"].cleanedOrNull()
        val greeting = fields["first_mes"].cleanedOrNull() ?: fields["first_message"].cleanedOrNull()

        val persona = buildList {
            if (body.isNotEmpty()) add(body)
            personality?.let { add(it) }
            description?.let { add(it) }
            scenario?.let { add(it) }
        }.distinct()
            .joinToString("\n\n")
            .takeIf { it.isNotEmpty() }

        val mesExample = fields["mes_example"].cleanedOrNull()
            ?: fields["example_dialogue"].cleanedOrNull()
        val samples = buildList {
            addAll(StyleSamples.fromMesExample(mesExample.orEmpty()))
            addAll(StyleSamples.fromQuotedLines(persona.orEmpty()))
        }.distinct().take(StyleSamples.MAX_ITEMS)

        val warnings = mutableListOf<String>()
        if (name == null) warnings += ImportWarnings.MISSING_NAME
        if (persona == null) warnings += ImportWarnings.MISSING_PERSONA

        return ParsedImport(
            name = name,
            persona = persona,
            greeting = greeting,
            styleSamples = samples,
            turns = emptyList(),
            format = Format.MARKDOWN,
            warnings = warnings,
        )
    }

    // ---- 内部实现 ----

    /** 拆出 (frontmatter, 正文)。开头不是 `---` 或没有闭合行时返回 null。 */
    private fun frontmatterParts(text: String): Pair<String, String>? {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        var end = -1
        for (i in 1 until lines.size) {
            val l = lines[i].trim()
            if (l == "---" || l == "...") {
                end = i
                break
            }
        }
        if (end < 0) return null
        val front = lines.subList(1, end).joinToString("\n")
        val body = lines.subList(end + 1, lines.size).joinToString("\n")
        return front to body
    }

    /** 没有 frontmatter 的情况：整段当纯文本，引号句顺手当语气样本。 */
    private fun plainText(text: String): ParsedImport {
        val body = text.trim()
        val persona = body.takeIf { it.isNotEmpty() }
        return ParsedImport(
            name = null,
            persona = persona,
            greeting = null,
            styleSamples = StyleSamples.fromQuotedLines(body),
            turns = emptyList(),
            format = Format.TEXT,
            warnings = if (persona == null) listOf(ImportWarnings.EMPTY) else emptyList(),
        )
    }

    private fun parseFields(front: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        val lines = front.lines()
        var lastKey: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmedStart = line.trimStart()
            if (trimmedStart.isEmpty() || trimmedStart.startsWith("#")) {
                i += 1
                continue
            }

            // 缩进行：普通标量的续行，接到上一个 key 后面。
            if (line[0] == ' ' || line[0] == '\t') {
                val key = lastKey
                if (key != null) {
                    val previous = fields[key].orEmpty()
                    val part = trimmedStart.trim()
                    fields[key] = if (previous.isEmpty()) part else "$previous $part"
                }
                i += 1
                continue
            }

            val match = FIELD.matchEntire(line.trimEnd())
            if (match == null) {
                i += 1
                continue
            }
            val key = match.groupValues[1].lowercase()
            val rawValue = match.groupValues[2].trim()
            lastKey = key

            if (rawValue.startsWith("|") || rawValue.startsWith(">")) {
                val (block, next) = readBlock(lines, i + 1)
                fields[key] = if (rawValue.startsWith(">")) {
                    block.lines().joinToString(" ") { it.trim() }.trim()
                } else {
                    block
                }
                i = next
            } else {
                fields[key] = unquote(rawValue)
                i += 1
            }
        }
        return fields
    }

    /** 读 `|` / `>` 块：收集所有缩进行（含中间空行），去掉公共缩进。 */
    private fun readBlock(lines: List<String>, start: Int): Pair<String, Int> {
        val collected = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) {
                collected += ""
                i += 1
                continue
            }
            if (l[0] != ' ' && l[0] != '\t') break
            collected += l.replace("\t", "    ")
            i += 1
        }
        while (collected.isNotEmpty() && collected.last().isBlank()) collected.removeAt(collected.size - 1)
        val indent = collected.filter { it.isNotBlank() }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0
        val block = collected.joinToString("\n") { if (it.isBlank()) "" else it.drop(indent) }
        return block to i
    }

    /** 去掉 YAML 引号。只处理最常见的单/双引号和 `\n`、`\"`、`\\`。 */
    private fun unquote(raw: String): String {
        val s = raw.trim()
        if (s.length < 2) return s
        return when {
            s.startsWith("\"") && s.endsWith("\"") ->
                s.substring(1, s.length - 1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")

            s.startsWith("'") && s.endsWith("'") ->
                s.substring(1, s.length - 1).replace("''", "'")

            else -> s
        }
    }
}
