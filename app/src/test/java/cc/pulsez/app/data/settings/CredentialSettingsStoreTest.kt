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
 * 密钥绑定字段的持久化。
 *
 * 覆盖四个新键（`chat_key_host` / `embed_key_host` / `chat_key_reset_after_upgrade` /
 * `embed_key_reset_after_upgrade`）与保存协议的「一次 edit」语义：
 * ① 失效（hasKey=false + host 清空，地址/模型不动）、③ 绑定（地址/模型/host/hasKey 一起写）、
 * 迁移标记、清除 / 断开。
 *
 * 应用类换成普通 `Application`：避免 `PulsezApp` 启动迁移在本类刻意构造脏状态时后台跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CredentialSettingsStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSettings() = runBlocking {
        SettingsStore(context).update { it.clear() }
    }

    @Test
    fun `新字段默认空 且新实例能读回`() = runBlocking {
        val store = SettingsStore(context)
        val defaults = store.flow.first()
        assertEquals("默认没有 host", "", defaults.chatKeyHost)
        assertEquals("默认没有 host", "", defaults.embedKeyHost)
        assertFalse("默认没有升级提示", defaults.chatKeyResetAfterUpgrade)
        assertFalse("默认没有升级提示", defaults.embedKeyResetAfterUpgrade)
        assertFalse(defaults.hasChatKey)
        assertFalse(defaults.hasEmbedKey)

        store.setChatBoundEndpoint("https://b.example.com/v1", "model-b", "https://b.example.com")
        store.setEmbedBoundEndpoint("https://open.bigmodel.cn/api/paas/v4", "embedding-3", "https://open.bigmodel.cn")

        // 新实例 = 杀进程后重新打开 App：绑定与 host 都要记得。
        val reloaded = SettingsStore(context).flow.first()
        assertTrue(reloaded.hasChatKey)
        assertEquals("https://b.example.com/v1", reloaded.chatBaseUrl)
        assertEquals("model-b", reloaded.chatModel)
        assertEquals("https://b.example.com", reloaded.chatKeyHost)
        assertTrue(reloaded.hasEmbedKey)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", reloaded.embedBaseUrl)
        assertEquals("embedding-3", reloaded.embedModel)
        assertEquals("https://open.bigmodel.cn", reloaded.embedKeyHost)
    }

    @Test
    fun `保存协议第①步 只让绑定失效 不动地址模型`() = runBlocking {
        val store = SettingsStore(context)
        store.setChatBoundEndpoint("https://b.example.com/v1", "model-b", "https://b.example.com")

        store.invalidateChatKeyBinding()

        val persisted = store.flow.first()
        assertFalse("hasKey 先置 false", persisted.hasChatKey)
        assertEquals("host 先清空", "", persisted.chatKeyHost)
        assertEquals("地址不能被第①步清掉", "https://b.example.com/v1", persisted.chatBaseUrl)
        assertEquals("模型不能被第①步清掉", "model-b", persisted.chatModel)

        store.invalidateEmbedKeyBinding()
        val embed = store.flow.first()
        assertFalse(embed.hasEmbedKey)
        assertEquals("", embed.embedKeyHost)
    }

    @Test
    fun `迁移标记一次 edit 置上 可被知道了清掉`() = runBlocking {
        val store = SettingsStore(context)
        store.setChatEndpoint("https://a.example.com/v1", "model-a")
        store.setChatKeyPresent(true)

        store.resetLegacyChatKeyBinding()

        var persisted = store.flow.first()
        assertFalse("迁移后旧密钥不可用", persisted.hasChatKey)
        assertEquals("host 不回填", "", persisted.chatKeyHost)
        assertTrue("要置一次性提示", persisted.chatKeyResetAfterUpgrade)
        assertEquals("地址保留（那是用户填的配置）", "https://a.example.com/v1", persisted.chatBaseUrl)

        store.setChatKeyResetAfterUpgrade(false)
        persisted = store.flow.first()
        assertFalse("「知道了」清标记", persisted.chatKeyResetAfterUpgrade)

        store.setEmbedKeyPresent(true)
        store.resetLegacyEmbedKeyBinding()
        assertTrue(store.flow.first().embedKeyResetAfterUpgrade)
        store.setEmbedKeyResetAfterUpgrade(false)
        assertFalse(store.flow.first().embedKeyResetAfterUpgrade)
    }

    @Test
    fun `清除密钥 让绑定失效并清掉升级提示`() = runBlocking {
        val store = SettingsStore(context)
        store.setChatBoundEndpoint("https://b.example.com/v1", "model-b", "https://b.example.com")
        store.setChatKeyResetAfterUpgrade(true)

        store.clearChatKeyBinding()

        val persisted = store.flow.first()
        assertFalse(persisted.hasChatKey)
        assertEquals("", persisted.chatKeyHost)
        assertFalse("清除后不再提示要重填", persisted.chatKeyResetAfterUpgrade)
        assertEquals("地址/模型不被清除密钥顺带清掉", "https://b.example.com/v1", persisted.chatBaseUrl)
        assertEquals("model-b", persisted.chatModel)
    }

    @Test
    fun `停止使用找旧事 地址模型绑定标记全清`() = runBlocking {
        val store = SettingsStore(context)
        store.setEmbedBoundEndpoint(
            "https://open.bigmodel.cn/api/paas/v4",
            "embedding-3",
            "https://open.bigmodel.cn",
        )
        store.setEmbedKeyResetAfterUpgrade(true)

        store.clearEmbedServiceConfig()

        val persisted = store.flow.first()
        assertEquals("地址要清空（ChatEngine 据此零出网）", "", persisted.embedBaseUrl)
        assertEquals("模型要清空", "", persisted.embedModel)
        assertFalse(persisted.hasEmbedKey)
        assertEquals("", persisted.embedKeyHost)
        assertFalse("断开后不再提示要重填", persisted.embedKeyResetAfterUpgrade)
    }
}
