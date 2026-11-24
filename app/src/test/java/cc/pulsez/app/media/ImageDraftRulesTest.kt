package cc.pulsez.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿规则测试。
 *
 * 覆盖：≤3 张（失败不占名额）、准备中禁止发送、只有图没文字也能发、空草稿不能发、
 * 只有 READY 才参与绑定/体积检查。纯 JVM。
 */
class ImageDraftRulesTest {

    private fun draft(
        id: String,
        status: DraftStatus,
        path: String = "attachments/$id.jpg",
    ) = DraftImage(id = id, localPath = path, status = status)

    @Test
    fun `最多 3 张 失败的不占名额`() {
        val twoReady = listOf(draft("1", DraftStatus.READY), draft("2", DraftStatus.READY))
        assertTrue(DraftRules.canAdd(twoReady))

        val three = twoReady + draft("3", DraftStatus.PREPARING)
        assertFalse("3 张就满了", DraftRules.canAdd(three))
        assertEquals(3, DraftRules.activeCount(three))

        val withFailed = twoReady + draft("3", DraftStatus.FAILED)
        assertTrue("失败态不占名额，可以换一张", DraftRules.canAdd(withFailed))
        assertEquals(2, DraftRules.activeCount(withFailed))
    }

    @Test
    fun `准备中禁止发送 空草稿不能发`() {
        val preparing = listOf(draft("1", DraftStatus.PREPARING))
        assertEquals(
            DraftRules.SendBlock.IMAGES_PREPARING,
            DraftRules.sendBlock(preparing, hasText = true, streaming = false),
        )
        assertEquals(
            DraftRules.SendBlock.EMPTY,
            DraftRules.sendBlock(emptyList(), hasText = false, streaming = false),
        )
        assertNull(
            "只有图没文字也能发",
            DraftRules.sendBlock(listOf(draft("1", DraftStatus.READY)), hasText = false, streaming = false),
        )
        assertNull(
            "有文字就能发（图片准备中除外）",
            DraftRules.sendBlock(emptyList(), hasText = true, streaming = false),
        )
    }

    @Test
    fun `只有 READY 参与绑定与体积检查`() {
        val drafts = listOf(
            draft("1", DraftStatus.READY, "attachments/1.jpg"),
            draft("2", DraftStatus.FAILED),
            draft("3", DraftStatus.PREPARING),
            draft("4", DraftStatus.READY, "attachments/4.jpg"),
        )
        assertEquals(listOf("1", "4"), DraftRules.bindableIds(drafts))
        assertEquals(
            listOf("attachments/1.jpg", "attachments/4.jpg"),
            DraftRules.readyPaths(drafts),
        )
        assertTrue(DraftRules.isPreparing(drafts))
        assertTrue(DraftRules.hasReady(drafts))
        assertNull(DraftRules.sendLimitProblem(drafts) { 100L })
    }

    @Test
    fun `体积检查只算 READY`() {
        val drafts = listOf(
            draft("1", DraftStatus.READY),
            draft("2", DraftStatus.READY),
            draft("3", DraftStatus.FAILED),
        )
        val problem = DraftRules.sendLimitProblem(drafts) { d ->
            if (d.id == "2") ImageMath.SINGLE_MAX_BYTES + 1 else 1_000L
        }
        assertEquals(ImageMath.SendLimitProblem.SINGLE_TOO_LARGE, problem)
    }

    @Test
    fun `常量与文案口径一致`() {
        assertEquals(3, DraftRules.MAX_IMAGES)
    }
}
