package cc.pulsez.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.settings.Consolidation
import cc.pulsez.app.data.settings.ImportPrefs
import cc.pulsez.app.data.settings.LastImport
import cc.pulsez.app.data.settings.MAX_CONSOLIDATION_SHARDS
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.data.settings.decodeConsolidation
import cc.pulsez.app.data.settings.encodeConsolidation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入偏好的 Robolectric + Preferences DataStore 测试。
 *
 * 覆盖：上次导入指纹（续导用）读写/覆盖/按 TA 清理、整理续跑进度读写/按 thread 清理、
 * 与 `pulsez_settings` 的**文件隔离**（不共用 DataStore）。全程离线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPrefsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPrefs() = runBlocking {
        // Robolectric 同一进程内 DataStore 按文件名是单例，用例间共享同一份文件；
        // 不复位会让「默认 null」断言随执行顺序漂移（参照 SettingsStoreTest）。
        ImportPrefs(context).clearAll()
    }

    @Test
    fun lastImportDefaultsToNull() = runBlocking {
        assertNull("没导入过时必须是 null，不能编一个空记录", ImportPrefs(context).lastImport.first())
    }

    @Test
    fun lastImportRoundTripsAcrossNewInstance() = runBlocking {
        ImportPrefs(context).setLastImport(
            LastImport(
                zipName = "deepseek-export.zip",
                zipSize = 123_456L,
                characterId = "char-1",
                importedAt = 1_700_000_000_000L,
            ),
        )

        // 新实例 = 杀进程后重新打开 App：续导指纹要记得。
        val reloaded = requireNotNull(ImportPrefs(context).lastImport.first())
        assertEquals("deepseek-export.zip", reloaded.zipName)
        assertEquals(123_456L, reloaded.zipSize)
        assertEquals("char-1", reloaded.characterId)
        assertEquals(1_700_000_000_000L, reloaded.importedAt)
    }

    @Test
    fun setLastImportOverwritesPreviousFingerprint() = runBlocking {
        val prefs = ImportPrefs(context)
        prefs.setLastImport("a.zip", 1L, "char-a", 10L)
        prefs.setLastImport("b.zip", 2L, "char-b", 20L)

        val last = ImportPrefs(context).lastImport.first()!!
        assertEquals("b.zip", last.zipName)
        assertEquals(2L, last.zipSize)
        assertEquals("char-b", last.characterId)
    }

    @Test
    fun clearLastImportOnlyClearsMatchingCharacter() = runBlocking {
        val prefs = ImportPrefs(context)
        prefs.setLastImport("a.zip", 1L, "char-a", 10L)

        prefs.clearLastImport("char-b")
        assertEquals(
            "撤销别的 TA 不能误清当前 TA 的续导线索",
            "char-a",
            ImportPrefs(context).lastImport.first()!!.characterId,
        )

        prefs.clearLastImport("char-a")
        assertNull(ImportPrefs(context).lastImport.first())
    }

    @Test
    fun consolidationRoundTripsAndClearsByThread() = runBlocking {
        val prefs = ImportPrefs(context)
        assertNull("没在整理时必须是 null", prefs.consolidation.first())

        // ：进度是「已成功片索引 + 失败片索引 + 总数」，不是单一计数。
        prefs.setConsolidation(
            Consolidation(
                threadId = "thread-1",
                doneShards = setOf(0, 1, 4),
                failedShards = setOf(2),
                totalShards = 7,
            ),
        )
        val reloaded = requireNotNull(ImportPrefs(context).consolidation.first())
        assertEquals("thread-1", reloaded.threadId)
        assertEquals(setOf(0, 1, 4), reloaded.doneShards)
        assertEquals(setOf(2), reloaded.failedShards)
        assertEquals(7, reloaded.totalShards)
        assertEquals("进度分子是成功片数", 3, reloaded.doneCount)
        assertTrue(reloaded.hasRemaining)

        prefs.clearConsolidation("thread-other")
        assertNotNull(
            "清别的 thread 不能误清当前整理进度",
            ImportPrefs(context).consolidation.first(),
        )

        prefs.clearConsolidation("thread-1")
        assertNull(ImportPrefs(context).consolidation.first())
    }

    @Test
    fun completedConsolidationReadsAsNoProgress() = runBlocking {
        val prefs = ImportPrefs(context)
        prefs.setConsolidation(
            Consolidation(
                threadId = "thread-1",
                doneShards = setOf(0, 1, 2),
                failedShards = emptySet(),
                totalShards = 3,
            ),
        )
        assertNull("全部完成 = 没有进度（不能显示「再整理一些」）", prefs.consolidation.first())
    }

    @Test
    fun zeroTotalConsolidationClearsInsteadOfPersisting() = runBlocking {
        val prefs = ImportPrefs(context)
        prefs.setConsolidation(
            Consolidation("thread-1", setOf(0), emptySet(), totalShards = 4),
        )
        // 0 片等价于「没有可续跑的进度」：不能写一条永远不被消费的记录。
        prefs.setConsolidation(
            Consolidation("thread-1", emptySet(), emptySet(), totalShards = 0),
        )
        assertNull(prefs.consolidation.first())
    }

    @Test
    fun importPrefsAndSettingsUseIsolatedStores() = runBlocking {
        // 行为级验证「独立 DataStore」：清空一边不能把另一边清掉。
        val settings = SettingsStore(context)
        settings.update { it.clear() }
        val prefs = ImportPrefs(context)

        settings.setWindowTier(64_000)
        prefs.setLastImport("a.zip", 1L, "char-a", 10L)

        // 清空导入偏好不应影响设置（共用文件时会被一起清掉）
        ImportPrefs(context).clearAll()
        assertEquals(64_000, SettingsStore(context).flow.first().windowTier)

        // 清空设置不应影响导入偏好
        prefs.setLastImport("a.zip", 1L, "char-a", 10L)
        SettingsStore(context).update { it.clear() }
        assertEquals("a.zip", ImportPrefs(context).lastImport.first()!!.zipName)
    }
}

/**
 * 整理进度的稳定编码/解码（必须提供 encode/decode 纯函数并单测）。
 *
 * 纯 JVM（不碰 Android），覆盖：集合往返、空集、脏数据逐项丢弃、total 边界、done/failed 重叠。
 */
class ConsolidationCodecTest {

    private fun state(
        threadId: String = "thread-1",
        done: Set<Int> = setOf(0, 2),
        failed: Set<Int> = setOf(1),
        total: Int = 3,
    ) = Consolidation(threadId, done, failed, total)

    @Test
    fun `encode decode round trips sets and thread`() {
        val original = state(
            threadId = "thread-1",
            done = setOf(0, 2, 5),
            failed = setOf(1, 4),
            total = 8,
        )
        assertEquals(original, decodeConsolidation(encodeConsolidation(original)))
        // 编码结果确定（集合按升序输出），便于排查/跨版本对账。
        assertEquals("v1|8|0,2,5|1,4|thread-1", encodeConsolidation(original))
    }

    @Test
    fun `empty sets round trip`() {
        val empty = state(done = emptySet(), failed = emptySet(), total = 3)
        assertEquals(empty, decodeConsolidation(encodeConsolidation(empty)))
    }

    @Test
    fun `illegal entries are dropped one by one`() {
        // 非整数/负数/越界：逐项丢，不整条作废（脏数据不能把整段断点变成「从头再来」）。
        assertEquals(
            Consolidation("t", setOf(0, 2), setOf(1), 3),
            decodeConsolidation("v1|3|0,x,-1,2,9|1,abc,7|t"),
        )
    }

    @Test
    fun `failed overlapping done is treated as done`() {
        // 成功过的片绝不能被当成失败重跑（ 「绝不重调 completeJson」）。
        assertEquals(
            Consolidation("t", setOf(1), setOf(2), 4),
            decodeConsolidation("v1|4|1|1,2|t"),
        )
    }

    @Test
    fun `total boundaries are rejected`() {
        assertNull("total=0 不是有效进度", decodeConsolidation("v1|0|0|1|t"))
        assertNull("负数 total 是脏数据", decodeConsolidation("v1|-3|0|1|t"))
        assertNull("total 非整数是脏数据", decodeConsolidation("v1|abc|0|1|t"))
        assertNull(
            "超大 total 是脏数据",
            decodeConsolidation("v1|${MAX_CONSOLIDATION_SHARDS + 1}|0|1|t"),
        )
        // 上限本身合法。
        assertNotNull(
            decodeConsolidation("v1|$MAX_CONSOLIDATION_SHARDS|0|1|t"),
        )
    }

    @Test
    fun `malformed payloads decode to null`() {
        assertNull(decodeConsolidation(null))
        assertNull(decodeConsolidation(""))
        assertNull(decodeConsolidation("   "))
        assertNull("字段数不对", decodeConsolidation("v1|3|0|1"))
        assertNull("版本不对", decodeConsolidation("v2|3|0|1|t"))
        assertNull("threadId 空", decodeConsolidation("v1|3|0|1|"))
        assertNull("threadId 全空白", decodeConsolidation("v1|3|0|1|   "))
    }

    @Test
    fun `thread id containing separators survives split`() {
        // threadId 放在最后 + split(limit=5)：即使 id 里有 `|` 也不会截断。
        val odd = Consolidation("weird|id", setOf(0), emptySet(), 2)
        assertEquals(odd, decodeConsolidation(encodeConsolidation(odd)))
    }
}
