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
 * 思考开关 / 强度 / 一次性提示的持久化（产品决定 ：
 * 思考默认开、low，引导在**第一次操作开关**时出现）。
 * Robolectric + Preferences DataStore，离线，不依真机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSettings() = runBlocking {
        // Robolectric 同一进程内 DataStore 是单例（按 name 缓存），用例之间共享同一个文件；
        // 不清回默认，默认值断言与「一次性提示」断言会随执行顺序漂移。
        SettingsStore(context).update { it.clear() }
    }

    @Test
    fun thinkingDefaultsOffAndAllValuesSurviveNewInstance() = runBlocking {
        val store = SettingsStore(context)
        val defaults = store.flow.first()
        assertFalse("深度思考默认关", defaults.thinkingEnabled)
        assertFalse("智能搜索默认关", defaults.webSearchEnabled)
        assertEquals("思考强度默认低", Settings.EFFORT_LOW, defaults.reasoningEffort)

        // 新实例 = 杀进程后重新打开 App：上次选择要记得。
        store.setThinkingEnabled(true)
        store.setWebSearchEnabled(true)
        store.setReasoningEffort(Settings.EFFORT_MAX)

        val reloaded = SettingsStore(context).flow.first()
        assertTrue(reloaded.thinkingEnabled)
        assertTrue(reloaded.webSearchEnabled)
        assertEquals(Settings.EFFORT_MAX, reloaded.reasoningEffort)
    }

    @Test
    fun invalidReasoningEffortFallsBackToLow() = runBlocking {
        val store = SettingsStore(context)
        // 手改 / 老版本脏数据不能把非法值发到服务端：一律按低。
        store.setReasoningEffort("ultra")
        assertEquals(Settings.EFFORT_LOW, SettingsStore(context).flow.first().reasoningEffort)
        assertEquals(Settings.EFFORT_LOW, normalizeReasoningEffort(null))
        assertEquals(Settings.EFFORT_HIGH, normalizeReasoningEffort(Settings.EFFORT_HIGH))
    }

    @Test
    fun turningThinkingOnFirstShowsHintAndOnlyOnce() = runBlocking {
        val store = SettingsStore(context)
        // 默认关 → 第一次手动从关切到开触发一次性提示，后续再关/再开不再重复出现。
        assertFalse("默认就是关", store.flow.first().thinkingEnabled)
        store.setThinkingEnabled(true)
        assertTrue("第一次操作（关→开）应出现提示", store.consumeThinkingHint())
        store.setThinkingEnabled(false)
        assertFalse("第二次操作（开→关）不再出现", store.consumeThinkingHint())
        assertFalse("安装后只出现一次：杀进程重进也不再出现", SettingsStore(context).consumeThinkingHint())
    }

    @Test
    fun changingEffortBeforeFirstToggleSuppressesHint() = runBlocking {
        val store = SettingsStore(context)
        // 条件 3：先在设置里改过强度，再第一次操作深度思考开关 → 不出现提示。
        assertFalse("默认没改过", store.flow.first().reasoningEffortTouched)
        store.setReasoningEffort(Settings.EFFORT_HIGH)
        assertTrue("改过之后要记住", store.flow.first().reasoningEffortTouched)
        assertFalse(store.consumeThinkingHint())
        assertFalse("改过强度就永久不再教", SettingsStore(context).consumeThinkingHint())
    }

    @Test
    fun creativeModeDefaultsOffAndSurvivesNewInstance() = runBlocking {
        val store = SettingsStore(context)
        val defaults = store.flow.first()
        // ：用户主动开启，默认关；首次说明默认没看过。
        assertFalse("创作模式默认关", defaults.creativeMode)
        assertFalse("创作模式首次说明默认没看过", defaults.creativeModeIntroSeen)

        store.setCreativeMode(true)
        store.setCreativeModeIntroSeen(true)

        // 新实例 = 杀进程后重新打开 App：开关与"已看过说明"都要记得。
        val reloaded = SettingsStore(context).flow.first()
        assertTrue(reloaded.creativeMode)
        assertTrue(reloaded.creativeModeIntroSeen)
    }

    @Test
    fun creativeModeIntroSeenPersistsIndependentlyOfTheSwitch() = runBlocking {
        val store = SettingsStore(context)
        // 看过说明之后关掉模式，再开回来不该重复弹说明（一次性说明）。
        store.setCreativeModeIntroSeen(true)
        store.setCreativeMode(true)
        store.setCreativeMode(false)

        val reloaded = SettingsStore(context).flow.first()
        assertFalse("关掉模式", reloaded.creativeMode)
        assertTrue("关掉模式不能把'已看过说明'一起清掉", reloaded.creativeModeIntroSeen)
    }

    @Test
    fun onlyDeepSeekEndpointsShowTheSwitches() {
        // ：统一按 host 域名边界判定（net/DeepSeekEndpoint.kt），不用裸子串。
        assertTrue(Settings(chatBaseUrl = "https://api.deepseek.com/v1").isDeepSeekProvider())
        assertTrue(Settings(chatBaseUrl = "api.deepseek.com").isDeepSeekProvider())
        assertTrue(Settings(chatBaseUrl = "https://API.DeepSeek.com").isDeepSeekProvider())
        assertTrue(Settings(chatBaseUrl = "https://xxx.deepseek.com/v1").isDeepSeekProvider())
        assertTrue(Settings(chatBaseUrl = "https://api.deepseek.com:443/v1").isDeepSeekProvider())
        assertFalse(Settings(chatBaseUrl = "https://api.openai.com/v1").isDeepSeekProvider())
        // 镜像/代理：名字里有 deepseek，但不是官方端点 → 不能显示成"能思考/能联网"的开关。
        assertFalse(Settings(chatBaseUrl = "https://deepseek-proxy.example.com/v1").isDeepSeekProvider())
        assertFalse(Settings(chatBaseUrl = "deepseek-proxy.example.com").isDeepSeekProvider())
        // 伪装域名：host 后缀是 evil.com。
        assertFalse(Settings(chatBaseUrl = "https://api.deepseek.com.evil.com/v1").isDeepSeekProvider())
        assertFalse(Settings(chatBaseUrl = "").isDeepSeekProvider())
    }
}
