package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.AttachmentRepository
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
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

/**
 * 草稿 / 失败态 / 未绑定 READY 附件的回收
 * （ 扩面）。
 *
 *删草稿、绑定失败、导入中删除等路径留下的附件行仍是**活行**（`deletedAt` 为 null），
 * 而启动孤儿文件清理的 keep 集合 = 活行 → 这些行和文件永远不被回收（每触发一次永久残留一份
 * 文件 + 一行 DB，用户没有任何入口能清）。
 *
 * 补的漏：生产代码**从不写** `AttachmentStatus.FAILED`，绑定失败事务回滚后 DB 行仍
 * 是 READY；「选图成功即 READY、没发就进程被杀」也留下 READY 未绑定行。旧的
 * `status IN ('DRAFT','FAILED')` 条件永远扫不到它们 → 仍会永久残留，所以 TTL 面扩到 READY。
 *
 * 本测试锁两条回收路径：
 * 1. `discardDraft`：删草稿/回滚时软删行 + 删文件（幂等）；
 * 2. `sweepStaleDrafts`：启动维护按 24h TTL 回收「未绑定 + DRAFT/FAILED/READY」的陈旧行；
 * 新鲜草稿、SENT/SENDING、已绑定的附件都不动。
 *
 * 设施同 `AttachmentRepositoryTest`：Robolectric + 内存 Room + JUnit `TemporaryFolder`，
 * 全部离线，不连真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentDraftGcTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: PulsezDatabase
    private lateinit var repo: AttachmentRepository
    private lateinit var store: AttachmentStore

    /** 测试基准"现在"：避免依赖真实时钟，TTL 边界可精确断言。 */
    private val now = 100L * DAY_MS

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulsezDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = AttachmentStore(tempFolder.newFolder("files"))
        repo = AttachmentRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // 1. discardDraft：软删行 + 删文件（幂等）
    // ------------------------------------------------------------------

    @Test
    fun `discardDraft 软删行并删掉文件`() = runBlocking {
        val draft = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(16), createdAt = now - DAY_MS)
        repo.markReady(draft.id)
        assertTrue(store.exists(draft.localPath))

        assertTrue(repo.discardDraft(draft.id, store))

        assertNotNull("行保留（软删），deletedAt 置位", db.attachmentDao().byId(draft.id))
        assertNotNull(db.attachmentDao().byId(draft.id)!!.deletedAt)
        assertFalse("文件应被删掉", store.exists(draft.localPath))
        assertTrue("软删后不再算在用", draft.id !in db.attachmentDao().idsInUse())
    }

    @Test
    fun `discardDraft 对已删或不存在是幂等 false`() = runBlocking {
        val draft = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(8))
        repo.markReady(draft.id)
        assertTrue(repo.discardDraft(draft.id, store))
        assertEquals("第二次丢弃返回 false（行已软删）", false, repo.discardDraft(draft.id, store))
        assertEquals("不存在的 id 安全返回 false", false, repo.discardDraft("missing", store))
        assertEquals("空 id 安全返回 false", false, repo.discardDraft(" ", store))
    }

    @Test
    fun `导入中删除占位后的补偿 discard 不会误删别的行`() = runBlocking {
        // 模拟 `startImport`：导入完成时发现占位已被用户删掉 → 对刚导入的那个 id 调 discard。
        val imported = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(12), id = "imported-1")
        repo.markReady(imported.id)
        val keep = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(12), id = "keep-1")
        repo.markReady(keep.id)

        assertTrue(repo.discardDraft(imported.id, store))

        assertNotNull("被丢弃的行软删", db.attachmentDao().byId(imported.id)!!.deletedAt)
        assertFalse(store.exists(imported.localPath))
        assertTrue("其它草稿不受影响", store.exists(keep.localPath))
        assertEquals("只剩没被丢弃的那条活行", listOf(keep.id), db.attachmentDao().allLive().map { it.id })
    }

    // ------------------------------------------------------------------
    // 2. sweepStaleDrafts：TTL 回收陈旧 DRAFT/FAILED/READY
    // ------------------------------------------------------------------

    @Test
    fun `陈旧的 DRAFT 按 TTL 软删并删文件`() = runBlocking {
        val old = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "old-draft",
            createdAt = now - AttachmentRepository.STALE_DRAFT_TTL_MS - 1,
        )
        assertTrue(store.exists(old.localPath))

        val removed = repo.sweepStaleDrafts(store, now = now)

        assertEquals(1, removed)
        assertNotNull("行软删（不硬删）", db.attachmentDao().byId(old.id)!!.deletedAt)
        assertFalse("文件应被回收", store.exists(old.localPath))
        assertTrue(db.attachmentDao().idsInUse().isEmpty())
    }

    @Test
    fun `陈旧的 FAILED 同样回收`() = runBlocking {
        val failed = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "old-failed",
            createdAt = now - 2 * AttachmentRepository.STALE_DRAFT_TTL_MS,
        )
        db.attachmentDao().updateStatus(failed.id, AttachmentStatus.FAILED, "BAD_IMAGE")

        assertEquals(1, repo.sweepStaleDrafts(store, now = now))
        assertNotNull(db.attachmentDao().byId(failed.id)!!.deletedAt)
        assertFalse(store.exists(failed.localPath))
    }

    @Test
    fun `近期 DRAFT 不删（用户在选图打字）`() = runBlocking {
        val recent = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "recent-draft",
            createdAt = now - 60_000L,
        )

        assertEquals(0, repo.sweepStaleDrafts(store, now = now))
        assertNull(db.attachmentDao().byId(recent.id)!!.deletedAt)
        assertTrue(store.exists(recent.localPath))
    }

    @Test
    fun `TTL 边界按严格小于处理 恰好到期不删`() = runBlocking {
        val exactly = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "exactly-ttl",
            createdAt = now - AttachmentRepository.STALE_DRAFT_TTL_MS,
        )
        assertEquals(0, repo.sweepStaleDrafts(store, now = now))
        assertNull(db.attachmentDao().byId(exactly.id)!!.deletedAt)
        assertTrue(store.exists(exactly.localPath))
    }

    @Test
    fun `已绑定的 READY 与 SENT 都不动`() = runBlocking {
        // READY 但**已绑定**：绑定关系本身是「在用」信号，即便很老也不在清理面
        // （起未绑定的旧 READY 会被回收，见下一条测试）。
        val readyBound = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "old-ready-bound",
            createdAt = now - 10 * DAY_MS,
        )
        repo.markReady(readyBound.id)
        // 直接绑 messageId（不推进 SENT），模拟「停在 READY 的已绑定行」。
        db.attachmentDao().bindToMessage(readyBound.id, "msg-1")
        // SENT：已绑定到某轮消息，属于对话内容，绝不能被 TTL 清理误删。
        val sent = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "old-sent",
            createdAt = now - 10 * DAY_MS,
        )
        repo.markReady(sent.id)
        repo.bindToMessage(listOf(sent.id), "msg-2")

        assertEquals(0, repo.sweepStaleDrafts(store, now = now))

        assertNull(db.attachmentDao().byId(readyBound.id)!!.deletedAt)
        assertNull(db.attachmentDao().byId(sent.id)!!.deletedAt)
        assertTrue(store.exists(readyBound.localPath))
        assertTrue(store.exists(sent.localPath))
        assertEquals(1, repo.listForMessage("msg-1").size)
        assertEquals(1, repo.listForMessage("msg-2").size)
    }

    @Test
    fun `陈旧且未绑定的 READY 按 TTL 回收（R1 的核心缺口）`() = runBlocking {
        //。生产代码从不写 FAILED（全仓只有读取过滤）：
        // 绑定失败事务回滚后行仍是 READY；「选图成功即 READY、没发就进程被杀」也留下
        // READY 未绑定行。旧条件 ('DRAFT','FAILED') 永远扫不到 → 扩到 READY 后必须被回收。
        val old = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "old-ready",
            createdAt = now - 2 * AttachmentRepository.STALE_DRAFT_TTL_MS,
        )
        repo.markReady(old.id)
        assertEquals(AttachmentStatus.READY, db.attachmentDao().byId(old.id)!!.status)
        assertTrue(store.exists(old.localPath))

        val removed = repo.sweepStaleDrafts(store, now = now)

        assertEquals(1, removed)
        assertNotNull("行软删（不硬删）", db.attachmentDao().byId(old.id)!!.deletedAt)
        assertFalse("文件应被回收", store.exists(old.localPath))
        assertTrue(db.attachmentDao().idsInUse().isEmpty())
    }

    @Test
    fun `新鲜的 READY 未绑定不删（用户可能马上要发）`() = runBlocking {
        val recent = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "recent-ready",
            createdAt = now - 60_000L,
        )
        repo.markReady(recent.id)

        assertEquals(0, repo.sweepStaleDrafts(store, now = now))
        assertNull(db.attachmentDao().byId(recent.id)!!.deletedAt)
        assertTrue(store.exists(recent.localPath))
    }

    @Test
    fun `SENDING 不动（即便很老且未绑定）`() = runBlocking {
        // 防御性：SENDING 不在清理面（正常由发送链路推进，这里直接构造边界）。
        val sending = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "old-sending",
            createdAt = now - 10 * DAY_MS,
        )
        db.attachmentDao().updateStatus(sending.id, AttachmentStatus.SENDING, null)

        assertEquals(0, repo.sweepStaleDrafts(store, now = now))
        assertNull(db.attachmentDao().byId(sending.id)!!.deletedAt)
        assertTrue(store.exists(sending.localPath))
    }

    @Test
    fun `绑定失败回滚后 READY 行陈旧时能被 TTL 回收`() = runBlocking {
        // 复现 ChatViewModel.runTurn 的绑定失败路径：bindToMessage 整批回滚，
        // 行仍是 READY、messageId 仍为 null（生产不写 FAILED）。 要求这类行也能被 24h TTL 扫到。
        val old = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "bind-fail-ready",
            createdAt = now - 2 * AttachmentRepository.STALE_DRAFT_TTL_MS,
        )
        repo.markReady(old.id)

        val error = runCatching {
            repo.bindToMessage(listOf(old.id, "missing-attachment"), "msg-bind-fail")
        }.exceptionOrNull()
        assertNotNull("有一个 id 不存在 → 整批失败抛错", error)
        val after = db.attachmentDao().byId(old.id)!!
        assertEquals("事务回滚后状态仍是 READY（生产从不写 FAILED）", AttachmentStatus.READY, after.status)
        assertNull("messageId 回滚为 null", after.messageId)

        assertEquals(1, repo.sweepStaleDrafts(store, now = now))
        assertNotNull(db.attachmentDao().byId(old.id)!!.deletedAt)
        assertFalse(store.exists(old.localPath))
    }

    @Test
    fun `已绑定到消息的旧 DRAFT 也不动`() = runBlocking {
        // 防御性：绑定后正常会推进到 SENT，但万一停在 DRAFT，绑定关系本身是"在用"信号。
        val bound = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "bound-draft",
            createdAt = now - 10 * DAY_MS,
        )
        db.attachmentDao().bindToMessage(bound.id, "msg-2")

        assertEquals(0, repo.sweepStaleDrafts(store, now = now))
        assertNull(db.attachmentDao().byId(bound.id)!!.deletedAt)
        assertTrue(store.exists(bound.localPath))
    }

    @Test
    fun `ttlMs 小于等于 0 时关闭回收`() = runBlocking {
        val old = repo.createDraft(
            store,
            "IMAGE",
            "image/jpeg",
            ByteArray(10),
            id = "old-draft",
            createdAt = now - 10 * DAY_MS,
        )

        assertEquals(0, repo.sweepStaleDrafts(store, now = now, ttlMs = 0L))
        assertNull(db.attachmentDao().byId(old.id)!!.deletedAt)
        assertTrue(store.exists(old.localPath))
    }

    @Test
    fun `没有陈旧行时返回 0 且不碰目录里其它文件`() = runBlocking {
        // 启动时序：sweep 之后才是 cleanupOrphans。sweep 本身只删自己软删行的文件。
        val orphanPath = with(store) {
            save(ByteArrayInputStream(ByteArray(4)), "orphan-x", "jpg")
        }
        assertEquals(0, repo.sweepStaleDrafts(store, now = now))
        assertTrue("孤儿文件留给 cleanupOrphans，不在 sweep 里乱删", store.exists(orphanPath))
    }

    private companion object {
        const val DAY_MS: Long = 24 * 60 * 60 * 1000L
    }
}
