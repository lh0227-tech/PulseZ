package cc.pulsez.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.repo.UsageCategory
import cc.pulsez.app.data.repo.UsageStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分类记账。
 *
 * 核心不变量：**消息条数只由聊天记账累加**；图片走自己的分类，只加钱与「图片」小项。
 * 用 Robolectric + Preferences DataStore（与 `ReadSettingsTest` 同口径），离线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageCategoryTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val store: UsageStore
        get() = UsageStore(context)

    @Before
    fun resetUsage() = runBlocking {
        // DataStore 在 Robolectric 同一进程内是单例：不清掉，用例间会累加。
        store.clear()
    }

    @Test
    fun `图片分类不污染消息条数`() = runBlocking {
        store.record(0.010, 0.0) // 一次聊天
        store.record(0.002, 0.0, UsageCategory.VISION) // 一张图

        val snapshot = store.flow.first()
        assertEquals("消息条数只数聊天", 1, snapshot.todayMessages)
        assertEquals("图片也要计入总额", 0.012, snapshot.todayYuan, 1e-9)
        assertEquals("单列图片小项（预估）", 0.002, snapshot.todayImageYuan, 1e-9)
        assertEquals("月度同样单列", 0.002, snapshot.monthImageYuan, 1e-9)
        assertEquals(1, snapshot.monthMessages)
    }

    @Test
    fun `多张图只加钱不加消息`() = runBlocking {
        repeat(3) { store.record(0.002, 0.0, UsageCategory.VISION) }
        val snapshot = store.flow.first()
        assertEquals(0, snapshot.todayMessages)
        assertEquals(0.006, snapshot.todayImageYuan, 1e-9)
        assertEquals(0.006, snapshot.todayYuan, 1e-9)
    }

    @Test
    fun `省下比例只由聊天写 图片不覆盖`() = runBlocking {
        store.record(costYuan = 0.0, savedRatio = 0.9)
        assertEquals(0.9, store.flow.first().savedRatio, 1e-9)
        // 图片记账传 0，不能把上一轮的省下比例冲成 0。
        store.record(0.002, 0.0, UsageCategory.VISION)
        assertEquals(0.9, store.flow.first().savedRatio, 1e-9)
    }

    @Test
    fun `旧两参 record 保持旧行为 计一条消息`() = runBlocking {
        store.record(0.005, 0.5)
        val snapshot = store.flow.first()
        assertEquals(1, snapshot.todayMessages)
        assertEquals(0.005, snapshot.todayYuan, 1e-9)
        assertEquals("没有图片就不该出现图片小项", 0.0, snapshot.todayImageYuan, 1e-9)
        assertTrue(snapshot.savedRatio > 0.0)
    }

    @Test
    fun `负数或不合理金额不会记成负数`() = runBlocking {
        store.record(-1.0, 0.0)
        val snapshot = store.flow.first()
        assertEquals(0.0, snapshot.todayYuan, 1e-9)
        assertEquals(1, snapshot.todayMessages)
    }
}
