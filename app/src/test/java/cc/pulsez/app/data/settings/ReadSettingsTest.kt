package cc.pulsez.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * 自动朗读设置的默认值 / 持久化 / 首用标记。
 *
 * （开关默认关、语速 0.8–1.5、音色、首次说明）；
 * （首用提示只弹一次用 DataStore 标记，直接断言 DataStore）。
 *
 * Robolectric + Preferences DataStore，离线，不依真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadSettingsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSettings() = runBlocking {
        // Robolectric 同一进程内 DataStore 是单例（按 name 缓存），用例间共享文件；
        // 不清回默认，默认值断言会随执行顺序漂移（同 SettingsStoreTest 的处理）。
        SettingsStore(context).update { it.clear() }
    }

    @Test
    fun autoReadDefaultsOffWithNormalRateAndSystemVoice() = runBlocking {
        val defaults = SettingsStore(context).flow.first()
        assertFalse("自动朗读默认关", defaults.autoRead)
        assertEquals("语速默认 1.0（正常）", Settings.READ_RATE_DEFAULT_X100, defaults.readRateX100)
        assertEquals("音色默认系统默认（空串）", "", defaults.readVoiceId)
        assertFalse("首用说明默认没看过", defaults.readIntroSeen)
    }

    @Test
    fun valuesSurviveNewInstance() = runBlocking {
        val store = SettingsStore(context)
        store.setAutoRead(true)
        store.setReadRateX100(150)
        store.setReadVoiceId("zh-cn-x-offline")
        store.setReadIntroSeen(true)

        // 新实例 = 杀进程后重新打开 App：开关 / 语速 / 音色 / 已看过说明都要记得。
        val reloaded = SettingsStore(context).flow.first()
        assertTrue(reloaded.autoRead)
        assertEquals(150, reloaded.readRateX100)
        assertEquals("zh-cn-x-offline", reloaded.readVoiceId)
        assertTrue(reloaded.readIntroSeen)
    }

    @Test
    fun rateOutsideRangeIsClamped() = runBlocking {
        val store = SettingsStore(context)
        // 脏数据/手改值不能把语速推到 0.01x 或 9.99x：夹到 0.8–1.5。
        store.setReadRateX100(999)
        assertEquals(Settings.READ_RATE_MAX_X100, SettingsStore(context).flow.first().readRateX100)
        store.setReadRateX100(-5)
        assertEquals(Settings.READ_RATE_MIN_X100, SettingsStore(context).flow.first().readRateX100)
        store.setReadRateX100(120)
        assertEquals(120, SettingsStore(context).flow.first().readRateX100)
    }

    @Test
    fun introSeenPersistsIndependentlyOfTheSwitch() = runBlocking {
        val store = SettingsStore(context)
        // 首用说明只弹一次：用户点了「先不用」也算看过；之后再把开关打开不该重复弹。
        store.setReadIntroSeen(true)
        store.setAutoRead(false)
        assertTrue("先不用之后不能再弹", SettingsStore(context).flow.first().readIntroSeen)
        assertFalse(SettingsStore(context).flow.first().autoRead)

        store.setAutoRead(true)
        val reloaded = SettingsStore(context).flow.first()
        assertTrue(reloaded.autoRead)
        assertTrue("开关打开不重置已看过标记", reloaded.readIntroSeen)
    }

    @Test
    fun normalizeReadRateHandlesNullAndBounds() {
        assertEquals(Settings.READ_RATE_DEFAULT_X100, normalizeReadRate(null))
        assertEquals(Settings.READ_RATE_MIN_X100, normalizeReadRate(1))
        assertEquals(Settings.READ_RATE_MAX_X100, normalizeReadRate(1_000))
        assertEquals(120, normalizeReadRate(120))
    }
}
