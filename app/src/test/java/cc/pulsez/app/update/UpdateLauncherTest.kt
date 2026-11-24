package cc.pulsez.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「去下载」出口：只跳浏览器、零新权限（-A）。
 *
 * Phase 1（普通 https 链接不需要权限，交给浏览器；不声明
 * `REQUEST_INSTALL_PACKAGES`、不做 App 内安装）、（打不开时要给人话出路）。
 *
 * Robolectric：只验证 Intent 形状与失败降级，不启动真实浏览器、不联网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateLauncherTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `https 下载地址构造成 ACTION_VIEW 并带 NEW_TASK`() {
        val url = "https://releases.pulsez.cc/stable/pulsez-0.5.1.apk"
        val intent = UpdateLauncher.downloadIntent(url)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(url, intent.data?.toString())
        assertTrue(
            "非 Activity Context 启动外部页面要 NEW_TASK，避免直接崩",
            (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )
    }

    @Test
    fun `非 https 或非法地址不构造 Intent`() {
        assertNull(UpdateLauncher.downloadIntent("http://releases.pulsez.cc/pulsez.apk"))
        assertNull(UpdateLauncher.downloadIntent("ftp://releases.pulsez.cc/pulsez.apk"))
        assertNull(UpdateLauncher.downloadIntent("not a url"))
        assertNull(UpdateLauncher.downloadIntent(""))
    }

    @Test
    fun `打开浏览器成功返回 true 并原样交出下载地址`() {
        var started: Intent? = null
        val recording = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                started = intent
            }
        }
        val url = "https://github.com/lh0227-tech/PulseZ/releases/download/v0.5.1/pulsez-0.5.1.apk"

        assertTrue(UpdateLauncher.openDownloadPage(recording, url))
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals(url, started?.data?.toString())
    }

    @Test
    fun `手机没有浏览器时不崩 返回 false 给人话`() {
        val noBrowser = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("no activity handles https")
            }
        }
        assertFalse(
            "ActivityNotFoundException 要变成 false，让 UI 显示可读提示",
            UpdateLauncher.openDownloadPage(noBrowser, "https://releases.pulsez.cc/pulsez.apk"),
        )
    }

    @Test
    fun `非法地址不会去启动任何 Activity`() {
        var called = false
        val recording = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                called = true
            }
        }
        assertFalse(UpdateLauncher.openDownloadPage(recording, "http://releases.pulsez.cc/pulsez.apk"))
        assertFalse("http 地址不该交给外部 App", called)
    }
}
