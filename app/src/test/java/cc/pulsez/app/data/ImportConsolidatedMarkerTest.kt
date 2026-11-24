package cc.pulsez.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.ConsolidateResult
import cc.pulsez.app.data.settings.ImportPrefs
import cc.pulsez.app.data.settings.isFullyConsolidated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「已整理」标记（ 补充要求； 「不重复收费」/「整理续跑」）。
 *
 * 两块独立验证：
 * 1. [ImportConsolidatedPureTest]：标记的**生效条件**是纯函数（不碰 Android，可纯 JVM 跑）；
 * 2. [ImportConsolidatedPrefsTest]：标记的 DataStore **读写往返**（Robolectric，同
 * `ImportPrefsTest` 口径）。
 *
 * 背景：整理完成后 `clearConsolidation` 只清分片进度，而导入段（`batonReason="IMPORT"`）永不删除；
 * 只按「有没有导入段」判断会让记忆面板一直显示「整理往事」，用户再点一次就从 0 重跑全部分片
 * ——重复花钱、可能重复抽取记忆。标记后入口收起。
 */
class ImportConsolidatedPureTest {

    private fun result(
        shardsTotal: Int = 3,
        shardsDone: Int = 3,
        failed: Int = 0,
        cancelled: Boolean = false,
    ): ConsolidateResult = ConsolidateResult(
        shardsTotal = shardsTotal,
        shardsDone = shardsDone,
        failed = failed,
        estimatedYuan = 0.0,
        cancelled = cancelled,
    )

    @Test
    fun `fully consolidated only when all shards done without failure or cancel`() {
        assertTrue("全部成功：标记", result().isFullyConsolidated())
        assertFalse(
            "0 片不标记（全部早于 90 天的档案不能被误标已整理，否则再无入口）",
            result(shardsTotal = 0, shardsDone = 0).isFullyConsolidated(),
        )
        assertFalse("用户点停：保留断点，不标记", result(cancelled = true).isFullyConsolidated())
        assertFalse("有失败片：保留断点，不标记", result(failed = 1).isFullyConsolidated())
        assertFalse("没跑完全部分片：不标记", result(shardsDone = 2).isFullyConsolidated())
        assertTrue("done 越界（脏数据）按完成处理", result(shardsDone = 4).isFullyConsolidated())
    }
}

/**
 * 「已整理」标记的 DataStore 往返（`ImportPrefs.consolidatedThreads` / `markConsolidated` /
 * `clearConsolidated` / `clearAll`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportConsolidatedPrefsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPrefs() = runBlocking {
        // Robolectric 同一进程内 DataStore 按文件名是单例，用例间共享同一份文件（同 ImportPrefsTest）。
        ImportPrefs(context).clearAll()
    }

    @Test
    fun consolidatedThreadsDefaultsToEmpty() = runBlocking {
        assertTrue(
            "没整理过时必须是空集合，不能误判成「已整理」而藏掉入口",
            ImportPrefs(context).consolidatedThreads.first().isEmpty(),
        )
    }

    @Test
    fun markConsolidatedRoundTripsAcrossNewInstance() = runBlocking {
        ImportPrefs(context).markConsolidated("thread-1")
        ImportPrefs(context).markConsolidated("thread-2")

        // 新实例 = 杀进程后重开：标记必须还在，否则会重复花钱。
        assertEquals(
            setOf("thread-1", "thread-2"),
            ImportPrefs(context).consolidatedThreads.first(),
        )

        // 重复标记幂等（集合语义，不会出现两条）。
        ImportPrefs(context).markConsolidated("thread-1")
        assertEquals(
            setOf("thread-1", "thread-2"),
            ImportPrefs(context).consolidatedThreads.first(),
        )
    }

    @Test
    fun blankThreadIdCannotMarkAnything() = runBlocking {
        ImportPrefs(context).markConsolidated("")
        ImportPrefs(context).markConsolidated("   ")
        assertTrue(ImportPrefs(context).consolidatedThreads.first().isEmpty())
    }

    @Test
    fun clearConsolidatedOnlyRemovesMatchingThread() = runBlocking {
        ImportPrefs(context).markConsolidated("thread-1")
        ImportPrefs(context).markConsolidated("thread-2")

        ImportPrefs(context).clearConsolidated("thread-other")
        assertEquals(
            "清别的 thread 不能误清当前标记",
            setOf("thread-1", "thread-2"),
            ImportPrefs(context).consolidatedThreads.first(),
        )

        ImportPrefs(context).clearConsolidated("thread-1")
        assertEquals(setOf("thread-2"), ImportPrefs(context).consolidatedThreads.first())
    }

    @Test
    fun clearAllAlsoClearsConsolidatedThreads() = runBlocking {
        ImportPrefs(context).markConsolidated("thread-1")
        ImportPrefs(context).clearAll()
        assertTrue(
            "clearAll 要同步清掉标记（测试复位依赖它）",
            ImportPrefs(context).consolidatedThreads.first().isEmpty(),
        )
    }
}
