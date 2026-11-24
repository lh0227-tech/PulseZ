package cc.pulsez.app.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证清空发生在调用方检查通过后、引擎执行前时，旧代际内容不会写入索引。
 *
 * 这里只检查引擎层的代际判定，没有运行完整清空 UI 流程或设备级并发调度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WipeLateIndexBehaviorTest {

    private companion object {
        /** `AppContainer` 里 Room 的库名是写死的（`PulsezDatabase.create`）。 */
        const val APP_DB_NAME = "pulsez.db"
    }

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var indexThreadId: String
    private lateinit var indexSegmentId: String

    /** 注入的内存密钥表（不碰 AndroidKeyStore；本测试不需要任何真实密钥/网络）。 */
    private val secrets = mutableMapOf<String, String>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // Room 库在 Robolectric 进程内是文件库：清掉上次运行残留，避免 chunk 串用例。
        context.deleteDatabase(APP_DB_NAME)
        // DataStore 在同一 Robolectric 进程里是单例，用例之间共享 → 清回默认（不配置向量端点）。
        SettingsStore(context).update { it.clear() }
        secrets.clear()
        container = AppContainer(
            context,
            secretReader = { name -> secrets[name] },
            secretRemover = { name -> secrets.remove(name) },
            secretWriter = { name, value -> secrets[name] = value },
        )
        val (_, indexThread) = container.characters.createWithThread(
            name = "wipe index fixture",
            persona = "synthetic test fixture",
        )
        indexThreadId = indexThread.id
        indexSegmentId = requireNotNull(indexThread.activeSegmentId)
    }

    @After
    fun tearDown() {
        runCatching { container.database.close() }
        secrets.clear()
        context.deleteDatabase(APP_DB_NAME)
    }

    /**
 * 调一次真实索引写入。
 *
 * @param expectedWipeEpoch null = 不传代际（旧调用点：默认参数取进入函数时的当前代际）
 */
    private suspend fun indexTurn(expectedWipeEpoch: Long?) {
        if (expectedWipeEpoch == null) {
            container.engine.indexTurn(
                threadId = indexThreadId,
                segmentId = indexSegmentId,
                userText = "今天有点累",
                assistantText = "那就早点休息",
                userMessageId = "u1",
                assistantMessageId = "a1",
                embed = false,
            )
        } else {
            container.engine.indexTurn(
                threadId = indexThreadId,
                segmentId = indexSegmentId,
                userText = "今天有点累",
                assistantText = "那就早点休息",
                userMessageId = "u1",
                assistantMessageId = "a1",
                embed = false,
                expectedWipeEpoch = expectedWipeEpoch,
            )
        }
    }

    private suspend fun chunks() = container.database.chunkDao().recent(indexThreadId, 10)

    @Test
    fun `清空后 用调用方旧代际调用 indexTurn 不写 chunk`() = runBlocking {
        val captured = container.wipeEpoch.value
        // 模拟「调用方检查通过之后、引擎执行之前」发生清空（「执行中窗口」：
        // 代际在清空**开始执行**时自增，见 AppContainer.notifyUserDataWiped）。
        container.notifyUserDataWiped()
        assertTrue("前置：代际必须已经变化", container.wipeEpoch.value != captured)

        indexTurn(captured)

        assertTrue(
            "引擎不得用清空后的新代际通过校验：清空前的正文不能写成 chunk/chunk_term",
            chunks().isEmpty(),
        )
    }

    @Test
    fun `清空后 用当前代际调用 indexTurn 正常写入关键词 chunk`() = runBlocking {
        container.notifyUserDataWiped()
        // 正向对照：清空**之后**发起的一轮，代际就是当前值，索引照常写（未清空语义不变）。
        indexTurn(container.wipeEpoch.value)

        val chunk = chunks().single()
        assertEquals("未配置向量时只写关键词 chunk：dim=0", 0, chunk.dim)
        assertTrue("未配置向量时只写关键词 chunk：vec 为空", chunk.vec.isEmpty())
        assertEquals("未配置向量时 chunk.model=none", "none", chunk.model)
        assertTrue("chunk 正文必须包含这轮对话", chunk.text.contains("今天有点累"))
    }

    @Test
    fun `不传代际的旧调用点保持 v0_5_4 行为`() = runBlocking {
        // 未清空时，传不传代际都写：默认参数（进入函数时的当前代际）= 行为，正常路径不变。
        indexTurn(null)
        assertEquals("没清空时索引照常写", 1, chunks().size)
    }
}
