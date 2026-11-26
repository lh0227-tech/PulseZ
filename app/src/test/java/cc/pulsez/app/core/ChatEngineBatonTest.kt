package cc.pulsez.app.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentKind
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.net.BaseUrl
import cc.pulsez.app.net.ContentPart
import cc.pulsez.app.net.LlmClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * [ChatEngine] 原生分段交接（`BUDGET` 剧情梗概 + 尾部原文衔接）与 DeepSeek 对齐接线测试。
 *
 * 覆盖：
 * 1. `buildBatonSummaryRequest` 使用 `.asInternalRequest`（DeepSeek 显式关思考、非 JSON 模式）；
 * 2. `resolveBatonSummary` 上限 1500 字与空/异常时降级回 `take(40)` 拼接；
 * 3. `maintenance` -> `batonIfDue` 真实调 `client.complete(...)` 写入封段 `summary` 与 `BUDGET`，失败时降级拼接；
 * 4. `prepare` 在 DeepSeek / 非 DeepSeek 端点对 `latestReminder`（含 `reuseLatestReminder`）、历史思考/工具调用、
 *    `AttachmentKind.FILE`（`ContentPart.File`）及 `BUDGET` 封存后尾部原文衔接的完整行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatEngineBatonTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private var previousStore: AttachmentStore? = null
    private val secrets = mutableMapOf<String, String>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = runBlocking {
        previousStore = AttachmentStore.installedOrNull()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        SettingsStore(context).update { it.clear() }
        secrets.clear()
        AttachmentStore.install(AttachmentStore(tempFolder.newFolder("files")))
        server = MockWebServer()
        server.start()
        container = AppContainer(
            context,
            secretReader = { name -> secrets[name] },
            secretRemover = { name -> secrets.remove(name) },
            secretWriter = { name, value -> secrets[name] = value },
        )
    }

    @After
    fun tearDown() {
        runCatching { container.database.close() }
        runCatching { server.shutdown() }
        val previous = previousStore
        if (previous != null) AttachmentStore.install(previous) else AttachmentStore.resetForTest()
        secrets.clear()
        context.deleteDatabase(APP_DB_NAME)
    }

    // ------------------------------------------------------------------ 1. 剧情梗概请求与降级

    @Test
    fun buildBatonSummaryRequestDisablesThinkingOnDeepSeekAndFormatsChronologicalTranscript() {
        val rows = listOf(
            msg("m1", "user", "周五一起去海边吧", 1_000L),
            msg("m2", "assistant", "好呀，我带防晒和相机", 2_000L),
        )
        val request = ChatEngine.buildBatonSummaryRequest(
            model = "deepseek-flash",
            rows = rows,
            baseUrl = "https://api.deepseek.com/v1",
        )
        assertEquals(false, request.thinking)
        assertFalse(request.jsonMode)
        assertFalse(request.stream)
        assertEquals(2, request.messages.size)
        val transcript = request.messages[1].content
        assertTrue(transcript.indexOf("你：周五一起去海边吧") < transcript.indexOf("TA：好呀，我带防晒和相机"))

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"你们约好周五去海边。"}}]}"""),
        )
        val client = LlmClient(OkHttpClient(), server.url("/v1").toString(), "test-key")
        val reply = runBlocking { client.complete(request) }
        assertEquals("你们约好周五去海边。", reply)

        val recorded = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("disabled", recorded["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("response_format" in recorded)
    }

    @Test
    fun buildBatonSummaryRequestCapsSingleOversizedMessageAtTranscriptMaxChars() {
        val oversized = "超长单条消息".repeat(6_000) // > 24,000 chars
        val request = ChatEngine.buildBatonSummaryRequest(
            model = "gpt-test",
            rows = listOf(msg("m-big", "user", oversized, 1_000L)),
            baseUrl = "https://api.example.com/v1",
        )
        val transcript = request.messages[1].content
        assertEquals(ChatEngine.BATON_TRANSCRIPT_MAX_CHARS, transcript.length)
        assertTrue(transcript.startsWith("你："))
    }

    @Test
    fun resolveBatonSummaryCapsAt1500AndFallsBackWhenBlankOrNull() {
        val longSummary = "剧情".repeat(1_000)
        val resolved = ChatEngine.resolveBatonSummary(longSummary, listOf("第 1 段：旧拼接"))
        assertEquals(ChatEngine.BATON_SUMMARY_MAX_CHARS, resolved.length)

        val fallback = listOf(
            ChatEngine.fallbackSummaryPart(
                0,
                listOf(
                    MemMessage.user("今天聊了周末计划和电影清单，还约定下周再去"),
                    MemMessage.assistant("好的，周末下午三点地铁口见"),
                ),
            ),
        )
        assertEquals(fallback.single(), ChatEngine.resolveBatonSummary(null, fallback))
        assertEquals(fallback.single(), ChatEngine.resolveBatonSummary("   ", fallback))
    }

    // ------------------------------------------------------------------ 2. maintenance -> batonIfDue 端到端

    @Test
    fun batonIfDueUsesLlmSynopsisAndContinuityTailOnSuccess() = runBlocking {
        val baseUrl = server.url("/v1").toString()
        secrets[ChatEngine.CHAT_KEY] = "test-chat-key"
        container.settings.setChatBoundEndpoint(baseUrl, "gpt-test", BaseUrl.origin(baseUrl))
        container.settings.setWindowTier(8_000)

        val (character, thread) = container.characters.createWithThread(name = "阿淮", persona = "温柔")
        val segId = requireNotNull(thread.activeSegmentId)
        container.chat.append(thread.id, segId, "user", "我周五要去杭州出差", tokens = 4_500)
        val oldReply = container.chat.append(
            thread.id,
            segId,
            "assistant",
            "那记得带把伞，杭州这几天总下雨。",
            tokens = 4_500,
        )

        // 1) extractIfDue completeJson；2) batonIfDue 分片 completeJson；3) batonIfDue 剧情梗概 complete
        val emptyPatchJson =
            """{"choices":[{"message":{"content":"{\"facts\":[],\"events\":[],\"loops\":[],\"relation\":{},\"boundaries\":[],\"prefs\":[]}"}}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPatchJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPatchJson))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"你准备周五去杭州出差，TA 提醒你带伞并关心当地阴雨天气。"}}]}""",
            ),
        )

        container.engine.maintenance(thread.id, character.id)
        assertEquals(3, server.requestCount)

        val segments = container.database.segmentDao().forThread(thread.id)
        assertEquals(2, segments.size)
        val sealed = segments.first { it.state == "SEALED" }
        val open = segments.first { it.state == "OPEN" }
        assertEquals(ChatEngine.BUDGET_REASON, sealed.batonReason)
        assertEquals(ChatEngine.BUDGET_REASON, open.batonReason)
        assertEquals("你准备周五去杭州出差，TA 提醒你带伞并关心当地阴雨天气。", sealed.summary)

        // 切段后在新 OPEN 段继续对话：② 含编年史梗概，③ 衔接上一段尾部对话原文
        val freshThread = container.database.threadDao().byId(thread.id)!!
        val prepared = container.engine.prepare(freshThread, character.id, "我已经到机场啦")
        assertTrue(prepared.messages[1].content.contains("你准备周五去杭州出差"))
        assertTrue(prepared.messages.any { it.role == "user" && it.content == "我周五要去杭州出差" })
        assertTrue(prepared.messages.any { it.role == "assistant" && it.content == "那记得带把伞，杭州这几天总下雨。" })
        assertEquals("我已经到机场啦", prepared.messages.last().content)

        val regenerated = container.engine.prepare(
            thread = freshThread,
            characterId = character.id,
            userText = "我已经到机场啦",
            additionalExcludedMessageIds = setOf(oldReply.id),
        )
        assertTrue("普通准备仍保留上一段完整历史", prepared.messages.any { it.content == "那记得带把伞，杭州这几天总下雨。" })
        assertFalse("重新生成会从上一段连续历史排除旧助手回复",
            regenerated.messages.any { it.content == "那记得带把伞，杭州这几天总下雨。" })
    }

    @Test
    fun batonIfDueFallsBackToTake40JoinWhenCompleteFails() = runBlocking {
        val baseUrl = server.url("/v1").toString()
        secrets[ChatEngine.CHAT_KEY] = "test-chat-key"
        container.settings.setChatBoundEndpoint(baseUrl, "gpt-test", BaseUrl.origin(baseUrl))
        container.settings.setWindowTier(8_000)

        val (character, thread) = container.characters.createWithThread(name = "阿淮", persona = "温柔")
        val segId = requireNotNull(thread.activeSegmentId)
        val longLine = "这是超过四十个字的一句很长很长的对话内容用来验证降级截断逻辑是否正常触发".repeat(2)
        container.chat.append(thread.id, segId, "user", longLine, tokens = 4_500)
        container.chat.append(thread.id, segId, "assistant", "我记住啦，路上小心。", tokens = 4_500)

        // 1) extractIfDue completeJson；2) batonIfDue 分片 completeJson；3) complete 返回 400（非瞬态失败，不退避直接降级）
        val emptyPatchJson =
            """{"choices":[{"message":{"content":"{\"facts\":[],\"events\":[],\"loops\":[],\"relation\":{},\"boundaries\":[],\"prefs\":[]}"}}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPatchJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPatchJson))
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"summary unavailable"}}"""),
        )

        container.engine.maintenance(thread.id, character.id)
        assertEquals(3, server.requestCount)

        val sealed = container.database.segmentDao().forThread(thread.id).first { it.state == "SEALED" }
        val summary = requireNotNull(sealed.summary)
        assertTrue("降级时应回退到第 1 段 take(40) 拼接：$summary", summary.startsWith("第 1 段："))
        assertTrue(summary.contains(longLine.take(40)))
        assertFalse(summary.contains(longLine))
    }

    // ------------------------------------------------------------------ 3. prepare：DeepSeek / 非 DeepSeek 与 FILE

    @Test
    fun prepareDeepSeekInjectsLatestReminderHistoryMetaAndFileParts() = runBlocking {
        container.settings.setChatEndpoint("https://api.deepseek.com/v1", "deepseek-chat")
        val (character, thread) = container.characters.createWithThread(name = "阿淮", persona = "温柔")
        val segId = requireNotNull(thread.activeSegmentId)
        val bindingId = "synthetic-binding-chat-engine-baton"

        val histReminder = "2026-11-24,星期二,,App,中文"
        val histUser = container.chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "user",
            text = "帮我看看这份合同",
            tokens = 12,
            latestReminder = histReminder,
        )
        val histFile = container.attachments.createFileDraft(
            store = container.attachmentStore,
            mime = "application/pdf",
            stream = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            fileName = "contract.pdf",
            ownerThreadId = thread.id,
        )
        container.attachments.markReady(histFile.id)
        container.attachments.setRemoteFile(
            histFile.id,
            "ds-file-hist",
            expiresAt = null,
            remoteCredentialBindingId = bindingId,
        )
        container.attachments.bindToMessage(listOf(histFile.id), histUser.id)

        container.chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "assistant",
            text = "我查到第三条需要注意。",
            tokens = 14,
            reasoningContent = "先检查第三条条款",
            serverToolCallsJson = """[{"id":"call-1","type":"deepseek_web_search"}]""",
        )

        val curUser = container.chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "user",
            text = "再看看补充协议",
            tokens = 10,
        )
        val curFile = container.attachments.createFileDraft(
            store = container.attachmentStore,
            mime = "text/plain",
            stream = ByteArrayInputStream("补充说明".toByteArray()),
            fileName = "addendum.txt",
            ownerThreadId = thread.id,
        )
        container.attachments.markReady(curFile.id)
        container.attachments.setRemoteFile(
            curFile.id,
            "ds-file-cur",
            expiresAt = null,
            remoteCredentialBindingId = bindingId,
        )
        container.attachments.bindToMessage(listOf(curFile.id), curUser.id)
        val oldReply = container.chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "assistant",
            text = "待替换的旧回复",
            tokens = 10,
        )

        val reused = "2026-11-25,星期三,,App,中文"
        val prepared = container.engine.prepare(
            thread = thread,
            characterId = character.id,
            userText = "再看看补充协议",
            excludeMessageId = curUser.id,
            reuseLatestReminder = reused,
            additionalExcludedMessageIds = setOf(oldReply.id),
            remoteCredentialBindingId = bindingId,
        )

        assertEquals(reused, prepared.latestReminder)
        // [① system, latest_reminder(hist), user(hist), assistant(hist), latest_reminder(cur), user(cur)]
        assertEquals(
            listOf("system", "latest_reminder", "user", "assistant", "latest_reminder", "user"),
            prepared.messages.map { it.role },
        )
        assertEquals(histReminder, prepared.messages[1].content)
        assertEquals(
            listOf(ContentPart.Text("帮我看看这份合同"), ContentPart.File("ds-file-hist", "contract.pdf")),
            prepared.messages[2].parts,
        )
        assertEquals("先检查第三条条款", prepared.messages[3].reasoningContent)
        assertEquals("""[{"id":"call-1","type":"deepseek_web_search"}]""", prepared.messages[3].serverToolCallsJson)
        assertEquals(reused, prepared.messages[4].content)
        assertEquals(
            listOf(ContentPart.Text("再看看补充协议"), ContentPart.File("ds-file-cur", "addendum.txt")),
            prepared.messages[5].parts,
        )
        assertTrue("重生成请求不包含旧助手回复", prepared.messages.none { it.content == "待替换的旧回复" })

        // 不传 reuseLatestReminder 时自动生成合法 5 段格式
        val generated = container.engine.prepare(
            thread = thread,
            characterId = character.id,
            userText = "再看看补充协议",
            excludeMessageId = curUser.id,
            remoteCredentialBindingId = bindingId,
        )
        assertNotNull(generated.latestReminder)
        assertEquals(5, generated.latestReminder!!.split(',').size)
        assertTrue("普通发送未请求排除时保留已落库历史", generated.messages.any { it.content == "待替换的旧回复" })
    }

    @Test
    fun prepareNonDeepSeekOmitsLatestReminderHistoryMetaAndFileParts() = runBlocking {
        container.settings.setChatEndpoint("https://api.example.com/v1", "gpt-4o-mini")
        val (character, thread) = container.characters.createWithThread(name = "阿淮", persona = "温柔")
        val segId = requireNotNull(thread.activeSegmentId)

        val histPureFileUser = container.chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "user",
            text = "",
            tokens = 8,
            latestReminder = "2026-11-24,星期二,,App,中文",
        )
        val histFile = container.attachments.createFileDraft(
            store = container.attachmentStore,
            mime = "application/pdf",
            stream = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            fileName = "contract.pdf",
            ownerThreadId = thread.id,
        )
        container.attachments.markReady(histFile.id)
        container.attachments.setRemoteFile(histFile.id, "ds-file-hist", expiresAt = null)
        container.attachments.bindToMessage(listOf(histFile.id), histPureFileUser.id)

        container.chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "assistant",
            text = "记得",
            tokens = 6,
            reasoningContent = "不应发给第三方",
            serverToolCallsJson = """[{"id":"call-1"}]""",
        )

        val curUser = container.chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "user",
            text = "今天呢？",
            tokens = 6,
        )
        val oldReply = container.chat.append(
            threadId = thread.id,
            segmentId = segId,
            role = "assistant",
            text = "第三方端点里的旧回复",
            tokens = 10,
        )
        val curFile = container.attachments.createFileDraft(
            store = container.attachmentStore,
            mime = "text/plain",
            stream = ByteArrayInputStream("notes".toByteArray()),
            fileName = "notes.txt",
            ownerThreadId = thread.id,
        )
        container.attachments.markReady(curFile.id)
        container.attachments.setRemoteFile(curFile.id, "ds-file-cur", expiresAt = null)
        container.attachments.bindToMessage(listOf(curFile.id), curUser.id)

        val prepared = container.engine.prepare(
            thread = thread,
            characterId = character.id,
            userText = "今天呢？",
            excludeMessageId = curUser.id,
            additionalExcludedMessageIds = setOf(oldReply.id),
        )
        assertNull(prepared.latestReminder)
        assertTrue(prepared.messages.none { it.role == "latest_reminder" })
        assertTrue(
            "非 DeepSeek 端点绝不发射 ContentPart.File",
            prepared.messages.flatMap { it.parts }.none { it is ContentPart.File },
        )
        val histUserMsg = prepared.messages.first { it.role == "user" && it.content == ChatEngine.FILE_PLACEHOLDER }
        assertTrue(histUserMsg.parts.isEmpty())
        val assistantMsg = prepared.messages.first { it.role == "assistant" }
        assertNull(assistantMsg.reasoningContent)
        assertNull(assistantMsg.serverToolCallsJson)
        assertTrue("非 DeepSeek 重生成也排除旧回复", prepared.messages.none { it.content == "第三方端点里的旧回复" })

        val ordinary = container.engine.prepare(
            thread = thread,
            characterId = character.id,
            userText = "今天呢？",
            excludeMessageId = curUser.id,
        )
        assertTrue("非 DeepSeek 普通发送仍保留完整历史", ordinary.messages.any { it.content == "第三方端点里的旧回复" })
    }

    // ------------------------------------------------------------------ 辅助

    private fun msg(id: String, role: String, content: String, at: Long): MessageEntity = MessageEntity(
        id = id,
        threadId = "t1",
        segmentId = "s1",
        role = role,
        content = content,
        tokens = content.length + 4,
        emotion = null,
        status = "OK",
        draft = null,
        usageIn = null,
        usageOut = null,
        previousContent = null,
        createdAt = at,
    )

    private companion object {
        const val APP_DB_NAME = "pulsez.db"
    }
}
