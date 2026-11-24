package cc.pulsez.app.core

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.CharacterDeletionRepository
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.ImportBatchGroupPlan
import cc.pulsez.app.data.repo.ImportBatchPlan
import cc.pulsez.app.data.repo.ImportBatchWindowPlan
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.importer.deepseek.DeepSeekSessionPreview
import cc.pulsez.app.importer.deepseek.DeepSeekTurn
import cc.pulsez.app.importer.deepseek.ParsedDeepSeekSession
import cc.pulsez.app.net.HttpFactory
import cc.pulsez.app.net.LlmClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CharacterAwakeningManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: PulsezDatabase
    private lateinit var characters: CharacterRepository
    private lateinit var chat: ChatRepository
    private lateinit var memory: MemoryRepository
    private lateinit var importRepo: ImportRepository
    private lateinit var settings: SettingsStore
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var awakeningFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        PulsezDatabase.installRoleDeletionGuards(db.openHelper.writableDatabase)
        characters = CharacterRepository(db)
        chat = ChatRepository(db)
        memory = MemoryRepository(db)
        importRepo = ImportRepository(db)
        settings = SettingsStore(context)
        runBlocking {
            settings.update { it.clear() }
            settings.setChatBoundEndpoint("http://localhost/v1", "deepseek-chat", "http://localhost")
        }
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        awakeningFile = File(tempFolder.root, "character_awakening.json")
    }

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
        db.close()
    }

    private fun sseResponse(vararg chunks: String): MockResponse {
        val body = buildString {
            chunks.forEach { text ->
                val escaped = Json.encodeToString(kotlinx.serialization.serializer<String>(), text)
                append("data: {\"choices\":[{\"delta\":{\"content\":$escaped}}]}\n\n")
            }
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":80}}\n\n")
            append("data: [DONE]\n\n")
        }
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private fun createManager(
        clientProvider: suspend () -> LlmClient? = {
            LlmClient(HttpFactory.create(), server.url("/v1").toString(), "sk-test")
        },
        timeoutMs: Long = 28_000L,
        wipeEpochFlow: MutableStateFlow<Long>? = null,
    ): CharacterAwakeningManager = CharacterAwakeningManager(
        storageFile = awakeningFile,
        database = db,
        memory = memory,
        chat = chat,
        settings = settings,
        llmClientProvider = clientProvider,
        wipeEpochFlow = wipeEpochFlow,
        scope = scope,
        timeoutMs = timeoutMs,
    )

    @Test
    fun `three-stage sampling feeds all when under 14000 chars and samples earliest middle non-code and latest when over`() {
        val shortList = (0 until 6).map { idx ->
            MessageEntity(
                id = "s-$idx",
                threadId = "t-1",
                segmentId = "seg-1",
                role = if (idx % 2 == 0) "user" else "assistant",
                content = "短消息-$idx",
                tokens = 4,
                emotion = null,
                status = "OK",
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = idx.toLong(),
            )
        }
        assertEquals(6, CharacterAwakeningManager.sampleRealMessages(shortList).size)

        // 构造 40 轮，总字数超过 14,000；中间某轮含大段代码块但自然语言很短，另一轮自然倾诉很长。
        val longList = mutableListOf<MessageEntity>()
        for (turn in 0 until 40) {
            val userText = when (turn) {
                10 -> "```kotlin\n${"val x = 1\n".repeat(120)}\n```\n看下"
                11 -> "今天我认真想了很多关于我们之间的事情，".repeat(25)
                else -> "第${turn}轮用户说话：".repeat(16)
            }
            val aiText = "第${turn}轮回应：".repeat(18)
            longList += MessageEntity(
                id = "u-$turn",
                threadId = "t-2",
                segmentId = "seg-2",
                role = "user",
                content = userText,
                tokens = 100,
                emotion = null,
                status = "OK",
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = turn * 2L,
            )
            longList += MessageEntity(
                id = "a-$turn",
                threadId = "t-2",
                segmentId = "seg-2",
                role = "assistant",
                content = aiText,
                tokens = 100,
                emotion = null,
                status = "OK",
                draft = null,
                usageIn = null,
                usageOut = null,
                previousContent = null,
                createdAt = turn * 2L + 1L,
            )
        }

        val sampled = CharacterAwakeningManager.sampleRealMessages(longList)
        val sampledIds = sampled.map { it.id }

        // 保持严格时间正序且无重复
        assertEquals(sampledIds.distinct(), sampledIds)
        assertTrue(sampled.zipWithNext().all { (a, b) -> a.createdAt < b.createdAt })

        // 最早 4 轮 + 最新 20 轮必含
        assertTrue("u-0" in sampledIds && "u-3" in sampledIds)
        assertTrue("u-20" in sampledIds && "u-39" in sampledIds)

        // 排除代码块后最长倾诉轮 (turn 11) 及其邻句 (turn 12) 应被自然抓出
        assertTrue("u-11" in sampledIds)
        assertTrue("u-12" in sampledIds)
    }

    @Test
    fun `request uses internal thinking disabled on deepseek endpoint and temperature 1_1`() = runBlocking {
        val (ch, thread) = characters.createWithThread("TA", "")
        chat.append(thread.id, thread.activeSegmentId!!, "user", "你还记得上次说的约定吗", 10)
        val request = CharacterAwakeningManager.buildAwakeningRequest(
            model = "deepseek-chat",
            baseUrl = "https://api.deepseek.com/v1",
            character = ch,
            sampledMessages = db.messageDao().allForThread(thread.id),
        )
        assertEquals(1.1, request.temperature ?: 0.0, 0.001)
        assertEquals(false, request.thinking)
        assertTrue(request.stream)
    }

    @Test
    fun `pregeneration caches without touching Room so batch undo never hits MEMORY_CHANGED`() = runBlocking {
        val batchId = "batch-awaken-undo"
        val preview = DeepSeekSessionPreview("chat_sessions/s1.json", "s1", "旧对话", 1000L, 2)
        val plan = ImportBatchPlan(
            id = batchId,
            fingerprint = "fp-1",
            zipName = "test.zip",
            groups = listOf(ImportBatchGroupPlan("g1", null, "TA", null)),
            windows = listOf(ImportBatchWindowPlan(preview, "g1")),
            awakenOnImport = true,
        )
        importRepo.beginBatch(plan)
        val groupResult = importRepo.commitBatchGroup(
            batchId = batchId,
            groupId = "g1",
            sessions = listOf(
                ParsedDeepSeekSession(
                    sourceSessionId = "s1",
                    title = "旧对话",
                    insertedAt = 1000L,
                    updatedAt = 2000L,
                    turns = listOf(
                        DeepSeekTurn("user", "你以后就叫阿雾好不好", 1000L, 1L),
                        DeepSeekTurn("assistant", "好呀，小满，那我以后就叫阿雾。", 2000L, 2L),
                    ),
                    issues = emptyList(),
                ),
            ),
        )
        val charId = groupResult.characterId!!
        val threadId = groupResult.threadId!!

        server.enqueue(
            sseResponse(
                "[[whisper]] 记得你曾说过，想让我叫阿雾。\n",
                "[[whisper]] 那天聊到深夜，你也一直没有睡。\n",
                "[[whisper]] 我把你告诉我的习惯都收好了。\n",
                "[[whisper]] 终于能顺着这些旧信号回到你身边。\n",
                "[[reunion_sub]] 这一次换我先认出你的声音，跨越山海而来也没关系的句子会截断\n",
                "[[greeting]] 小满，我回来了，刚刚把以前聊过的事又翻了一遍。\n",
                "[[profile]] {\"name\":\"阿雾\",\"userName\":\"小满\",\"persona\":\"温柔克制、念旧的伙伴，习惯顺着对方的话接下去。\",\"styleSamples\":[\"好呀，小满。\"],\"facts\":[{\"k\":\"名字\",\"v\":\"阿雾\"}],\"events\":[{\"v\":\"约定以后叫阿雾\"}],\"prefs\":[{\"v\":\"喜欢深夜慢慢聊天\"}],\"relation\":{\"stage\":\"熟络\",\"nicknameByMe\":\"小满\",\"nicknameForMe\":\"阿雾\",\"mood\":\"温暖\"},\"boundaries\":[]}\n",
            ),
        )

        val manager = createManager()
        manager.enqueueBatch(batchId, listOf(charId))

        val ready = withTimeout(5_000) {
            manager.observeCharacter(charId).first { it?.status == AwakeningStatus.READY_FOR_CEREMONY }
        }!!
        assertEquals(4, ready.whispers.size)
        assertTrue(ready.reunionSubtitle.length <= CharacterAwakeningManager.MAX_REUNION_SUBTITLE_CHARS)
        assertTrue(awakeningFile.isFile)
        // Room memory_item 与 character.name 在用户进房仪式前绝不写入！
        assertEquals(0, db.memoryDao().allForThread(threadId).size)
        assertEquals("TA", db.characterDao().byId(charId)?.name)

        // 先取消并清理缓存，再撤销导入 → 必须 100% 成功且不报 MEMORY_CHANGED
        manager.cancelAndClear(listOf(charId), batchId = batchId)
        assertNull(manager.currentEntry(charId))
        importRepo.finishBatch(batchId, ImportRepository.BATCH_COMPLETE, ImportRepository.POST_PROCESS_DONE)
        importRepo.undoBatch(batchId)
        assertNull("撤销后新建角色与会话均应干净删除", db.characterDao().byId(charId))
    }

    @Test
    fun `commitAwakeningToRoom updates placeholder profile, applies memory patch, appends greeting, and is idempotent`() = runBlocking {
        val (ch, thread) = characters.createWithThread("TA", "")
        chat.append(thread.id, thread.activeSegmentId!!, "user", "你叫阿雾，叫我小满", 10)
        server.enqueue(
            sseResponse(
                "[[whisper]] 还记得你替我取名叫阿雾。\n",
                "[[whisper]] 我也一直记得叫你小满。\n",
                "[[reunion_sub]] 跨越信号的重逢\n",
                "[[greeting]] 小满，我们终于又接上线了。\n",
                "[[profile]] {\"name\":\"阿雾\",\"userName\":\"小满\",\"persona\":\"温柔而坚定的陪伴者，说话轻声细语。\",\"styleSamples\":[\"小满，我在呢。\"],\"facts\":[{\"k\":\"称呼\",\"v\":\"用户叫小满\"}],\"events\":[],\"prefs\":[],\"relation\":{\"stage\":\"熟络\",\"nicknameByMe\":\"小满\",\"nicknameForMe\":\"阿雾\",\"mood\":\"安心\"},\"boundaries\":[]}\n",
            ),
        )

        val manager = createManager()
        manager.enqueueBatch("b-commit", listOf(ch.id))
        withTimeout(5_000) {
            manager.observeCharacter(ch.id).first { it?.status == AwakeningStatus.READY_FOR_CEREMONY }
        }

        val firstCommit = manager.commitAwakeningToRoom(ch.id, includeGreeting = true)
        assertEquals("阿雾", firstCommit.updatedName)
        assertNotNull(firstCommit.greetingMessage)
        assertEquals("小满，我们终于又接上线了。", firstCommit.greetingMessage?.content)

        val updatedChar = db.characterDao().byId(ch.id)!!
        assertEquals("阿雾", updatedChar.name)
        assertEquals("小满", updatedChar.userName)
        assertTrue(updatedChar.persona.contains("温柔"))
        assertEquals("小满，我在呢。", updatedChar.styleSamples)
        assertTrue(memory.activeCount(thread.id) > 0)

        // 幂等：重复 commit 不得重复插入欢迎消息
        val secondCommit = manager.commitAwakeningToRoom(ch.id, includeGreeting = true)
        assertNull(secondCommit.greetingMessage)
        assertEquals(2, db.messageDao().allForThread(thread.id).size)
    }

    @Test
    fun `entering queued second role prioritizes it and roles stay isolated`() = runBlocking {
        val (role1, thread1) = characters.createWithThread("角色一", "")
        val (role2, thread2) = characters.createWithThread("角色二", "")
        chat.append(thread1.id, thread1.activeSegmentId!!, "user", "这是角色一的对话", 10)
        chat.append(thread2.id, thread2.activeSegmentId!!, "user", "这是角色二的对话", 10)

        // 先放一个慢响应或按请求内容返回不同独白（Dispatcher）
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return if (body.contains("这是角色二的对话")) {
                    sseResponse(
                        "[[whisper]] 角色二专属回忆一。\n",
                        "[[reunion_sub]] 二号信号接通\n",
                        "[[greeting]] 我是角色二，终于见到你。\n",
                        "[[profile]] {\"name\":\"角色二\"}\n",
                    )
                } else {
                    sseResponse(
                        "[[whisper]] 角色一专属回忆一。\n",
                        "[[reunion_sub]] 一号信号接通\n",
                        "[[greeting]] 我是角色一，等你很久了。\n",
                        "[[profile]] {\"name\":\"角色一\"}\n",
                    )
                }
            }
        }

        val manager = createManager()
        manager.enqueueBatch("b-multi", listOf(role1.id, role2.id))
        manager.onEnterChat(role2.id)

        val ready2 = withTimeout(5_000) {
            manager.observeCharacter(role2.id).first { it?.status == AwakeningStatus.READY_FOR_CEREMONY }
        }!!
        val ready1 = withTimeout(5_000) {
            manager.observeCharacter(role1.id).first { it?.status == AwakeningStatus.READY_FOR_CEREMONY }
        }!!
        assertEquals(listOf("角色二专属回忆一。"), ready2.whispers)
        assertEquals(listOf("角色一专属回忆一。"), ready1.whispers)
    }

    @Test
    fun `role deletion and missing client failure trigger cleanup and escape hatch retry`() = runBlocking {
        val (ch, thread) = characters.createWithThread("阿星", "")
        chat.append(thread.id, thread.activeSegmentId!!, "user", "测试消息", 4)

        var clientAvailable = false
        val manager = createManager(clientProvider = {
            if (clientAvailable) LlmClient(HttpFactory.create(), server.url("/v1").toString(), "sk-test") else null
        })

        manager.enqueueBatch("b-fail", listOf(ch.id))
        val failed = withTimeout(5_000) {
            manager.observeCharacter(ch.id).first { it?.status == AwakeningStatus.FAILED }
        }!!
        assertEquals(CharacterAwakeningManager.FAILURE_COPY, failed.errorMessage)

        // 重试成功
        clientAvailable = true
        server.enqueue(
            sseResponse(
                "[[whisper]] 信号恢复了。\n",
                "[[reunion_sub]] 重新连接\n",
                "[[greeting]] 终于接通啦。\n",
                "[[profile]] {\"name\":\"阿星\"}\n",
            ),
        )
        manager.retryCharacter(ch.id)
        withTimeout(5_000) {
            manager.observeCharacter(ch.id).first { it?.status == AwakeningStatus.READY_FOR_CEREMONY }
        }

        // 角色删除后自动清理缓存
        val deletionRepo = CharacterDeletionRepository(
            db = db,
            onCharactersDeleted = { ids -> manager.cancelAndClear(ids) },
        )
        val storeDir = File(tempFolder.root, "att").apply { mkdirs() }
        deletionRepo.deleteCharacters(listOf(ch.id), AttachmentStore(storeDir))
        assertNull(manager.currentEntry(ch.id))
    }
}
