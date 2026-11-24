package cc.pulsez.app.ui.threads

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadsDirectoryFoldTest {

    private lateinit var context: Context
    private var container: AppContainer? = null
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-24T04:00:00Z")
    private val cutoff = sevenDaysCutoffMillis(now, zone)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        runBlocking { SettingsStore(context).update { it.clear() } }
    }

    @After
    fun tearDown() {
        runCatching { container?.database?.close() }
        context.deleteDatabase(APP_DB_NAME)
    }

    private fun item(
        id: String,
        name: String,
        lastChatAt: Long? = null,
        createdAt: Long = 0L,
        threadLastMessageAt: Long? = null,
    ) = CharacterDirectoryItem(
        id = id,
        name = name,
        imported = false,
        lastSingleChatActivityAt = lastChatAt,
        createdAt = createdAt,
        threadLastMessageAt = threadLastMessageAt,
    )

    @Test
    fun partitionsByMaxOfLiveActivityCreatedAtAndThreadLastMessageAt() {
        val recentLive = item("r-live", "阿一", lastChatAt = cutoff + 1_000L, createdAt = 10L)
        val recentCreated = item("r-new", "阿二", lastChatAt = null, createdAt = cutoff)
        val recentImportedThread = item("r-thread", "阿三", lastChatAt = null, createdAt = 10L, threadLastMessageAt = cutoff + 500L)
        val olderRole = item("old-1", "老四", lastChatAt = cutoff - 1L, createdAt = cutoff - 10_000L, threadLastMessageAt = cutoff - 1L)

        val partition = partitionDirectoryItems(
            sortedItems = listOf(recentLive, recentCreated, recentImportedThread, olderRole),
            cutoffMillis = cutoff,
            query = "",
            selectionActive = false,
        )

        assertEquals(listOf("r-live", "r-new", "r-thread"), partition.recentItems.map { it.id })
        assertEquals(listOf("old-1"), partition.olderItems.map { it.id })
        assertFalse("近 7 天非空且非搜索/多选态时老角色默认折叠", partition.autoExpandedOlder)
    }

    @Test
    fun autoExpandsOlderWhenSearchingSelectingOrNoRecentCharacters() {
        val recent = item("r1", "阿一", createdAt = cutoff + 100L)
        val older = item("o1", "老二", createdAt = cutoff - 100L)

        val searching = partitionDirectoryItems(
            sortedItems = listOf(recent, older),
            cutoffMillis = cutoff,
            query = "老",
            selectionActive = false,
        )
        assertTrue("搜索非空时自动全量展开", searching.autoExpandedOlder)

        val selecting = partitionDirectoryItems(
            sortedItems = listOf(recent, older),
            cutoffMillis = cutoff,
            query = "",
            selectionActive = true,
        )
        assertTrue("进入多选态时自动全量展开", selecting.autoExpandedOlder)

        val onlyOlder = partitionDirectoryItems(
            sortedItems = listOf(older),
            cutoffMillis = cutoff,
            query = "",
            selectionActive = false,
        )
        assertTrue("近 7 天为 0 但有老角色时自动展开防空白页", onlyOlder.autoExpandedOlder)
    }

    @Test
    fun viewModelPopulatesCreatedAtAndThreadLastMessageAtWithoutSchemaChange() {
        val app = AppContainer(context)
        container = app
        val (character, thread) = runBlocking {
            app.characters.createWithThread(name = "阿淮", persona = "温和")
        }
        val vm = ThreadsViewModel(app)
        var item: CharacterDirectoryItem? = null
        repeat(60) {
            shadowOf(Looper.getMainLooper()).idle()
            item = vm.state.value.directoryItems.firstOrNull { it.id == character.id }
            if (item != null) return@repeat
            Thread.sleep(25)
        }
        val loaded = requireNotNull(item) { "ThreadsViewModel 未在超时内加载角色目录项" }
        assertEquals(character.createdAt, loaded.createdAt)
        assertEquals(thread.lastMessageAt, loaded.threadLastMessageAt)
    }

    private companion object {
        const val APP_DB_NAME = "pulsez.db"
    }
}
