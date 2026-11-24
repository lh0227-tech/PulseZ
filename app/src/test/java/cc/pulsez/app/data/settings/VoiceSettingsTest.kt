package cc.pulsez.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 语音输入首用说明标记（「语音输入首次」）。
 *
 * 只测默认值 / 持久化 / 与其它首用标记互不影响；文案本身在下一节点的 UI 层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSettingsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSettings() = runBlocking {
        // Robolectric 同一进程内 DataStore 是单例（按 name 缓存），用例间共享文件。
        SettingsStore(context).update { it.clear() }
    }

    @Test
    fun voiceIntroDefaultsToNotSeen() = runBlocking {
        val defaults = SettingsStore(context).flow.first()
        assertFalse("语音首用说明默认没看过", defaults.voiceIntroSeen)
    }

    @Test
    fun voiceIntroSeenSurvivesNewInstance() = runBlocking {
        SettingsStore(context).setVoiceIntroSeen(true)
        // 新实例 = 杀进程后重新打开 App：不该重复弹说明。
        assertTrue(SettingsStore(context).flow.first().voiceIntroSeen)
    }

    @Test
    fun voiceIntroIsIndependentFromOtherIntroFlags() = runBlocking {
        val store = SettingsStore(context)
        store.setVoiceIntroSeen(true)

        val reloaded = SettingsStore(context).flow.first()
        assertTrue("语音说明已看过", reloaded.voiceIntroSeen)
        // 其它一次性说明不能被顺带标记（各自独立）。
        assertFalse(reloaded.readIntroSeen)
        assertFalse(reloaded.imageIntroSeen)
        assertFalse(reloaded.creativeModeIntroSeen)

        store.setReadIntroSeen(true)
        val again = SettingsStore(context).flow.first()
        assertTrue(again.readIntroSeen)
        assertTrue("朗读标记不影响语音标记", again.voiceIntroSeen)
    }

    @Test
    fun clearingSettingsResetsVoiceIntro() = runBlocking {
        val store = SettingsStore(context)
        store.setVoiceIntroSeen(true)
        store.update { it.clear() }
        assertFalse(SettingsStore(context).flow.first().voiceIntroSeen)
    }
}
