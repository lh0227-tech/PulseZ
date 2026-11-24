package cc.pulsez.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.ui.copy.UpdateCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * 「浏览器打不开 → 复制下载地址」的行为与文案（ 13； /）。
 *
 * Robolectric：真实走 `ClipboardManager`（系统服务）与 `Toast`，断言写进去的文本与标签；
 * 不依赖真机、不依赖系统外部状态（用例内先清空剪贴板）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadCopyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val clipboard: ClipboardManager
        get() = context.getSystemService(ClipboardManager::class.java)

    private fun clearClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    @Test
    fun `复制地址写入系统剪贴板 并Toast确认`() {
        clearClipboard()
        val url = "https://releases.pulsez.cc/stable/pulsez-0.5.2.apk"

        assertTrue("非空地址应返回 true", copyDownloadUrl(context, url))

        val clip = clipboard.primaryClip
        assertNotNull("剪贴板必须有内容", clip)
        assertEquals("剪贴板文本逐字等于清单里的 apkUrl", url, clip!!.getItemAt(0).text.toString())
        assertEquals(
            "剪贴板标签来自 UpdateCopy（Android 13+ 系统浮层会显示它）",
            UpdateCopy.CLIP_LABEL,
            clipboard.primaryClipDescription?.label?.toString(),
        )
        assertEquals("复制成功后给一句确认", UpdateCopy.URL_COPIED, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `空地址或纯空白不写剪贴板`() {
        clearClipboard()
        val before = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

        assertFalse("空串不写、返回 false", copyDownloadUrl(context, ""))
        assertFalse("纯空白不写、返回 false", copyDownloadUrl(context, "   "))
        assertEquals(
            "空地址不得改动剪贴板",
            before,
            clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
        )
    }

    @Test
    fun `失败出路文案与行为一致 不再是无入口指令`() {
        // 逐字锁定（改字必须两边一次改齐，避免文案与界面行为漂移）。
        assertEquals(
            "这台手机没找到能打开网页的浏览器。下载地址在下面，复制后粘到浏览器里打开。",
            UpdateCopy.BROWSER_UNAVAILABLE,
        )
        assertTrue(UpdateCopy.BROWSER_UNAVAILABLE.contains("下载地址"))
        assertTrue(UpdateCopy.BROWSER_UNAVAILABLE.contains("下面"))
        assertTrue(UpdateCopy.BROWSER_UNAVAILABLE.contains("复制"))
        assertEquals("复制地址", UpdateCopy.COPY_URL)
        assertEquals("地址已复制", UpdateCopy.URL_COPIED)
        assertEquals("PulseZ 下载地址", UpdateCopy.CLIP_LABEL)
        assertFalse("不得承诺自动安装", UpdateCopy.BROWSER_UNAVAILABLE.contains("安装"))
    }
}
