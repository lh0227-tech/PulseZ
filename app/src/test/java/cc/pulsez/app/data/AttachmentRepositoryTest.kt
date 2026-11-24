package cc.pulsez.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.attachments.AttachmentErrorCodes
import cc.pulsez.app.data.attachments.AttachmentException
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.AttachmentRepository
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 附件仓储 + 本地文件仓库的 JVM 测试（「可自动测」）。
 *
 * 覆盖：
 * - 草稿 → READY → 绑定消息 → 按消息列出；
 * - 草稿箱只含未绑定 DRAFT；
 * - 软删后列表不含、文件可单独删；
 * - `clearAll` 后 DB 行软删、目录为空；
 * - `cleanupOrphans` 删孤儿/半成品、不删在用文件；
 * - 同一条消息多附件；
 * - 超限拒绝（小上限注入）且不留 DB 行/半成品；
 * - 路径穿越与非法扩展名被拒（文件名只用 id）；
 * - 绑定事务的原子性（一个 id 不存在则整批回滚）与可读错误码（不吞错）。
 *
 * 设施：Robolectric + 内存 Room（与 `RoomIntegrationTest` 同口径）+ JUnit `TemporaryFolder`
 * 作为 `filesDir`，不连真机、不依赖 Android 文件目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: PulsezDatabase
    private lateinit var repo: AttachmentRepository
    private lateinit var store: AttachmentStore

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
    // 1. 主链路：草稿 → READY → 绑定 → 按消息列出
    // ------------------------------------------------------------------

    @Test
    fun `草稿落盘后可标 READY 并绑定到消息`() = runBlocking {
        val bytes = ByteArray(2_048) { (it % 251).toByte() }
        val draft = repo.createDraft(
            store = store,
            kind = "IMAGE",
            mime = "image/jpeg",
            bytes = bytes,
            fileName = " 截图.jpg ",
            width = 1_536,
            height = 1_024,
            createdAt = 1_000L,
        )

        assertEquals(AttachmentStatus.DRAFT, draft.status)
        assertNull("草稿期不应有 messageId", draft.messageId)
        assertEquals(bytes.size.toLong(), draft.sizeBytes)
        assertEquals("截图.jpg", draft.fileName)
        assertEquals(1_536, draft.width)
        assertEquals(1_024, draft.height)
        assertEquals(0, draft.attempts)
        assertNull(draft.remoteFileId)
        assertNull(draft.expiresAt)
        assertNull(draft.deletedAt)
        assertTrue("文件应真实落盘", store.exists(draft.localPath))
        assertTrue("文件名必须用 id，不用用户原名", draft.localPath.endsWith("/${draft.id}.jpg"))
        assertTrue("内容应与写入一致", store.path(draft.localPath).readBytes().contentEquals(bytes))

        assertEquals(listOf(draft.id), db.attachmentDao().listDraft().map { it.id })

        val ready = repo.markReady(draft.id)
        assertEquals(AttachmentStatus.READY, ready.status)
        assertNull(ready.errorKind)

        val bound = repo.bindToMessage(listOf(draft.id), "msg-1")
        assertEquals(1, bound.size)
        assertEquals("msg-1", bound.single().messageId)
        assertEquals(listOf(draft.id), repo.listForMessage("msg-1").map { it.id })
        assertEquals(1, db.attachmentDao().countByMessage("msg-1"))
        assertTrue("绑定后不再是草稿", db.attachmentDao().listDraft().isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. 软删：列表不含、文件可删
    // ------------------------------------------------------------------

    @Test
    fun `软删消息附件后列表不含且文件可删`() = runBlocking {
        val draft = repo.createDraft(store, "IMAGE", "image/png", ByteArray(32), createdAt = 1_000L)
        repo.markReady(draft.id)
        repo.bindToMessage(listOf(draft.id), "msg-1")

        val localPaths = repo.softDeleteForMessage("msg-1", now = 2_000L)
        assertEquals(listOf(draft.localPath), localPaths)
        assertTrue("软删后按消息查应为空", repo.listForMessage("msg-1").isEmpty())
        assertNotNull("行还在（软删），deletedAt 应置位", db.attachmentDao().byId(draft.id))
        assertEquals(2_000L, db.attachmentDao().byId(draft.id)!!.deletedAt ?: -1L)
        assertTrue("DB 软删不自动删文件（由调用方/孤儿清理回收）", store.exists(draft.localPath))

        assertTrue("调用方可以按返回的 localPath 删文件", store.delete(localPaths.single()))
        assertFalse(store.exists(draft.localPath))
        assertFalse("重复删是幂等 false", store.delete(localPaths.single()))
    }

    // ------------------------------------------------------------------
    // 3. clearAll：DB 软删 + 目录为空
    // ------------------------------------------------------------------

    @Test
    fun `clearAll 软删全部行并清空附件目录`() = runBlocking {
        val a = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(10), createdAt = 1_000L)
        val b = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(20), createdAt = 2_000L)
        repo.markReady(a.id)
        repo.bindToMessage(listOf(a.id), "msg-1")
        assertNotNull(db.attachmentDao().byId(b.id))

        val softDeleted = repo.clearAll(store)
        assertEquals("两行都应被软删", 2, softDeleted)
        assertNotNull("a 的 deletedAt 应置位", db.attachmentDao().byId(a.id)!!.deletedAt)
        assertNotNull(db.attachmentDao().byId(b.id)!!.deletedAt)
        assertTrue("清空后没有在用附件", db.attachmentDao().idsInUse().isEmpty())
        assertTrue("清空后草稿箱为空", db.attachmentDao().listDraft().isEmpty())
        assertTrue("清空后附件目录必须为空", store.dir.listFiles().isNullOrEmpty())
    }

    // ------------------------------------------------------------------
    // 4. 孤儿清理：删孤儿与半成品、不删在用文件
    // ------------------------------------------------------------------

    @Test
    fun `cleanupOrphans 删孤儿和半成品但不删在用附件`() = runBlocking {
        val live = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(8), id = "live-1", createdAt = 1_000L)
        val deleted = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(8), id = "deleted-1", createdAt = 2_000L)
        db.attachmentDao().softDeleteById(deleted.id, 3_000L)

        // 孤儿：盘上有文件、DB 没有对应行（模拟「删文件前进程被杀」）
        val orphanPath = with(store) {
            save(ByteArrayInputStream(ByteArray(4)), "orphan-1", "jpg")
        }
        // 半成品：写文件中途崩溃留下的 .part
        val partFile = File(store.dir, "half-1.jpg.part")
        partFile.parentFile?.mkdirs()
        partFile.writeBytes(ByteArray(4))

        // 清理默认跳过最近 10 分钟修改的文件（防「先落盘后写 DB」竞态）。
        // 本用例验证的是"孤儿回收"本身，所以把测试文件时间调到保护窗口外；
        // 近期保护的专项断言见 `AttachmentCleanupGuardTest`。
        val outsideGuard = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        store.dir.listFiles()?.forEach { it.setLastModified(outsideGuard) }

        val removed = repo.cleanupOrphans(store)
        assertEquals("应删：deleted-1 文件 + orphan-1 + half-1.part", 3, removed)
        assertTrue("在用附件不能被删", store.exists(live.localPath))
        assertFalse("软删行的文件应被回收", store.exists(deleted.localPath))
        assertFalse("DB 没有的孤儿文件应被删", store.exists(orphanPath))
        assertFalse("半成品应被删", partFile.exists())
    }

    @Test
    fun `cleanupOrphans 在目录不存在时返回 0`() = runBlocking {
        assertEquals(0, repo.cleanupOrphans(store))
    }

    // ------------------------------------------------------------------
    // 5. 同一条消息多附件
    // ------------------------------------------------------------------

    @Test
    fun `同一条消息可以绑定多张图`() = runBlocking {
        val drafts = (1..3).map { index ->
            repo.createDraft(
                store = store,
                kind = "IMAGE",
                mime = "image/jpeg",
                bytes = ByteArray(index * 4),
                createdAt = index.toLong(),
            )
        }
        drafts.forEach { repo.markReady(it.id) }
        repo.bindToMessage(drafts.map { it.id }, "msg-multi")

        assertEquals(3, repo.listForMessage("msg-multi").size)
        assertEquals(3, db.attachmentDao().countByMessage("msg-multi"))
        assertEquals(drafts.map { it.id }, repo.listForMessage("msg-multi").map { it.id })
    }

    // ------------------------------------------------------------------
    // 6. 超限拒绝（小上限注入）：不留 DB 行、不留半成品
    // ------------------------------------------------------------------

    @Test
    fun `超过上限时抛 TOO_LARGE 且不留行与文件`() = runBlocking {
        val code = errorCodeOf {
            repo.createDraft(
                store = store,
                kind = "IMAGE",
                mime = "image/jpeg",
                bytes = ByteArray(100),
                maxBytes = 8L,
                id = "too-large",
                createdAt = 1_000L,
            )
        }
        assertEquals(AttachmentErrorCodes.TOO_LARGE, code)
        assertNull("失败不应写入 DB 行", db.attachmentDao().byId("too-large"))
        assertTrue("失败不应留下半成品", store.dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `流版 createDraft 超限同样拒绝`() = runBlocking {
        val code = errorCodeOf {
            repo.createDraft(
                store = store,
                kind = "IMAGE",
                mime = "image/png",
                stream = ByteArrayInputStream(ByteArray(64)),
                maxBytes = 16L,
                id = "too-large-stream",
            )
        }
        assertEquals(AttachmentErrorCodes.TOO_LARGE, code)
        assertNull(db.attachmentDao().byId("too-large-stream"))
    }

    // ------------------------------------------------------------------
    // 7. 错误不吞：非法参数、找不到行、绑定事务原子性
    // ------------------------------------------------------------------

    @Test
    fun `非法参数给 INVALID_ARGUMENT`() = runBlocking {
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            errorCodeOf { repo.createDraft(store, " ", "image/jpeg", ByteArray(4)) },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            errorCodeOf { repo.createDraft(store, "IMAGE", "", ByteArray(4)) },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            errorCodeOf { repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(4), width = 0) },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            errorCodeOf { repo.bindToMessage(emptyList(), "msg") },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            errorCodeOf { repo.bindToMessage(listOf("any"), " ") },
        )
    }

    @Test
    fun `markReady 找不到行给 NOT_FOUND`() = runBlocking {
        assertEquals(
            AttachmentErrorCodes.NOT_FOUND,
            errorCodeOf { repo.markReady("missing") },
        )
    }

    @Test
    fun `绑定含不存在的 id 时整批回滚`() = runBlocking {
        val draft = repo.createDraft(store, "IMAGE", "image/jpeg", ByteArray(8), id = "rollback-1")
        val code = errorCodeOf {
            repo.bindToMessage(listOf(draft.id, "missing-id"), "msg-atomic")
        }
        assertEquals(AttachmentErrorCodes.NOT_FOUND, code)
        assertNull("事务必须回滚：不能一半绑定成功", db.attachmentDao().byId(draft.id)!!.messageId)
        assertTrue(repo.listForMessage("msg-atomic").isEmpty())
    }

    // ------------------------------------------------------------------
    // 8. AttachmentStore 自身：路径穿越/上限/尺寸/清理
    // ------------------------------------------------------------------

    @Test
    fun `附件 id 与扩展名不允许路径穿越`() {
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            codeOf { store.save(ByteArrayInputStream(ByteArray(4)), "../evil", "jpg") },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            codeOf { store.save(ByteArrayInputStream(ByteArray(4)), "ok-id", "../evil") },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            codeOf { store.save(ByteArrayInputStream(ByteArray(4)), "ok-id", "jpg", maxBytes = 0L) },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            codeOf { store.path("../secrets.bin") },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            codeOf { store.path("/data/data/other/files/x.jpg") },
        )
        // 纵深防御：只接受 filesDir/attachments/ 内，
        // 不是仅 filesDir 内；前缀相似目录不能借「字符串前缀」绕过。
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            codeOf { store.path("other-subdir/x.jpg") },
        )
        assertEquals(
            AttachmentErrorCodes.INVALID_ARGUMENT,
            codeOf { store.path("attachments_evil/x.jpg") },
        )
    }

    @Test
    fun `path 接受 attachments 目录内的合法相对路径`() {
        val localPath = store.save(ByteArrayInputStream(ByteArray(8)), "legal-1", "jpg")
        val resolved = store.path(localPath)
        assertEquals(File(store.dir, "legal-1.jpg").canonicalFile, resolved)
        assertTrue("合法相对路径应解析到真实文件", resolved.isFile)
    }

    @Test
    fun `sizeOf 与 delete 对缺失文件是安全幂等`() {
        assertEquals(0L, store.sizeOf("attachments/missing.jpg"))
        assertFalse(store.delete("attachments/missing.jpg"))
        assertFalse(store.exists("attachments/missing.jpg"))
        assertEquals(0, store.clearAll())
        assertEquals(0, store.cleanupOrphans(emptySet()))
    }

    @Test
    fun `save 返回相对路径且 sizeOf 读到真实字节数`() {
        val localPath = store.save(ByteArrayInputStream(ByteArray(123)), "size-1", "jpg")
        assertEquals("attachments/size-1.jpg", localPath)
        assertEquals(123L, store.sizeOf(localPath))
        assertTrue(store.exists(localPath))
        assertTrue(store.delete(localPath))
        assertEquals(0L, store.sizeOf(localPath))
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private suspend fun errorCodeOf(block: suspend () -> Unit): String = try {
        block()
        fail("应抛 AttachmentException")
        ""
    } catch (e: AttachmentException) {
        e.code
    }

    private fun codeOf(block: () -> Unit): String = try {
        block()
        fail("应抛 AttachmentException")
        ""
    } catch (e: AttachmentException) {
        e.code
    }
}
