package cc.pulsez.app.data

import cc.pulsez.app.data.attachments.AttachmentStore
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 孤儿清理的"近期文件保护"（决策）。
 *
 * 写入时序是「先落盘、后写 DB」：启动清理若与保存并发，刚写的文件/`.part` 可能 DB 里还没有行。
 * 因此清理必须跳过最近 10 分钟内修改的文件； 的 path 收紧语义不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentCleanupGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): AttachmentStore = AttachmentStore(tempFolder.newFolder("files"))

    private fun AttachmentStore.saveFile(id: String, ext: String = "jpg"): String =
        save(ByteArrayInputStream(ByteArray(32)), id, ext)

    @Test
    fun `最近修改的孤儿文件不清 老文件才清`() {
        val store = store()
        val recent = store.saveFile("orphan-recent")
        val old = store.saveFile("orphan-old")
        val oldFile = store.path(old)
        oldFile.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L) // 2 小时前

        val deleted = store.cleanupOrphans(
            keepIds = emptySet(),
            now = System.currentTimeMillis(),
            recentGuardMs = AttachmentStore.CLEANUP_RECENT_GUARD_MS,
        )

        assertEquals("只删掉老孤儿", 1, deleted)
        assertTrue("刚写的文件必须保住（DB 行可能还没提交）", store.exists(recent))
        assertFalse("老孤儿照常回收", store.exists(old))
    }

    @Test
    fun `近期 part 半成品不清 老的才清`() {
        val store = store()
        val dir = store.dir
        dir.mkdirs()
        val recentPart = File(dir, "writing.jpg.part")
        recentPart.writeBytes(ByteArray(8))
        val oldPart = File(dir, "crashed.jpg.part")
        oldPart.writeBytes(ByteArray(8))
        oldPart.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)

        val deleted = store.cleanupOrphans(
            keepIds = emptySet(),
            now = System.currentTimeMillis(),
            recentGuardMs = AttachmentStore.CLEANUP_RECENT_GUARD_MS,
        )

        assertEquals(1, deleted)
        assertTrue("正在写的 .part 不能删", recentPart.exists())
        assertFalse("崩溃残留的 .part 回收", oldPart.exists())
    }

    @Test
    fun `在用文件即使很老也不删`() {
        val store = store()
        val inUse = store.saveFile("keep-me")
        store.path(inUse).setLastModified(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)

        val deleted = store.cleanupOrphans(keepIds = setOf("keep-me"), now = System.currentTimeMillis())

        assertEquals(0, deleted)
        assertTrue(store.exists(inUse))
    }

    @Test
    fun `显式关闭保护窗口时恢复旧行为`() {
        // 测试/维护动作可以显式传 recentGuardMs=0（旧行为）；生产默认 10 分钟。
        assertEquals(10 * 60 * 1000L, AttachmentStore.CLEANUP_RECENT_GUARD_MS)
        val store = store()
        val recent = store.saveFile("orphan-recent")
        val deleted = store.cleanupOrphans(
            keepIds = emptySet(),
            now = System.currentTimeMillis(),
            recentGuardMs = 0,
        )
        assertEquals(1, deleted)
        assertFalse(store.exists(recent))
    }

    @Test
    fun `保护窗口的 cutoff 方向正确 窗口内保留窗口外回收`() {
        // 回归护栏（修复）：cutoff 用可空表达后，"关闭保护"不能再被
        // `Long.MIN_VALUE` 兜底写成"全部跳过"。这里验证默认窗口两侧的方向：
        // mtime = now - guard + 60s（窗口内）保留；mtime = now - guard - 60s（窗口外）回收。
        val store = store()
        val inside = store.saveFile("inside-window")
        val outside = store.saveFile("outside-window")
        val now = System.currentTimeMillis()
        val guard = AttachmentStore.CLEANUP_RECENT_GUARD_MS
        assertTrue("设置 mtime 失败", store.path(inside).setLastModified(now - guard + 60_000L))
        assertTrue("设置 mtime 失败", store.path(outside).setLastModified(now - guard - 60_000L))

        val deleted = store.cleanupOrphans(emptySet(), now = now, recentGuardMs = guard)

        assertEquals("窗口内保留、窗口外回收", 1, deleted)
        assertTrue("窗口内文件必须保住", store.exists(inside))
        assertFalse("窗口外孤儿应回收", store.exists(outside))
    }
}
