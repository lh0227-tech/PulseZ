package cc.pulsez.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.data.repo.UsageCategory
import cc.pulsez.app.data.repo.UsageStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 语音分类记账，与图片分类方式一致。
 *
 * 核心不变量：
 * - 消息条数只由聊天记账累加，语音**不加消息数**；
 * - 语音金额计入日/月总额，并单列「语音」小项（给用量页）；
 * - 语音不覆盖缓存省下比例；
 * - 老数据/负值不产生负数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageVoiceCategoryTest {

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
    fun `语音只加钱不加消息条数`() = runBlocking {
        store.record(0.010, 0.0) // 一次聊天
        store.record(0.014, 0.0, UsageCategory.VOICE) // 一条 60 秒语音

        val snapshot = store.flow.first()
        assertEquals("消息条数只数聊天", 1, snapshot.todayMessages)
        assertEquals(1, snapshot.monthMessages)
        assertEquals("语音也计入总额", 0.024, snapshot.todayYuan, 1e-9)
        assertEquals("单列语音小项（预估）", 0.014, snapshot.todayVoiceYuan, 1e-9)
        assertEquals(0.014, snapshot.monthVoiceYuan, 1e-9)
        assertEquals("不能污染图片小项", 0.0, snapshot.todayImageYuan, 1e-9)
    }

    @Test
    fun `多条语音也只加钱`() = runBlocking {
        repeat(3) { store.record(0.014, 0.0, UsageCategory.VOICE) }
        val snapshot = store.flow.first()
        assertEquals(0, snapshot.todayMessages)
        assertEquals(0, snapshot.monthMessages)
        assertEquals(0.042, snapshot.todayVoiceYuan, 1e-9)
        assertEquals(0.042, snapshot.todayYuan, 1e-9)
    }

    @Test
    fun `语音不覆盖缓存省下比例`() = runBlocking {
        store.record(costYuan = 0.0, savedRatio = 0.9)
        assertEquals(0.9, store.flow.first().savedRatio, 1e-9)
        // 语音记账传 0，不能把上一轮的省下比例冲成 0（只有聊天能写这个值）。
        store.record(0.014, 0.0, UsageCategory.VOICE)
        assertEquals(0.9, store.flow.first().savedRatio, 1e-9)
    }

    @Test
    fun `旧两参 record 保持聊天分类 语音小项为零`() = runBlocking {
        // 「旧重载兼容」：老调用点仍按聊天记账，新增的语音字段默认 0（老数据读出来也对）。
        store.record(0.005, 0.5)
        val snapshot = store.flow.first()
        assertEquals(1, snapshot.todayMessages)
        assertEquals(0.005, snapshot.todayYuan, 1e-9)
        assertEquals(0.0, snapshot.todayVoiceYuan, 1e-9)
        assertEquals(0.0, snapshot.monthVoiceYuan, 1e-9)
    }

    @Test
    fun `负数金额不会把语音记成负数`() = runBlocking {
        store.record(-1.0, 0.0, UsageCategory.VOICE)
        val snapshot = store.flow.first()
        assertEquals(0.0, snapshot.todayVoiceYuan, 1e-9)
        assertEquals(0.0, snapshot.todayYuan, 1e-9)
        assertEquals("语音永远不加消息数", 0, snapshot.todayMessages)
    }

    @Test
    fun `聊天图片语音三类互不干扰`() = runBlocking {
        store.record(0.001, 0.25) // 聊天
        store.record(0.002, 0.0, UsageCategory.VISION) // 图片
        store.record(0.014, 0.0, UsageCategory.VOICE) // 语音

        val snapshot = store.flow.first()
        assertEquals("只有聊天算一条消息", 1, snapshot.todayMessages)
        assertEquals(0.002, snapshot.todayImageYuan, 1e-9)
        assertEquals(0.014, snapshot.todayVoiceYuan, 1e-9)
        assertEquals(0.017, snapshot.todayYuan, 1e-9)
        assertEquals(0.25, snapshot.savedRatio, 1e-9)
    }
}
