package cc.pulsez.app.net

/**
 * Server-Sent Events 单行解析。纯函数、无 IO、无业务。
 *
 * 必须处理的六件事里属于本文件的部分：
 * 1. `data:` 后不一定有空格 —— `data:{...}` 和 `data: {...}` 都要认；
 * 2. keep-alive 空帧 —— 以 `:` 开头的注释行、裸 `data:` 行、只有 `event:` 的帧都要能跳过；
 * 3. `[DONE]` 不一定有 —— 解析层只把它当普通 data，是否结束由 LlmClient 判断；
 * 6. CRLF —— 行尾 `\r` 在这里剥掉，调用方直接喂按行读取的整行（不含换行符）即可。
 *
 * 注意：一张帧里 id / event / data 在协议上是多行，本函数按**行**返回，
 * 帧级拼装（event 类型作用于后续 data 行）由 LlmClient 完成。
 */
object Sse {

    /** OpenAI 流结束标记。带不带空格都要认，交给 LlmClient 用 trim 后比较。 */
    const val DONE: String = "[DONE]"

    data class SseEvent(
        val id: String?,
        val event: String?,
        val data: String,
    )

    /**
     * 解析一行 SSE。返回 null 表示这一行不需要处理（空行、注释、keep-alive、未知字段）。
     *
     * - `data:{...}` / `data: {...}` → [SseEvent.data] 为去掉一个前导空格后的内容；
     * - `event: error` → [SseEvent.event] = "error"（data 为空，由调用方决定是否当错误帧）；
     * - `id: 42` → [SseEvent.id] = "42"；
     * - `: keep-alive` / 空行 / `retry: ...` → null；
     * - `data:`（裸）→ 返回 data 为空的 event，由调用方按空帧跳过。
     */
    fun parse(line: String): SseEvent? {
        // BOM 偶尔会出现在第一行；有些调用方会带 \r\n 直接传进来，这里一起容错。
        val raw = line.removePrefix("\uFEFF").trimEnd('\r', '\n')
        if (raw.isEmpty()) return null
        if (raw.startsWith(":")) return null // 注释 / keep-alive
        val colon = raw.indexOf(':')
        if (colon < 0) return null // 没有冒号的行在 SSE 里是"字段名为整行、值为空"，这里当无意义行跳过
        val field = raw.substring(0, colon)
        var value = raw.substring(colon + 1)
        // 协议规定只去掉一个前导空格；`data:  {...}` 的第二个空格是内容，不能全 trim。
        if (value.startsWith(" ")) value = value.substring(1)
        return when (field) {
            "data" -> SseEvent(id = null, event = null, data = value)
            "event" -> SseEvent(id = null, event = value, data = "")
            "id" -> SseEvent(id = value, event = null, data = "")
            else -> null // retry / 未知字段忽略
        }
    }
}
