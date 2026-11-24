package cc.pulsez.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 识图设置：首用标记 + 能力探测缓存 + 入口三态。
 *
 * （门禁）、（只弹一次）； /；
 * （非 DeepSeek 隐藏 / 非 vision 模型置灰说明）。
 *
 * Robolectric + Preferences DataStore，离线，不依真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageSettingsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSettings() = runBlocking {
        // DataStore 在 Robolectric 同一进程内是单例：不清回默认，断言会随执行顺序漂移。
        SettingsStore(context).update { it.clear() }
    }

    @Test
    fun defaultsAreUnseenAndNoProbe() = runBlocking {
        val defaults = SettingsStore(context).flow.first()
        assertFalse("首用说明默认没看过", defaults.imageIntroSeen)
        assertNull("没探过 = 结论未知", defaults.visionProbeResult())
        assertEquals("", defaults.visionProbeModel)
        assertEquals(0L, defaults.visionProbeAt)
    }

    @Test
    fun introSeenPersists() = runBlocking {
        SettingsStore(context).setImageIntroSeen(true)
        assertTrue("杀进程再开也要记得（只弹一次）", SettingsStore(context).flow.first().imageIntroSeen)
    }

    @Test
    fun visionProbePersistsAndSurvivesNewInstance() = runBlocking {
        SettingsStore(context).setVisionProbe(model = "deepseek-flash", supported = true, at = 123L)
        val reloaded = SettingsStore(context).flow.first()
        assertTrue(reloaded.visionProbeResult() == true)
        assertEquals("deepseek-flash", reloaded.visionProbeModel)
        assertEquals(123L, reloaded.visionProbeAt)

        SettingsStore(context).clearVisionProbe()
        val cleared = SettingsStore(context).flow.first()
        assertNull("清掉缓存后按模型 id 规则回退", cleared.visionProbeResult())
    }

    // ------------------------------------------------------------ 入口三态（纯函数）

    @Test
    fun nonDeepSeekEndpointHidesEntry() {
        val settings = Settings(
            chatBaseUrl = "https://api.openai.com/v1",
            chatModel = "deepseek-flash",
        )
        assertEquals(ImageEntryState.HIDDEN_OTHER_PROVIDER, settings.imageEntryState())
        assertFalse("第三方端点发图行为未核实，不发", settings.visionAvailable())
    }

    @Test
    fun deepSeekWithFlashIsEnabledByModelIdFallback() {
        val settings = Settings(
            chatBaseUrl = "https://api.deepseek.com/v1",
            chatModel = "deepseek-flash",
        )
        assertEquals(ImageEntryState.ENABLED, settings.imageEntryState())
    }

    @Test
    fun deepSeekWithProIsDisabled() {
        val settings = Settings(
            chatBaseUrl = "https://api.deepseek.com/v1",
            chatModel = "deepseek-v4-pro",
        )
        assertEquals(ImageEntryState.DISABLED_MODEL, settings.imageEntryState())
    }

    @Test
    fun probeResultOverridesModelIdForSameModel() {
        // 服务端明确说 pro 不能看图（input_modalities=["text"]）→ 即使 id 规则也不会放行；
        // 反过来 flash 被探成 false 时同样听服务端的。
        val pro = Settings(
            chatBaseUrl = "https://api.deepseek.com/v1",
            chatModel = "deepseek-v4-pro",
            visionProbeModel = "deepseek-v4-pro",
            visionProbeSupported = "false",
        )
        assertEquals(ImageEntryState.DISABLED_MODEL, pro.imageEntryState())

        val flashProbedOff = Settings(
            chatBaseUrl = "https://api.deepseek.com/v1",
            chatModel = "deepseek-flash",
            visionProbeModel = "deepseek-flash",
            visionProbeSupported = "false",
        )
        assertEquals(ImageEntryState.DISABLED_MODEL, flashProbedOff.imageEntryState())
    }

    @Test
    fun staleProbeForAnotherModelFallsBackToModelId() {
        // 缓存是旧模型的结论：用户换成 flash 后应回退规则（可用），不能拿旧结论卡住。
        val settings = Settings(
            chatBaseUrl = "https://api.deepseek.com/v1",
            chatModel = "deepseek-flash",
            visionProbeModel = "deepseek-v4-pro",
            visionProbeSupported = "false",
        )
        assertEquals(ImageEntryState.ENABLED, settings.imageEntryState())
    }

    @Test
    fun dirtyProbeValueIsTreatedAsUnknown() {
        val settings = Settings(
            chatBaseUrl = "https://api.deepseek.com/v1",
            chatModel = "deepseek-flash",
            visionProbeModel = "deepseek-flash",
            visionProbeSupported = "yes",
        )
        assertNull(settings.visionProbeResult())
        assertEquals(ImageEntryState.ENABLED, settings.imageEntryState())
    }
}
