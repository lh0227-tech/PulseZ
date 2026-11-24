package cc.pulsez.app.ui.chat

import cc.pulsez.app.media.DraftImage
import cc.pulsez.app.media.DraftRules
import cc.pulsez.app.media.DraftStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入条主按钮状态机回归（纯 JVM，不碰 Android / Compose）。
 *
 *
 * -（官方同一位置：发送 ↑ / 停止 ■ / 空态不放假语音）、
 *（PulseZ 落地版：空输入禁用箭头）、 方案 A；
 * -（停止按钮叫「停下」）；
 * - `cc.pulsez.app.media.DraftRules`（图片准备中禁止发送；失败态不阻塞打字发送）。
 */
class ComposerPrimaryStateTest {

    private fun ready(id: String = "ready") = DraftImage(
        id = id,
        localPath = "attachments/$id.jpg",
        status = DraftStatus.READY,
    )

    private fun preparing(id: String = "preparing") = DraftImage(
        id = "draft-$id",
        localPath = "",
        status = DraftStatus.PREPARING,
    )

    private fun failed(id: String = "failed") = DraftImage(
        id = id,
        localPath = "",
        status = DraftStatus.FAILED,
    )

    // ------------------------------------------------------------ 空 / 有字 / 有图

    @Test
    fun emptyInputIsDisabledArrow() {
        val state = ComposerPrimary.resolve(drafts = emptyList(), hasText = false, streaming = false)
        assertEquals(ComposerPrimaryIcon.ARROW_UP, state.icon)
        assertEquals(ComposerPrimaryAction.SEND, state.action)
        assertFalse("空输入不能点发送（也不能做成假语音按钮）", state.enabled)
        assertEquals(ComposerPrimaryCopy.SEND, state.contentDescription)
    }

    @Test
    fun textEnablesArrow() {
        val state = ComposerPrimary.resolve(drafts = emptyList(), hasText = true, streaming = false)
        assertEquals(ComposerPrimaryIcon.ARROW_UP, state.icon)
        assertEquals(ComposerPrimaryAction.SEND, state.action)
        assertTrue("有字可以发", state.enabled)
    }

    @Test
    fun readyImageWithoutTextStillEnablesArrow() {
        val state = ComposerPrimary.resolve(drafts = listOf(ready()), hasText = false, streaming = false)
        assertEquals(ComposerPrimaryIcon.ARROW_UP, state.icon)
        assertEquals(ComposerPrimaryAction.SEND, state.action)
        assertTrue("只有图片、没文字也能发", state.enabled)
    }

    @Test
    fun failedDraftDoesNotBlockSending() {
        val onlyFailed = ComposerPrimary.resolve(
            drafts = listOf(failed()),
            hasText = false,
            streaming = false,
        )
        assertFalse("失败态不占可发内容，所以空输入仍是禁用", onlyFailed.enabled)

        val failedWithText = ComposerPrimary.resolve(
            drafts = listOf(failed()),
            hasText = true,
            streaming = false,
        )
        assertTrue("失败态不阻塞打字发送", failedWithText.enabled)
    }

    // ------------------------------------------------------------ 图片准备中

    @Test
    fun preparingImageBlocksEvenWithText() {
        val state = ComposerPrimary.resolve(
            drafts = listOf(preparing()),
            hasText = true,
            streaming = false,
        )
        assertEquals(ComposerPrimaryIcon.ARROW_UP, state.icon)
        assertEquals(ComposerPrimaryAction.SEND, state.action)
        assertFalse("图片还在处理时不能发（现有提示：图片还在处理，等一下再发）", state.enabled)
        assertEquals("禁用时读屏名称不变，TalkBack 会额外念「已停用」", ComposerPrimaryCopy.SEND, state.contentDescription)
    }

    @Test
    fun preparingPlusReadyStillBlocked() {
        val state = ComposerPrimary.resolve(
            drafts = listOf(ready(), preparing()),
            hasText = true,
            streaming = false,
        )
        assertFalse("有一张还在处理就整体等它（与 DraftRules.sendBlock 一致）", state.enabled)
    }

    // ------------------------------------------------------------ 生成中

    @Test
    fun streamingShowsStopNoMatterWhatIsInTheBox() {
        val cases = listOf(
            Triple(emptyList<DraftImage>(), false, "空输入"),
            Triple(listOf(ready()), false, "有图"),
            Triple(listOf(preparing()), true, "处理中 + 有字"),
        )
        cases.forEach { (drafts, hasText, label) ->
            val state = ComposerPrimary.resolve(drafts, hasText, streaming = true)
            assertEquals("$label：生成中必须是停止图标", ComposerPrimaryIcon.STOP, state.icon)
            assertEquals("$label：生成中点下去是停止", ComposerPrimaryAction.STOP, state.action)
            assertTrue("$label：生成中停止永远可点", state.enabled)
            assertEquals("$label：读屏说「停下」", ComposerPrimaryCopy.STOP, state.contentDescription)
        }
    }

    // ------------------------------------------------------------ 与发送链路同一口径

    @Test
    fun enabledMatchesDraftRulesForEveryCombination() {
        val draftSets = listOf<List<DraftImage>>(
            emptyList(),
            listOf(ready()),
            listOf(preparing()),
            listOf(failed()),
            listOf(ready(), preparing()),
            listOf(failed(), ready()),
        )
        draftSets.forEach { drafts ->
            listOf(false, true).forEach { hasText ->
                val state = ComposerPrimary.resolve(drafts, hasText, streaming = false)
                val expected = DraftRules.sendBlock(drafts, hasText, streaming = false) == null
                assertTrue(
                    "按钮可点性必须与 DraftRules.sendBlock 一致：drafts=$drafts, hasText=$hasText",
                    expected == state.enabled,
                )
                assertEquals(ComposerPrimaryIcon.ARROW_UP, state.icon)
                assertEquals(ComposerPrimaryAction.SEND, state.action)
            }
        }
    }

    @Test
    fun copyIsPlainAndMatchesProjectStopWording() {
        assertEquals("发送", ComposerPrimaryCopy.SEND)
        assertEquals("停止生成按钮叫「停下」", "停下", ComposerPrimaryCopy.STOP)
        listOf(ComposerPrimaryCopy.SEND, ComposerPrimaryCopy.STOP).forEach { text ->
            assertTrue("读屏名称不能为空", text.isNotBlank())
        }
    }
}
