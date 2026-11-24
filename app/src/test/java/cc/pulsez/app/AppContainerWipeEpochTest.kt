package cc.pulsez.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 清空代际。
 *
 * 为什么要单独测 [AppContainer.wipeEpoch]：它是「清空后迟到写入」的唯一信号源——
 * `ChatViewModel` / `GroupChatViewModel` 订阅它取消在途生成，`ChatEngine` 与生成流程用
 * 它比对起点/终点。若它不递增（或第一帧就是脏值）， 的所有守卫都会静默失效。
 *
 * 用 Robolectric 只为拿一个可用的 `Context` 构造容器（`AppContainer` 的依赖全是 lazy，本测试
 * 除 @Before 复位 DataStore 外不读写任何数据；换掉 `PulsezApp` 避免启动迁移在后台干扰）。
 * 依据（同类写法）：`AppContainerCredentialGuardTest` 头部注释。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppContainerWipeEpochTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // DataStore 在同一 Robolectric 进程内是单例，用例之间共享 → 清回默认（与既有容器测试一致）。
        runBlocking { SettingsStore(context).update { it.clear() } }
    }

    @Test
    fun `初始代际为 0 且每次清空通知只自增 1`() = runBlocking {
        val container = AppContainer(context)
        assertEquals("本次进程还没清空过时必须是 0（不是时间戳、不持久化）", 0L, container.wipeEpoch.value)

        container.notifyUserDataWiped()
        assertEquals(1L, container.wipeEpoch.value)

        container.notifyUserDataWiped()
        assertEquals("每次清空+1，单调递增（迟到写入靠不相等判断）", 2L, container.wipeEpoch.value)

        assertNotEquals("清空后新旧代际必须可区分", 0L, container.wipeEpoch.value)
    }

    @Test
    fun `订阅方能在通知后立刻看到新代际`() = runBlocking {
        val container = AppContainer(context)
        // 先起收集再通知（模拟 ChatViewModel 的 init 订阅）；`first` 会等代际真正变化的那一帧。
        val waiter = async(Dispatchers.Default) { container.wipeEpoch.first { it > 0L } }
        container.notifyUserDataWiped()
        assertEquals("通知后订阅方必须收到新代际（不能丢唤醒）", 1L, withTimeout(5_000) { waiter.await() })
    }

    @Test
    fun `两个容器实例的代际互不影响`() = runBlocking {
        // 守卫只看自己容器的代际：测试/多容器场景（Robolectric 每个用例新建容器）不能串。
        val first = AppContainer(context)
        val second = AppContainer(context)
        first.notifyUserDataWiped()
        assertEquals(1L, first.wipeEpoch.value)
        assertEquals(0L, second.wipeEpoch.value)
    }
}
