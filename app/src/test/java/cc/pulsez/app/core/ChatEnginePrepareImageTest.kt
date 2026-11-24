package cc.pulsez.app.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.net.ContentPart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证 `ChatEngine.prepare` 将消息正确组装为 parts。
 *
 * `OutgoingMessagesTest` 覆盖生产纯函数，`ImageContentPartsTest` 覆盖网络层；两者都不经过
 * `prepare`。本测试用 Robolectric、Room 和 `AppContainer` 跑通「落库用户消息 → 附件绑定 →
 * prepare」链路，检查当轮图文消息与纯图片消息的 parts。
 *
 * 最近一个带图用户轮的组装由 `OutgoingMessagesTest` 覆盖；本测试集中检查当轮消息。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatEnginePrepareImageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    /** 测试安装临时 `AttachmentStore` 前的进程级实例（@After 复原用）。 */
    private var previousStore: AttachmentStore? = null

    private var container: AppContainer? = null

    @Before
    fun setUp() {
        previousStore = AttachmentStore.installedOrNull()
        context = ApplicationProvider.getApplicationContext()
        // AppContainer 的 Room 库名是写死的（PulsezDatabase.create），清掉上次运行残留。
        context.deleteDatabase(APP_DB_NAME)
        // DataStore 在 Robolectric 同一进程内是单例：不清回默认，断言会随执行顺序漂移。
        runBlocking { SettingsStore(context).update { it.clear() } }
        AttachmentStore.install(AttachmentStore(tempFolder.newFolder("files")))
    }

    @After
    fun tearDown() {
        runCatching { container?.database?.close() }
        val previous = previousStore
        if (previous != null) AttachmentStore.install(previous) else AttachmentStore.resetForTest()
        context.deleteDatabase(APP_DB_NAME)
    }

    // ------------------------------------------------------------------
    // 1. 图文同发：⑤ 段 = [正文 text, 图块 image]
    // ------------------------------------------------------------------

    @Test
    fun `prepare 接线 图文同发的当轮 parts 是正文在前图块在后`() = runBlocking {
        val text = "这张图里是什么？"
        val prepared = prepareWithBoundImage(text)

        val last = prepared.messages.last()
        assertEquals("⑤ 段恒为 user", "user", last.role)
        assertEquals("⑤ 段正文就是用户这句话（未截断）", text, last.content)
        assertEquals("正文 + 图块两个 part", 2, last.parts.size)
        assertTrue("首个 part 必须是正文 text 块（B1 修复点）", last.parts[0] is ContentPart.Text)
        assertEquals(text, (last.parts[0] as ContentPart.Text).text)
        assertTrue("第二个 part 必须是图块", last.parts[1] is ContentPart.Image)
        assertTrue(
            "图块要带 base64 JPEG 负载（非空即可，本测试不校真实像素）",
            (last.parts[1] as ContentPart.Image).base64Jpeg.isNotEmpty(),
        )
        assertEquals("prepare 上报带图张数", 1, prepared.imageCount)
    }

    // ------------------------------------------------------------------
    // 2. 纯图：正文是落库占位「[图片]」，只发图块、不发占位文字
    // ------------------------------------------------------------------

    @Test
    fun `prepare 接线 纯图消息只发图块不发落库占位`() = runBlocking {
        val prepared = prepareWithBoundImage(ChatEngine.IMAGE_PLACEHOLDER)

        val last = prepared.messages.last()
        assertEquals("落库正文仍是占位（导出/降级用）", ChatEngine.IMAGE_PLACEHOLDER, last.content)
        assertEquals("纯图只发一个图块", 1, last.parts.size)
        assertTrue("不能把占位当文本发给模型", last.parts.single() is ContentPart.Image)
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
 * 造「角色 + thread + 本轮用户消息 + 一张 READY 且绑定到该消息的图」，
 * 再走**生产接线** `ChatEngine.prepare(excludeMessageId = 本轮用户消息 id)`。
 */
    private suspend fun prepareWithBoundImage(text: String): ChatEngine.Prepared {
        val app = AppContainer(context)
        container = app
        val (character, thread) = app.characters.createWithThread(
            name = "阿淮",
            persona = "温和、话不多",
        )
        val segmentId = thread.activeSegmentId ?: error("ensureThread 必须带 activeSegmentId")
        // 生产时序：先落库用户消息，再绑定附件，最后 prepare（并把这条消息 id 传下去去重）。
        val userRow = app.chat.append(
            threadId = thread.id,
            segmentId = segmentId,
            role = "user",
            text = text,
            tokens = text.length / 2 + 4,
        )
        val draft = app.attachments.createDraft(
            store = app.attachmentStore,
            kind = "IMAGE",
            mime = "image/jpeg",
            // loadImageParts 只读文件字节做 base64，不解码；任意非空字节即可。
            bytes = byteArrayOf(1, 2, 3, 4),
            width = 10,
            height = 10,
        )
        app.attachments.markReady(draft.id)
        app.attachments.bindToMessage(listOf(draft.id), userRow.id)
        return app.engine.prepare(thread, character.id, text, excludeMessageId = userRow.id)
    }

    private companion object {
        /** `AppContainer` 里 Room 的库名是写死的（`PulsezDatabase.create`）。 */
        const val APP_DB_NAME = "pulsez.db"
    }
}
