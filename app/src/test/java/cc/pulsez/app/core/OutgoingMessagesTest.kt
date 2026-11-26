package cc.pulsez.app.core

import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.ContentPart
import cc.pulsez.app.net.buildChatPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「组装结果 → 请求消息」的纯函数 + 真实请求体（闭合阻断 ）。
 *
 * 背景： 的 `ChatEngine.prepare` 只为带图消息生成 `ContentPart.Image`，`Payloads` 在 parts
 * 非空时又只序列化 parts、丢掉 `message.content` → **图文同发时用户写的字不会发给模型**
 * （官方形态 `content: [{"type":"text",...},{"type":"image_url",...}]`
 * 见 -106`）。
 *
 * 本测试从 `PromptAssembler.assemble` 的形态（五段：①system ②编年史? ③history... ④system ⑤user）
 * 出发，走 **生产同一套** `ChatEngine.buildOutgoingMessages`，再交给生产同一套
 * `buildChatPayload` 序列化，断言最终 JSON 里正文与图片都在、顺序正确——不是手工拼一个
 * 生产上不存在的 `ContentPart.Text` 形态（那正是 测试掩盖缺陷的地方， ①）。
 */
class OutgoingMessagesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------ 组装结果 → ChatMessage

    @Test
    fun `图文同发时正文作为首个 text 块放在图片前`() {
        // [①system] [③u1] [③a1] [④system] [⑤user]：historyStart = 5 - 2 - 2 = 1
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.user("上一句"),
            MemMessage.assistant("上一句回复"),
            MemMessage.system("临场块"),
            MemMessage.user("这张图里是什么？"),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 1,
            recentImageHistoryIndex = -1,
            currentImages = listOf(ContentPart.Image("CURRENT_IMG")),
            recentImages = emptyList(),
        )

        val last = messages.last()
        assertEquals("user", last.role)
        assertEquals("这张图里是什么？", last.content)
        assertEquals(
            "正文必须是首个 text 块，随后才是图片（官方形态）",
            listOf(ContentPart.Text("这张图里是什么？"), ContentPart.Image("CURRENT_IMG")),
            last.parts,
        )
        // 其它消息（历史/系统）不带 parts：旧行为不变。
        assertTrue(messages.dropLast(1).all { it.parts.isEmpty() })
    }

    @Test
    fun `纯图当轮只发图块不发图片占位`() {
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.system("临场块"),
            MemMessage.user(ChatEngine.IMAGE_PLACEHOLDER),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 1,
            recentImageHistoryIndex = -1,
            currentImages = listOf(ContentPart.Image("IMG_A")),
            recentImages = emptyList(),
        )

        assertEquals(listOf(ContentPart.Image("IMG_A")), messages.last().parts)
        assertTrue(
            "不能把落库占位 [图片] 当用户文字发出去",
            messages.last().parts.none { it is ContentPart.Text },
        )
    }

    @Test
    fun `最近一轮带图历史消息同样带当时的正文`() {
        // 历史：[③索引0 = 上一轮带图用户] [③索引1 = TA 回复]
        // historyStart = 6 - 2 - 2 = 2（①② 各一条）
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.system("编年史"),
            MemMessage.user("上次那张图里是什么？"),
            MemMessage.assistant("上次的回复"),
            MemMessage.system("临场块"),
            MemMessage.user("这次再问一句"),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 2,
            recentImageHistoryIndex = 0,
            currentImages = emptyList(),
            recentImages = listOf(ContentPart.Image("RECENT_IMG")),
        )

        assertEquals(
            "最近 1 轮历史的图片块要挂回那条消息，且保留当时正文",
            listOf(ContentPart.Text("上次那张图里是什么？"), ContentPart.Image("RECENT_IMG")),
            messages[2].parts,
        )
        // 当轮无图：parts 空，保持字符串行为。
        assertTrue(messages.last().parts.isEmpty())
        // 其它历史/系统不带 parts。
        assertTrue(messages.filterIndexed { index, _ -> index != 2 }.all { it.parts.isEmpty() })
    }

    @Test
    fun `最近一轮是纯图历史时只带图块`() {
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.user(ChatEngine.IMAGE_PLACEHOLDER),
            MemMessage.assistant("上一轮回复"),
            MemMessage.system("临场块"),
            MemMessage.user("嗯"),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 1,
            recentImageHistoryIndex = 0,
            currentImages = emptyList(),
            recentImages = listOf(ContentPart.Image("RECENT_IMG")),
        )

        assertEquals(listOf(ContentPart.Image("RECENT_IMG")), messages[1].parts)
    }

    @Test
    fun `更早的图片行保持字符串占位且不带 parts`() {
        // 历史索引0 = 更早的图片行（prepare 已把它换成「[图片] 原文」占位）；
        // 索引1 = 最近一轮带图用户（保留图块）；索引2 = TA 回复。
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.user("[图片] 很早以前问的"),
            MemMessage.user("最近那次"),
            MemMessage.assistant("回复"),
            MemMessage.system("临场块"),
            MemMessage.user("现在"),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 1,
            recentImageHistoryIndex = 1,
            currentImages = emptyList(),
            recentImages = listOf(ContentPart.Image("RECENT_IMG")),
        )

        assertEquals("[图片] 很早以前问的", messages[1].content)
        assertTrue("更早的图片行不读图、不发图块", messages[1].parts.isEmpty())
        assertEquals(
            listOf(ContentPart.Text("最近那次"), ContentPart.Image("RECENT_IMG")),
            messages[2].parts,
        )
    }

    @Test
    fun `无图时行为与旧版一致 parts 全空`() {
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.user("旧问题"),
            MemMessage.assistant("旧回答"),
            MemMessage.system("临场块"),
            MemMessage.user("在吗"),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 1,
            recentImageHistoryIndex = -1,
            currentImages = emptyList(),
            recentImages = emptyList(),
        )

        assertEquals(5, messages.size)
        assertTrue(messages.all { it.parts.isEmpty() })
        assertEquals(listOf("system", "user", "assistant", "system", "user"), messages.map { it.role })
        assertEquals("在吗", messages.last().content)
    }

    // ------------------------------------------------------------ assembled → ChatMessage → 请求体

    @Test
    fun `图文同发的最终请求体里 text 在前 image_url 在后`() {
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.system("临场块"),
            MemMessage.user("这张图里是什么？"),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 1,
            recentImageHistoryIndex = -1,
            currentImages = listOf(ContentPart.Image("BASE64_CURRENT")),
            recentImages = emptyList(),
        )

        val body = payloadOf(messages)
        val last = body["messages"]!!.jsonArray.last().jsonObject
        val content = last["content"] as JsonArray
        assertEquals("正文 + 图 = 两个内容块", 2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "这张图里是什么？",
            content[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,BASE64_CURRENT",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        // content 数组里不能再出现第二份正文：数组形态下 content 字段就是数组本身。
        assertTrue("parts 非空时 content 必须是数组", last["content"] is JsonArray)
    }

    @Test
    fun `纯图的最终请求体只有 image_url 没有图片占位文字`() {
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.system("临场块"),
            MemMessage.user(ChatEngine.IMAGE_PLACEHOLDER),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 1,
            recentImageHistoryIndex = -1,
            currentImages = listOf(ContentPart.Image("BASE64_ONLY")),
            recentImages = emptyList(),
        )

        val body = payloadOf(messages)
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"] as JsonArray
        assertEquals(1, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(
            "纯图消息不能把落库占位 [图片] 当文字发出去",
            body.toString().contains(ChatEngine.IMAGE_PLACEHOLDER),
        )
    }

    @Test
    fun `无图的最终请求体仍是字符串 content`() {
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.system("临场块"),
            MemMessage.user("在吗"),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            historyStart = 1,
            recentImageHistoryIndex = -1,
            currentImages = emptyList(),
            recentImages = emptyList(),
        )

        val body = payloadOf(messages)
        val last = body["messages"]!!.jsonArray.last().jsonObject
        // 旧行为：无 parts 时 content 是字符串（字节语义与 一致）。
        assertTrue(last["content"] !is JsonArray)
        assertEquals("在吗", last["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sourceId 挂载在插入 latest_reminder 后依然精准匹配图片与文件并透传思考工具`() {
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.latestReminder("2026-11-24,星期二,,App,中文"),
            MemMessage.user("看这个 PDF 和图", sourceId = "u-1"),
            MemMessage(
                role = MemMessage.ROLE_ASSISTANT,
                content = "我看完了",
                sourceId = "a-1",
                reasoningContent = "先读附件再检索",
                serverToolCallsJson = """[{"id":"tc1"}]""",
            ),
            MemMessage.latestReminder("2026-11-25,星期三,,App,中文"),
            MemMessage.user("再看今天这份文件"),
        )

        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            recentImageMessageId = "u-1",
            currentImages = emptyList(),
            recentImages = listOf(ContentPart.Image("HIST_IMG")),
            currentFiles = listOf(ContentPart.File("file-cur", "today.pdf")),
            filePartsByMessageId = mapOf(
                "u-1" to listOf(ContentPart.File("file-hist", "note.txt")),
            ),
        )

        assertEquals(6, messages.size)
        // latest_reminder 与 system 均不带 parts、不带思考字段
        assertTrue(messages[1].parts.isEmpty())
        assertEquals("latest_reminder", messages[1].role)
        assertEquals(null, messages[1].reasoningContent)

        // 历史 u-1 虽然前面插入了 latest_reminder 导致下标偏移，按 sourceId 依然精准挂载正文 + 文件 + 图片
        assertEquals(
            listOf(
                ContentPart.Text("看这个 PDF 和图"),
                ContentPart.File("file-hist", "note.txt"),
                ContentPart.Image("HIST_IMG"),
            ),
            messages[2].parts,
        )

        // 历史 assistant 透传 reasoningContent 与 serverToolCallsJson，且绝不带 parts
        assertTrue(messages[3].parts.isEmpty())
        assertEquals("先读附件再检索", messages[3].reasoningContent)
        assertEquals("""[{"id":"tc1"}]""", messages[3].serverToolCallsJson)

        // 当轮 ⑤ user 挂载 currentFiles
        assertEquals(
            listOf(
                ContentPart.Text("再看今天这份文件"),
                ContentPart.File("file-cur", "today.pdf"),
            ),
            messages.last().parts,
        )
    }

    @Test
    fun `纯文件当轮只有 File part 不产生空 Text part`() {
        val assembled = listOf(
            MemMessage.system("人设"),
            MemMessage.user(""),
        )
        val messages = ChatEngine.buildOutgoingMessages(
            assembled = assembled,
            recentImageMessageId = null,
            currentImages = emptyList(),
            recentImages = emptyList(),
            currentFiles = listOf(ContentPart.File("file-1", "report.docx")),
            filePartsByMessageId = emptyMap(),
        )
        assertEquals(listOf(ContentPart.File("file-1", "report.docx")), messages.last().parts)
    }

    // ------------------------------------------------------------ 辅助

    private fun payloadOf(messages: List<ChatMessage>): JsonObject {
        val raw = buildChatPayload(
            request = ChatRequest(
                model = "deepseek-flash",
                messages = messages,
                stream = false,
            ),
            useJsonMode = false,
            includeStreamOptions = false,
        )
        return json.parseToJsonElement(raw).jsonObject
    }
}
