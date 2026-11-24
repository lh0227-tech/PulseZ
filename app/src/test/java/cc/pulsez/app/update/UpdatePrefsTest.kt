package cc.pulsez.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.settings.SettingsStore
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
 * 更新状态的 DataStore 落盘（-A）。
 *
 * （键名 snake_case，发布后不改）、（24h 节流靠本地时间戳）、
 *（只在本机记「上次检查时间」，不收集统计）。
 *
 * Robolectric + Preferences DataStore，离线，不依真机（同 `ReadSettingsTest` 做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatePrefsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSettings() = runBlocking {
        // Robolectric 同一进程内 DataStore 是单例（按 name 缓存），用例之间共享文件；
        // 不清回默认，默认值断言会随执行顺序漂移（同 SettingsStoreTest 的处理）。
        SettingsStore(context).update { it.clear() }
    }

    @Test
    fun `默认值是没查过 没跳过 没提示过 且未过门槛`() = runBlocking {
        val snapshot = SettingsUpdatePrefs(SettingsStore(context)).snapshot()
        assertEquals("没查过", 0L, snapshot.lastCheckAt)
        assertEquals("没跳过任何版本", 0L, snapshot.ignoredVersionCode)
        assertEquals("没弹过提示", 0L, snapshot.pendingVersionCode)
        assertFalse("还没过门槛页", snapshot.agreed)
    }

    @Test
    fun `检查时间 跳过版本 提示版本都能落盘并被新实例读到`() = runBlocking {
        val store = SettingsStore(context)
        val prefs = SettingsUpdatePrefs(store)

        prefs.recordCheck(1_700_000_000_000L)
        prefs.recordPendingVersion(8L)
        prefs.setIgnoredVersion(7L)

        // 新实例 = 杀进程后重新打开 App：三个值都要记得（键名发布后不改）。
        val reloaded = SettingsUpdatePrefs(SettingsStore(context)).snapshot()
        assertEquals(1_700_000_000_000L, reloaded.lastCheckAt)
        assertEquals(8L, reloaded.pendingVersionCode)
        assertEquals(7L, reloaded.ignoredVersionCode)
    }

    @Test
    fun `失败也写检查时间（同一个 setter）`() = runBlocking {
        val prefs = SettingsUpdatePrefs(SettingsStore(context))
        // 检查失败时仓库调用同一个 recordCheck：下次启动不会因为「没成功过」又立刻联网。
        prefs.recordCheck(42L)
        assertEquals(42L, SettingsUpdatePrefs(SettingsStore(context)).snapshot().lastCheckAt)
    }

    @Test
    fun `负数版本号按 0 处理 避免脏数据`() = runBlocking {
        val prefs = SettingsUpdatePrefs(SettingsStore(context))
        prefs.setIgnoredVersion(-3L)
        prefs.recordPendingVersion(-9L)
        val snapshot = SettingsUpdatePrefs(SettingsStore(context)).snapshot()
        assertEquals(0L, snapshot.ignoredVersionCode)
        assertEquals(0L, snapshot.pendingVersionCode)
    }

    @Test
    fun `同意协议后 agreed 变成 true`() = runBlocking {
        val store = SettingsStore(context)
        // ：协议版本升到 2，`agreed` 改判「已同意版本
        // 恰好等于当前版本」；原断言写死 version = 1，与 F 的新判据冲突，按 点名更新。
        store.agree(version = SettingsStore.AGREEMENT_VERSION, at = 1L)
        assertTrue(SettingsUpdatePrefs(store).snapshot().agreed)
    }

    @Test
    fun `旧版本同意记录不算同意 升级用户要重看协议`() = runBlocking {
        // 旧判断 `agreedVersion > 0` 会把升级用户当成已同意，导致他们既看不到
        // 新增的出网/清空说明，又直接放行新的后台检查（10 ）。
        SettingsStore(context).agree(version = 1, at = 1L)
        assertFalse(SettingsUpdatePrefs(SettingsStore(context)).snapshot().agreed)
    }

    @Test
    fun `降级安装不算同意 与门槛同一判据`() = runBlocking {
        // `needsAgreement` 用 `!=` 而不是 `<`：agreedVersion 比当前版本大（降级安装）同样要重新同意。
        SettingsStore(context).agree(version = SettingsStore.AGREEMENT_VERSION + 1, at = 1L)
        assertFalse(SettingsUpdatePrefs(SettingsStore(context)).snapshot().agreed)
    }
}
