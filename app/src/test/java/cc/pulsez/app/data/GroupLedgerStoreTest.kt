package cc.pulsez.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.core.SpeakerLedgerSnapshot
import cc.pulsez.app.data.group.GroupLedgerStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 缺陷 的 DataStore 层验收（Robolectric，离线，不依真机）。
 *
 * 纯逻辑的 JSON 编解码与恢复规则在 `GroupLedgerPersistenceTest`；
 * 这里只补一条链路事实：真的写进 Preferences DataStore 之后，**换一个实例也能读回来**
 * （即杀进程后重新进入群聊的场景），并且读不到时返回 null 让调用方回退到历史重建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupLedgerStoreTest {

    private val store: GroupLedgerStore
        get() = GroupLedgerStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun savedLedgerSurvivesNewStoreInstance() = runBlocking {
        val threadId = "thread-${UUID.randomUUID()}"
        val snapshot = SpeakerLedgerSnapshot(
            lastSpeakers = listOf("b", "a"),
            silentTurns = mapOf("a" to 0, "b" to 0, "c" to 5),
        )

        store.save(threadId, snapshot)

        // 新实例 = 杀进程后重新打开群聊
        assertEquals(snapshot, store.load(threadId))
    }

    @Test
    fun missingThreadReturnsNull() = runBlocking {
        assertNull(store.load("thread-missing-${UUID.randomUUID()}"))
    }

    @Test
    fun ledgersAreIsolatedPerThread() = runBlocking {
        val first = "thread-a-${UUID.randomUUID()}"
        val second = "thread-b-${UUID.randomUUID()}"
        val snapshot = SpeakerLedgerSnapshot(lastSpeakers = listOf("a"), silentTurns = mapOf("a" to 0))

        store.save(first, snapshot)

        assertNull("另一个群不能被这个群的账本污染", store.load(second))
        assertEquals(snapshot, store.load(first))
    }
}
