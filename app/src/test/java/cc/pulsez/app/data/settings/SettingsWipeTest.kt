package cc.pulsez.app.data.settings

import android.app.Application
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
 * 「清空所有数据」里的设置清空。
 *
 * 口径：`wipeUserData` 后 `Settings` 必须**逐键等于默认值**，唯一例外是协议同意记录
 * （版本 + 时间）按原值保留——用 data class 相等做「其余每个键都回默认」的断言，
 * 不是只断言「会删」清单里出现的那几项（漏键正是 03 的来源）。
 *
 * 环境：Robolectric + 真实 Preferences DataStore；`@Config(application = Application::class)`
 * 换掉 `PulsezApp`，避免它的后台「凭据迁移 / 更新检查」在测试期间改设置（ 测试同做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsWipeTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSettings() = runBlocking {
        // Robolectric 同一进程内 DataStore 是单例（按 name 缓存），用例之间共享文件。
        SettingsStore(context).update { it.clear() }
    }

    @Test
    fun wipeUserDataKeepsOnlyAgreement() = runBlocking {
        val store = SettingsStore(context)
        // 先造「本版已同意」+ 一批覆盖各分组的非默认值（设置/凭据/首用标记/识图探测/更新记录）。
        store.agree(SettingsStore.AGREEMENT_VERSION, at = 1L)
        store.setChatEndpoint("https://api.deepseek.com", "deepseek-flash")
        store.setEmbedEndpoint("https://open.bigmodel.cn/api/paas/v4", "embedding-3")
        store.setChatKeyPresent(true)
        store.setEmbedKeyPresent(true)
        store.setWindowTier(Settings.TIER_32K)
        store.setDailyLimit(100)
        store.setMonthlyLimit(200)
        store.setThinkingEnabled(false)
        store.setWebSearchEnabled(true)
        store.setReasoningEffort(Settings.EFFORT_MAX)
        store.setCreativeMode(true)
        store.setCreativeModeIntroSeen(true)
        store.setAutoRead(true)
        store.setReadRateX100(150)
        store.setReadVoiceId("voice-1")
        store.setReadIntroSeen(true)
        store.setImageIntroSeen(true)
        store.setVoiceIntroSeen(true)
        store.setUpdateCheckResult(1_700_000_000_000L)
        store.setUpdateIgnoredVersionCode(9L)
        store.setUpdatePendingVersionCode(8L)
        store.setVisionProbe("deepseek-flash", supported = true, at = 123L)

        store.wipeUserData()

        // data class 相等 = 其余每个键都回到默认值；协议同意两项按原值保留。
        assertEquals(
            Settings(agreedVersion = SettingsStore.AGREEMENT_VERSION, agreedAt = 1L),
            store.flow.first(),
        )

        // 关键几项再单独断言一次（失败时给出可读信息，不让整个断言只报「对象不相等」）。
        val after = store.flow.first()
        assertFalse("密钥标记要清", after.hasChatKey)
        assertFalse("找旧事密钥标记要清", after.hasEmbedKey)
        assertTrue("密钥归属要清", after.chatKeyHost.isEmpty())
        assertTrue("找旧事密钥归属要清", after.embedKeyHost.isEmpty())
        assertTrue("聊天地址要清", after.chatBaseUrl.isEmpty())
        assertTrue("聊天模型要清", after.chatModel.isEmpty())
        assertTrue("找旧事地址要清", after.embedBaseUrl.isEmpty())
        assertEquals("更新检查时间归零", 0L, after.updateLastCheckAt)
        assertEquals("忽略版本归零", 0L, after.updateIgnoredVersionCode)
        assertEquals("待提示版本归零", 0L, after.updatePendingVersionCode)
        assertFalse("语音首用标记归零", after.voiceIntroSeen)
        assertTrue("识图探测模型归零", after.visionProbeModel.isEmpty())
        assertTrue("识图探测结论归零", after.visionProbeSupported.isEmpty())
        assertEquals("识图探测时间归零", 0L, after.visionProbeAt)
        assertEquals("协议版本保留", SettingsStore.AGREEMENT_VERSION, after.agreedVersion)
        assertEquals("协议时间保留", 1L, after.agreedAt)
    }

    @Test
    fun wipeUserDataIsIdempotent() = runBlocking {
        val store = SettingsStore(context)
        store.agree(SettingsStore.AGREEMENT_VERSION, at = 5L)
        store.wipeUserData()
        store.wipeUserData()
        assertEquals(
            Settings(agreedVersion = SettingsStore.AGREEMENT_VERSION, agreedAt = 5L),
            store.flow.first(),
        )
    }

    @Test
    fun wipeUserDataWithoutAgreementStaysDefault() = runBlocking {
        val store = SettingsStore(context)
        store.setVoiceIntroSeen(true)
        store.wipeUserData()
        // 从没同意过：清完就是默认值（agreedVersion=0 不会被伪造成已同意）。
        assertEquals(Settings(), store.flow.first())
    }
}
