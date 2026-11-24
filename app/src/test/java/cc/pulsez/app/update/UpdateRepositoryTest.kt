package cc.pulsez.app.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新状态机的节流、失败处理、忽略版本、手动检查和清空代际守卫。
 *
 * 清空期间，正在等待网络或排在互斥锁队列中的检查都应作废。测试使用纯 JVM 内存 fake，
 * 不依赖 DataStore、MockWebServer 或真机。
 */
class UpdateRepositoryTest {

    private companion object {
        /** 固定时钟：边界断言不随真实时间漂移。 */
        const val NOW = 1_700_000_000_000L
        const val MINUTE = 60L * 1000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR
    }

    // ---------------------------------------------------------------- 自动检查的前置条件

    @Test
    fun `未通过首启门槛页不联网`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = false)
            fetcher.manifest = TestManifests.manifest()
        }
        harness.repository.checkOnLaunch()
        assertEquals("门槛页没完成不发请求，避免首启就联网", 0, harness.fetcher.calls)
        assertEquals(UpdateUiState.Idle, harness.repository.state.value)
    }

    @Test
    fun `自动检查关闭时（debug 构建）不联网但手动检查仍可用`() = runBlocking {
        val harness = Harness(autoCheckEnabled = false).apply {
            fetcher.manifest = TestManifests.manifest()
        }
        harness.repository.checkOnLaunch()
        assertEquals("debug 构建不自动查", 0, harness.fetcher.calls)

        // 手动检查不受这个开关影响。
        harness.repository.checkNow()
        assertEquals("手动检查在 debug 下仍可用", 1, harness.fetcher.calls)
    }

    // ---------------------------------------------------------------- 24h 节流

    @Test
    fun `从没查过会检查并在发现新版时置 Available 且只提示一次`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.1")
        val harness = Harness().apply { fetcher.manifest = manifest }
        harness.repository.checkOnLaunch()

        assertEquals(1, harness.fetcher.calls)
        assertEquals(listOf(NOW), harness.prefs.checks)
        assertEquals(UpdateUiState.Available(manifest), harness.repository.state.value)
        assertEquals("启动提示通道拿到新版", manifest, harness.repository.autoPrompt.value)
        assertEquals("提示前先落盘 pending，进程被杀也不会重复打扰", listOf(8L), harness.prefs.pending)
    }

    @Test
    fun `距上次不足 24h 不请求`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, lastCheckAt = NOW - HOUR)
            fetcher.manifest = TestManifests.manifest()
        }
        harness.repository.checkOnLaunch()
        assertEquals("23 小时不查", 0, harness.fetcher.calls)
        assertEquals(UpdateUiState.Idle, harness.repository.state.value)
    }

    @Test
    fun `满 24h 会请求`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, lastCheckAt = NOW - DAY)
            fetcher.manifest = TestManifests.manifest(versionCode = 7L)
        }
        harness.repository.checkOnLaunch()
        assertEquals("恰好 24h 到期，才发起请求", 1, harness.fetcher.calls)
    }

    @Test
    fun `超过 24h 会请求`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, lastCheckAt = NOW - 25 * HOUR)
            fetcher.manifest = TestManifests.manifest(versionCode = 7L)
        }
        harness.repository.checkOnLaunch()
        assertEquals(1, harness.fetcher.calls)
    }

    @Test
    fun `时钟回拨视为到期 不因改时间卡死`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, lastCheckAt = NOW + HOUR)
            fetcher.manifest = TestManifests.manifest(versionCode = 7L)
        }
        harness.repository.checkOnLaunch()
        assertEquals("上次时间在未来时仍需检查并重写时间戳", 1, harness.fetcher.calls)
        assertEquals(listOf(NOW), harness.prefs.checks)
    }

    @Test
    fun `查过一次之后同一时间点不再请求`() = runBlocking {
        val harness = Harness().apply { fetcher.manifest = TestManifests.manifest(versionCode = 7L) }
        harness.repository.checkOnLaunch()
        harness.repository.checkOnLaunch()
        assertEquals("刚查过（lastCheckAt=now）→ 第二次直接跳过", 1, harness.fetcher.calls)
    }

    // ---------------------------------------------------------------- 自动检查失败（静默）

    @Test
    fun `自动检查失败也要写时间戳 且状态不变不提示`() = runBlocking {
        val harness = Harness() // fetcher.manifest 留在 null = 所有源都失败
        harness.repository.checkOnLaunch()

        assertEquals("失败也写 lastCheckAt：否则每次冷启动都会再打一次", listOf(NOW), harness.prefs.checks)
        assertEquals("自动失败完全静默：状态不变", UpdateUiState.Idle, harness.repository.state.value)
        assertNull("自动失败不弹提示", harness.repository.autoPrompt.value)
        assertTrue(harness.prefs.pending.isEmpty())
    }

    @Test
    fun `自动检查失败不覆盖已有的已是最新状态`() = runBlocking {
        val harness = Harness().apply { fetcher.manifest = TestManifests.manifest(versionCode = 7L) }
        harness.repository.checkOnLaunch()
        assertTrue(harness.repository.state.value is UpdateUiState.UpToDate)

        // 下一个 24h 周期：更新源挂了，状态保持「已是最新」，不闪失败。
        harness.prefs.snapshotValue = harness.prefs.snapshotValue.copy(lastCheckAt = NOW - 25 * HOUR)
        harness.fetcher.manifest = null
        harness.repository.checkOnLaunch()
        assertTrue(harness.repository.state.value is UpdateUiState.UpToDate)
    }

    // ---------------------------------------------------------------- versionCode 比较

    @Test
    fun `清单版本等于当前版本 已是最新`() = runBlocking {
        val harness = Harness(installed = 8L).apply { fetcher.manifest = TestManifests.manifest(versionCode = 8L) }
        harness.repository.checkOnLaunch()
        assertEquals(UpdateUiState.UpToDate(NOW), harness.repository.state.value)
        assertNull(harness.repository.autoPrompt.value)
    }

    @Test
    fun `清单版本小于当前版本 不降级`() = runBlocking {
        val harness = Harness(installed = 8L).apply { fetcher.manifest = TestManifests.manifest(versionCode = 7L) }
        harness.repository.checkOnLaunch()
        assertEquals("回滚指针不提示升级", UpdateUiState.UpToDate(NOW), harness.repository.state.value)
    }

    // ---------------------------------------------------------------- 忽略版本

    @Test
    fun `点过稍后的版本不再自动提示 状态显示已跳过`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L)
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = manifest
        }
        harness.repository.checkOnLaunch()
        // 07：版本事实必须如实保留，不能回落成「已是最新」。
        assertEquals(UpdateUiState.Ignored(manifest, NOW), harness.repository.state.value)
        assertNull("同版本不再自动弹提示", harness.repository.autoPrompt.value)
        assertTrue("被跳过的版本不写 pending", harness.prefs.pending.isEmpty())
    }

    @Test
    fun `checkOnLaunch 命中忽略版本 状态带清单与检查时间`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = manifest
        }
        harness.repository.checkOnLaunch()
        val state = harness.repository.state.value
        assertTrue("命中忽略版本 → Ignored，不是 UpToDate", state is UpdateUiState.Ignored)
        assertEquals(manifest, (state as UpdateUiState.Ignored).manifest)
        assertEquals(NOW, state.checkedAt)
    }

    @Test
    fun `版本号变大后重新提示 即使旧版本被跳过`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = TestManifests.manifest(versionCode = 9L, versionName = "0.5.2")
        }
        harness.repository.checkOnLaunch()
        assertTrue(harness.repository.state.value is UpdateUiState.Available)
        assertNotNull("新版本号要重新提示", harness.repository.autoPrompt.value)
        assertEquals(listOf(9L), harness.prefs.pending)
    }

    @Test
    fun `同一版本提示过一次后不再弹启动提示`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, pendingVersionCode = 8L)
            fetcher.manifest = TestManifests.manifest(versionCode = 8L)
        }
        harness.repository.checkOnLaunch()
        assertTrue("行上仍然显示有新版本", harness.repository.state.value is UpdateUiState.Available)
        assertNull("但同版本只打扰一次，不再弹启动提示", harness.repository.autoPrompt.value)
        assertTrue(harness.prefs.pending.isEmpty())
    }

    @Test
    fun `标记稍后写入忽略版本 清掉启动提示并显示已跳过`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L)
        val harness = Harness().apply { fetcher.manifest = manifest }
        harness.repository.checkOnLaunch()
        assertTrue(harness.repository.state.value is UpdateUiState.Available)

        harness.repository.markIgnored(manifest)

        assertEquals(listOf(8L), harness.prefs.ignored)
        assertNull(harness.repository.autoPrompt.value)
        // 07：点「稍后」后如实显示「有新版本 X · 已跳过」，不得回落成「已是最新」。
        assertEquals(UpdateUiState.Ignored(manifest, NOW), harness.repository.state.value)
    }

    @Test
    fun `标记稍后只对同一版本生效`() = runBlocking {
        val older = TestManifests.manifest(versionCode = 8L, versionName = "0.5.1")
        val newer = TestManifests.manifest(versionCode = 9L, versionName = "0.5.2")
        val harness = Harness().apply { fetcher.manifest = older }
        harness.repository.checkOnLaunch()
        assertTrue(harness.repository.state.value is UpdateUiState.Available)

        // 当前提示的是 8，用户点「稍后」传的却是 9 → 状态保持不变（只记 9 的忽略标记）。
        harness.repository.markIgnored(newer)

        assertEquals(listOf(9L), harness.prefs.ignored)
        assertNull(harness.repository.autoPrompt.value)
        assertEquals("不是同一版本时不改行状态", UpdateUiState.Available(older), harness.repository.state.value)
    }

    @Test
    fun `已跳过时手动再查 变成有新版本且忽略标记保留`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = manifest
        }
        harness.repository.checkOnLaunch()
        assertTrue(harness.repository.state.value is UpdateUiState.Ignored)

        val result = harness.repository.checkNow()

        assertTrue("手动再查如实返回 Available", result is UpdateCheckResult.Available)
        assertEquals(UpdateUiState.Available(manifest), harness.repository.state.value)
        assertTrue("忽略标记不被手动检查清掉", harness.prefs.ignored.isEmpty())
        assertEquals(8L, harness.prefs.snapshotValue.ignoredVersionCode)
    }

    @Test
    fun `consumeAutoPrompt 只关提示不写忽略`() = runBlocking {
        val harness = Harness().apply { fetcher.manifest = TestManifests.manifest(versionCode = 8L) }
        harness.repository.checkOnLaunch()
        assertNotNull(harness.repository.autoPrompt.value)

        harness.repository.consumeAutoPrompt()

        assertNull(harness.repository.autoPrompt.value)
        assertTrue("点外面或返回不算稍后，不写忽略标记", harness.prefs.ignored.isEmpty())
    }

    // ---------------------------------------------------------------- 手动检查

    @Test
    fun `手动检查不受 24h 节流限制`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, lastCheckAt = NOW - MINUTE)
            fetcher.manifest = TestManifests.manifest(versionCode = 8L)
        }
        val result = harness.repository.checkNow()

        assertEquals("手动点必须真去查", 1, harness.fetcher.calls)
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals(listOf(NOW), harness.prefs.checks)
        assertTrue(harness.repository.state.value is UpdateUiState.Available)
    }

    @Test
    fun `手动检查时状态先进入 Checking`() = runBlocking {
        val harness = Harness().apply { fetcher.manifest = TestManifests.manifest(versionCode = 8L) }
        var observed: UpdateUiState? = null
        harness.fetcher.onFetch = { observed = harness.repository.state.value }

        harness.repository.checkNow()

        assertEquals("请求期间行上显示「正在看……」", UpdateUiState.Checking, observed)
    }

    @Test
    fun `手动检查失败给一句人话且不影响任何操作`() = runBlocking {
        val harness = Harness() // 所有源都失败
        val result = harness.repository.checkNow()

        assertEquals(UpdateCheckResult.FailedSilent, result)
        val state = harness.repository.state.value
        assertTrue("手动失败给轻提示状态，不弹窗", state is UpdateUiState.ManualFailed)
        assertEquals(UpdateConfig.MANUAL_FAILED_MESSAGE, (state as UpdateUiState.ManualFailed).message)
        assertTrue("失败也写时间戳，避免下一次冷启动立刻再打", harness.prefs.checks.isNotEmpty())
        assertNull("手动失败不该走启动提示通道", harness.repository.autoPrompt.value)
    }

    @Test
    fun `手动检查发现已是最新`() = runBlocking {
        val harness = Harness(installed = 8L).apply { fetcher.manifest = TestManifests.manifest(versionCode = 8L) }
        val result = harness.repository.checkNow()
        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals(UpdateUiState.UpToDate(NOW), harness.repository.state.value)
    }

    @Test
    fun `用户主动查被跳过的版本 仍如实显示有新版本`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = TestManifests.manifest(versionCode = 8L)
        }
        val result = harness.repository.checkNow()
        assertTrue("忽略只压制自动提示，不隐藏版本事实", result is UpdateCheckResult.Available)
        assertTrue(harness.repository.state.value is UpdateUiState.Available)
    }

    @Test
    fun `mandatory 为 true 也不阻断（本轮不做强制更新）`() = runBlocking {
        val harness = Harness().apply {
            fetcher.manifest = TestManifests.manifest(versionCode = 8L, mandatory = true)
        }
        val result = harness.repository.checkNow()
        assertTrue("mandatory 只记录字段：本轮仍只是普通 Available", result is UpdateCheckResult.Available)
        assertTrue(harness.repository.state.value is UpdateUiState.Available)
    }

    // ---------------------------------------------------------------- 清空所有数据后重置

    // ------------------------------------------------- 清空前发起/排队的在途检查

    /**
 * 主验收：在途自动检查期间清空 → 结果整份作废。
 *
 * 测试路径：`checkOnLaunch` 先读清空前的设置快照（`ignoredVersionCode` 还是
 * 旧值）→ 网络等待 → 期间用户清空（`resetAfterWipe` 把状态置 Idle、持久键被 wipeUserData 清掉）
 * → 网络返回。旧实现会拿**旧快照**判定并发布 `Ignored`（「已跳过」复活），同时 `recordCheck`
 * 重写 `lastCheckAt`。修后：代际已变 → 不落 `_state`、不写任何持久化。
 *
 * 用可阻塞的 fake fetcher（`fetchGate` + `entered`）保证「网络确实在等待」时再清空；
 * `entered` 由 fetcher 在读取快照/取得代际**之后**才 complete，避免测试跑在 `checkOnLaunch`
 * 取代际之前导致假绿。
 */
    @Test
    fun `在途自动检查期间清空 旧已跳过不复活且不写时间戳`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = manifest
            fetcher.blockNextFetch = true
        }
        val snapshotBefore = harness.prefs.snapshotValue
        val checksBefore = harness.prefs.checks.toList()
        val pendingBefore = harness.prefs.pending.toList()
        val ignoredBefore = harness.prefs.ignored.toList()

        val checkJob = async(Dispatchers.Default) { harness.repository.checkOnLaunch() }
        harness.fetcher.entered.await() // 已在消费 fetchLatest（快照/代际已取）
        harness.repository.resetAfterWipe() // 清空成功分支会做的事
        harness.fetcher.releaseFetch()
        checkJob.await()

        assertEquals(
            "清空后在途检查的结果必须整份作废：状态保持 Idle（不得重发旧 Ignored）",
            UpdateUiState.Idle,
            harness.repository.state.value,
        )
        assertNull("也不得再推启动提示", harness.repository.autoPrompt.value)
        assertEquals("不得写 lastCheckAt", checksBefore, harness.prefs.checks)
        assertEquals("不得写 pending 版本", pendingBefore, harness.prefs.pending)
        assertEquals("不得写忽略版本", ignoredBefore, harness.prefs.ignored)
        assertEquals("持久化快照整体不变（清空流程自己负责清 DataStore）", snapshotBefore, harness.prefs.snapshotValue)
    }

    /**
 * 的「同语义」半边：手动检查也可能与清空并发（用户点完检查立刻去清空）。
 *
 * 修前：返回后会把 `Checking → Available/UpToDate/ManualFailed` 盖回清空后的 Idle。
 * 修后：状态保持 Idle、不写 `lastCheckAt`；返回值按静默失败（UI 只读 `state`）。
 */
    @Test
    fun `在途手动检查期间清空 不覆盖 Idle 且不写时间戳`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            fetcher.manifest = manifest
            fetcher.blockNextFetch = true
        }
        val checksBefore = harness.prefs.checks.toList()

        val checkJob = async(Dispatchers.Default) { harness.repository.checkNow() }
        harness.fetcher.entered.await()
        // 前置：手动检查确实已进入在途（行上显示 Checking）。
        assertEquals(UpdateUiState.Checking, harness.repository.state.value)
        harness.repository.resetAfterWipe()
        harness.fetcher.releaseFetch()
        val result = checkJob.await()

        assertEquals(UpdateCheckResult.FailedSilent, result)
        assertEquals("清空后在途手动检查不得把 Idle 盖掉", UpdateUiState.Idle, harness.repository.state.value)
        assertEquals("不得写 lastCheckAt", checksBefore, harness.prefs.checks)
    }

    /**
 * 主验收：排队中的手动检查跨越清空后必须整份作废。
 *
 * 测试路径：启动检查跨网络持有 `checkMutex` → 用户点「检查更新」，
 * 手动检查排在锁队列里（此时还没取代际）→ 用户清空（`resetAfterWipe` 代际 +1、状态置 Idle）
 * → 启动检查回包、发现代际已变而丢弃结果、**释放锁** → 排队的手动检查进锁。旧实现这时才取
 * 代际，拿到的是清空后的新代际，于是照常发布 `Checking`、写 `lastCheckAt`、发布完成状态，
 * 把清空复位后的 Idle 盖掉。
 *
 * 修后：手动检查在**进锁之前**就记下代际，进锁立即比对，不一致直接按静默失败返回。
 * 测试用 `CoroutineStart.UNDISPATCHED` 让 `checkNow` 在测试线程同步跑到 `withLock` 挂起
 * （= 入口代际已在清空前捕获、正在排队），保证稳定复现「清空前排队」的时序，而不是靠 sleep。
 */
    @Test
    fun `排队中的手动检查跨越清空后作废 不发布 Checking 不写时间戳`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            fetcher.manifest = manifest
            fetcher.blockNextFetch = true
        }
        val checksBefore = harness.prefs.checks.toList()
        // 订阅状态流：既确认「排队窗口内没有发布过 Checking」，也避免只靠终态断言。
        val seenStates = java.util.concurrent.CopyOnWriteArrayList<UpdateUiState>()
        val collecting = launch(Dispatchers.Default) { harness.repository.state.collect { seenStates += it } }
        withTimeout(5_000) { while (seenStates.isEmpty()) delay(1) }

        // 自动检查进网络并持有 checkMutex。
        val autoJob = async(Dispatchers.Default) { harness.repository.checkOnLaunch() }
        harness.fetcher.entered.await()

        // 手动检查排在锁队列里：UNDISPATCHED 同步跑到 withLock 挂起，入口代际在清空之前。
        val manualJob = async(start = CoroutineStart.UNDISPATCHED) { harness.repository.checkNow() }
        assertTrue("手动检查应在等锁（不能已在清空前执行完）", !manualJob.isCompleted)
        assertEquals("排队期间还没拿到锁，不能发布 Checking", UpdateUiState.Idle, harness.repository.state.value)

        harness.repository.resetAfterWipe() // 排队期间用户清空
        harness.fetcher.releaseFetch() // 放行启动检查 → 它丢弃结果后释放锁
        autoJob.await()
        val result = manualJob.await()

        assertEquals("清空前排队的手动检查按静默失败作废", UpdateCheckResult.FailedSilent, result)
        assertEquals("不得写 lastCheckAt", checksBefore, harness.prefs.checks)
        assertEquals("状态保持清空后的 Idle", UpdateUiState.Idle, harness.repository.state.value)
        assertTrue(
            "排队窗口内不得发布 Checking（实际观测：$seenStates）",
            seenStates.none { it is UpdateUiState.Checking },
        )
        collecting.cancel()
    }

    /**
 * 的另一半：`checkOnLaunch` 同样在进锁前取代际。
 *
 * 时序：一次手动检查持锁在途 → 启动检查排在锁队列里 → 清空 → 在途手动检查回包丢弃并释放锁
 * → 排队的启动检查进锁。旧实现会把它当成清空后的新一轮，照常发布 `Ignored`/`Available`
 * 并写 `lastCheckAt`；修后整份作废。
 */
    @Test
    fun `排队中的启动检查跨越清空后作废 不写时间戳不发布状态`() = runBlocking {
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = TestManifests.manifest(versionCode = 8L)
            fetcher.blockNextFetch = true
        }
        val checksBefore = harness.prefs.checks.toList()

        // 手动检查先持锁在途（它会发布 Checking，清空后复位）。
        val manualJob = async(Dispatchers.Default) { harness.repository.checkNow() }
        harness.fetcher.entered.await()

        // 启动检查排在锁队列里：UNDISPATCHED 同步跑到 withLock 挂起，入口代际在清空之前。
        val autoJob = async(start = CoroutineStart.UNDISPATCHED) { harness.repository.checkOnLaunch() }
        assertTrue("启动检查应在等锁（不能已在清空前执行完）", !autoJob.isCompleted)

        harness.repository.resetAfterWipe()
        harness.fetcher.releaseFetch()
        manualJob.await()
        autoJob.await()

        assertEquals("排队中的启动检查整份作废：不得写 lastCheckAt", checksBefore, harness.prefs.checks)
        assertEquals("状态保持清空后的 Idle", UpdateUiState.Idle, harness.repository.state.value)
        assertNull("不得推送启动提示", harness.repository.autoPrompt.value)
        assertTrue("不得写 pending 版本", harness.prefs.pending.isEmpty())
    }

    /**
 * 反向护栏：守卫只拦「清空前发起并排队」的检查，不能把「清空之后才点的检查」也拦掉。
 *
 * 时序：启动检查持锁在途 → 清空 → 用户**清空后**点「检查更新」（排在同一个在途检查后面）
 * → 在途检查回包丢弃并释放锁 → 清空后点的手动检查属于新代际，必须照常执行。
 */
    @Test
    fun `清空后点的手动检查即使排在清空前在途检查后面也照常执行`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            fetcher.manifest = manifest
            fetcher.blockNextFetch = true
        }

        val autoJob = async(Dispatchers.Default) { harness.repository.checkOnLaunch() }
        harness.fetcher.entered.await()
        harness.repository.resetAfterWipe() // 在途自动检查属于清空前，稍后会被丢弃
        // 清空之后才点的检查：入口代际已是新代际，排队在同一个在途检查后面也必须照常执行。
        val manualJob = async(start = CoroutineStart.UNDISPATCHED) { harness.repository.checkNow() }
        assertTrue("手动检查应在等锁", !manualJob.isCompleted)

        harness.fetcher.releaseFetch()
        autoJob.await()
        val result = manualJob.await()

        assertTrue("清空后点的检查必须照常出结果", result is UpdateCheckResult.Available)
        assertEquals(UpdateUiState.Available(manifest), harness.repository.state.value)
        assertEquals("清空后的检查照常写 lastCheckAt", listOf(NOW), harness.prefs.checks)
    }

    @Test
    fun `清空后新发起的自动检查照常落状态与时间戳`() = runBlocking {
        // 反向护栏：代际守卫只拦「清空前发起」的检查，不能把清空之后的正常检查也拦掉。
        val manifest = TestManifests.manifest(versionCode = 8L)
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = manifest
        }
        harness.repository.resetAfterWipe() // 先清空（代际 +1），此时还没有在途检查
        harness.repository.checkOnLaunch()

        assertEquals("清空后发起的检查是新一轮，正常发布状态", UpdateUiState.Ignored(manifest, NOW), harness.repository.state.value)
        assertEquals("清空后的检查照常写 lastCheckAt", listOf(NOW), harness.prefs.checks)
    }

    @Test
    fun `清空后新发起的手动检查照常发布状态并写时间戳`() = runBlocking {
        // 反向护栏：手动检查同样只在「清空前发起并排队」时作废，
        // 清空之后才点的检查是新一轮，不能被入口代际守卫误拦。
        val manifest = TestManifests.manifest(versionCode = 8L)
        val harness = Harness().apply { fetcher.manifest = manifest }
        harness.repository.resetAfterWipe()

        val result = harness.repository.checkNow()

        assertTrue("清空后点的手动检查必须照常出结果", result is UpdateCheckResult.Available)
        assertEquals(UpdateUiState.Available(manifest), harness.repository.state.value)
        assertEquals("清空后的手动检查照常写 lastCheckAt", listOf(NOW), harness.prefs.checks)
    }

    // ------------------------------------------------- 挂起点期间清空

    /**
 * 主验收之一：启动检查在 `prefs.recordCheck` **挂起期间**
 * 发生清空 → 恢复后不得再发布清空前的 `Ignored`/`Available`/`UpToDate`。
 *
 * 时序：网络已返回且末次代际比对通过 → 进入 `recordCheck`（生产 = DataStore，真挂起）→
 * 用户清空（`resetAfterWipe` 代际 +1、状态 Idle、提示清空）→ `recordCheck` 恢复。修前会继续按
 * 清空前的设置快照发布 `Ignored`；修后写后复核代际，整份作废。
 *
 * 注意：`lastCheckAt` 的写入发生在挂起区间内、可能已落盘，属已披露的可接受残余
 * （不改持久化语义）；因此这里断言的是**状态与提示**不被重新发布，而不是持久化完全不变。
 */
    @Test
    fun `启动检查在 recordCheck 挂起期间清空 不发布清空前状态且不写 pending`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = manifest
            prefs.blockNextRecordCheck = true
        }
        // 订阅状态流：既看终态，也确认清空后没有再发布任何非 Idle 状态。
        val seenStates = java.util.concurrent.CopyOnWriteArrayList<UpdateUiState>()
        val collecting = launch(Dispatchers.Default) { harness.repository.state.collect { seenStates += it } }
        withTimeout(5_000) { while (seenStates.isEmpty()) delay(1) }

        val checkJob = async(Dispatchers.Default) { harness.repository.checkOnLaunch() }
        harness.prefs.recordCheckEntered.await() // 已进入 recordCheck 挂起点（网络与末次比对都已过）
        harness.repository.resetAfterWipe() // 写持久化期间清空
        harness.prefs.releaseRecordCheck()
        checkJob.await()

        assertEquals(
            "清空后不得发布清空前的旧 Ignored/Available/UpToDate：终态必须回到 Idle",
            UpdateUiState.Idle,
            harness.repository.state.value,
        )
        assertNull("也不得推送启动提示", harness.repository.autoPrompt.value)
        assertTrue("不得写 pending 版本（清空后不再是同一轮）", harness.prefs.pending.isEmpty())
        assertTrue(
            "恢复后不得再发布任何非 Idle 状态（实际观测：$seenStates）",
            seenStates.all { it is UpdateUiState.Idle },
        )
        // 可接受残余：`lastCheckAt` 的写入已开始，可能已落盘。
        assertEquals(listOf(NOW), harness.prefs.checks)
        collecting.cancel()
    }

    /**
 * 主验收之一（续）：启动检查在 `prefs.recordPendingVersion` **挂起期间**清空 →
 * 恢复后不得重投旧自动提示。
 *
 * 修前：`recordPendingVersion` 返回后无条件 `_autoPrompt.value = manifest`，把清空前的提示
 * 重新投给 UI（状态虽已被 `resetAfterWipe` 复位，提示却复活）。修后：写后复核代际，命中不设提示。
 */
    @Test
    fun `启动检查在 recordPendingVersion 挂起期间清空 不再重投旧自动提示`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true) // pendingVersionCode=0 → 走 recordPendingVersion 分支
            fetcher.manifest = manifest
            prefs.blockNextRecordPendingVersion = true
        }

        val checkJob = async(Dispatchers.Default) { harness.repository.checkOnLaunch() }
        harness.prefs.recordPendingEntered.await()
        // 前置：进入挂起点之前，Available 是按时有效的判定发布的（不是残余）。
        assertEquals(UpdateUiState.Available(manifest), harness.repository.state.value)
        harness.repository.resetAfterWipe() // 写 pending 期间清空
        harness.prefs.releaseRecordPendingVersion()
        checkJob.await()

        assertEquals("清空后不得留下清空前的 Available", UpdateUiState.Idle, harness.repository.state.value)
        assertNull("恢复后不得重投清空前的自动提示", harness.repository.autoPrompt.value)
        // 可接受残余：pending 的落盘可能发生在清空之后（与 lastCheckAt 同型，不改持久化语义）。
        assertEquals(listOf(8L), harness.prefs.pending)
    }

    /**
 * 主验收之二：手动检查在 `prefs.recordCheck` **挂起期间**清空 → 三个结果分支
 * （`Available` / `UpToDate` / `ManualFailed`）都不得覆盖清空后的 Idle，返回值按静默失败。
 *
 * 此前：`recordCheck` 返回后无条件把 `Checking` 改成结果状态（`UpdateRepository.kt` 旧实现），
 * 清空复位的 Idle 被盖掉。修后：写后复核代际，命中就复位 Idle 并 `FailedSilent`。
 */
    @Test
    fun `手动检查在 recordCheck 挂起期间清空 各结果分支都复位 Idle 且静默失败`() = runBlocking {
        val cases: List<Triple<Long, VersionManifest?, String>> = listOf(
            Triple(7L, TestManifests.manifest(versionCode = 8L, versionName = "0.5.2"), "Available"),
            Triple(8L, TestManifests.manifest(versionCode = 8L, versionName = "0.5.2"), "UpToDate"),
            Triple(7L, null, "ManualFailed"),
        )
        for ((installed, manifest, label) in cases) {
            val harness = Harness(installed = installed).apply {
                fetcher.manifest = manifest
                prefs.blockNextRecordCheck = true
            }
            val checkJob = async(Dispatchers.Default) { harness.repository.checkNow() }
            harness.prefs.recordCheckEntered.await()
            // 前置：挂起点之前确实已发布 Checking（清空后必须复位）。
            assertEquals("$label：挂起前应处于 Checking", UpdateUiState.Checking, harness.repository.state.value)
            harness.repository.resetAfterWipe() // 写时间戳期间清空
            harness.prefs.releaseRecordCheck()
            val result = checkJob.await()

            assertEquals("$label：清空后按静默失败返回", UpdateCheckResult.FailedSilent, result)
            assertEquals("$label：不得把清空后的 Idle 盖回结果状态", UpdateUiState.Idle, harness.repository.state.value)
            // 可接受残余：`lastCheckAt` 的写入已开始，可能已落盘。
            assertEquals("$label：lastCheckAt 可能已落盘", listOf(NOW), harness.prefs.checks)
        }
    }

    // ------------------------------------------- 反向护栏：未清空时行为逐路径不变

    @Test
    fun `未清空时 recordCheck 挂起不改变自动检查的发布与记录`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            fetcher.manifest = manifest
            prefs.blockNextRecordCheck = true
        }

        val checkJob = async(Dispatchers.Default) { harness.repository.checkOnLaunch() }
        harness.prefs.recordCheckEntered.await()
        assertEquals("挂起期间还没发布完成状态", UpdateUiState.Idle, harness.repository.state.value)
        harness.prefs.releaseRecordCheck()
        checkJob.await()

        assertEquals("未清空：照常发布 Available", UpdateUiState.Available(manifest), harness.repository.state.value)
        assertEquals("未清空：照常推自动提示", manifest, harness.repository.autoPrompt.value)
        assertEquals("未清空：照常写 lastCheckAt", listOf(NOW), harness.prefs.checks)
        assertEquals("未清空：照常写 pending", listOf(8L), harness.prefs.pending)
    }

    @Test
    fun `未清空时 recordPendingVersion 挂起不阻止自动提示`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            fetcher.manifest = manifest
            prefs.blockNextRecordPendingVersion = true
        }

        val checkJob = async(Dispatchers.Default) { harness.repository.checkOnLaunch() }
        harness.prefs.recordPendingEntered.await()
        assertEquals(UpdateUiState.Available(manifest), harness.repository.state.value)
        assertNull("挂起期间提示还没投递", harness.repository.autoPrompt.value)
        harness.prefs.releaseRecordPendingVersion()
        checkJob.await()

        assertEquals("未清空：照常推自动提示", manifest, harness.repository.autoPrompt.value)
        assertEquals(listOf(8L), harness.prefs.pending)
        assertEquals(UpdateUiState.Available(manifest), harness.repository.state.value)
    }

    @Test
    fun `未清空时手动检查 recordCheck 挂起照常发布状态与记录时间`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply {
            fetcher.manifest = manifest
            prefs.blockNextRecordCheck = true
        }

        val checkJob = async(Dispatchers.Default) { harness.repository.checkNow() }
        harness.prefs.recordCheckEntered.await()
        assertEquals(UpdateUiState.Checking, harness.repository.state.value)
        harness.prefs.releaseRecordCheck()
        val result = checkJob.await()

        assertTrue("未清空：照常返回 Available", result is UpdateCheckResult.Available)
        assertEquals(UpdateUiState.Available(manifest), harness.repository.state.value)
        assertEquals("未清空：照常写 lastCheckAt", listOf(NOW), harness.prefs.checks)
        assertNull("手动检查不推启动提示", harness.repository.autoPrompt.value)
    }

    // ------------------------------------------------- 最终复核与发布之间清空

    @Test
    fun `启动检查在安装版本读取与发布之间清空 不发布旧状态或提示`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply { fetcher.manifest = manifest }
        var wipeTriggered = false
        // 旧路径在此之前已通过 recordCheck 后的代际复核；模拟 PackageManager IPC 期间清空。
        harness.onInstalledVersionCode = {
            wipeTriggered = true
            harness.repository.resetAfterWipe()
        }

        harness.repository.checkOnLaunch()

        assertTrue("回归时序必须执行安装版本读取挂钩", wipeTriggered)
        assertEquals("旧启动检查不得把清空后的 Idle 盖成 Available", UpdateUiState.Idle, harness.repository.state.value)
        assertNull("不得重新投递清空前的启动提示", harness.repository.autoPrompt.value)
        assertTrue("结果发布前清空，不应开始写 pending", harness.prefs.pending.isEmpty())
        // recordCheck 已在清空前完成；时间戳语义不变。
        assertEquals(listOf(NOW), harness.prefs.checks)
    }

    @Test
    fun `手动检查在安装版本读取与发布之间清空 静默失败且保持 Idle`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply { fetcher.manifest = manifest }
        var wipeTriggered = false
        // 旧路径在此之前已通过 recordCheck 后的代际复核；模拟 PackageManager IPC 期间清空。
        harness.onInstalledVersionCode = {
            wipeTriggered = true
            harness.repository.resetAfterWipe()
        }

        val result = harness.repository.checkNow()

        assertTrue("回归时序必须执行安装版本读取挂钩", wipeTriggered)
        assertEquals("清空后旧手动结果按静默失败返回", UpdateCheckResult.FailedSilent, result)
        assertEquals("旧手动结果不得把清空后的 Idle 盖回", UpdateUiState.Idle, harness.repository.state.value)
        assertNull("手动检查不得投递启动提示", harness.repository.autoPrompt.value)
        assertEquals("recordCheck 仍按原语义完成", listOf(NOW), harness.prefs.checks)
    }

    @Test
    fun `清空后 resetAfterWipe 把已跳过重置为 Idle 且不写任何持久化键`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L)
        val harness = Harness().apply {
            prefs.snapshotValue = UpdatePrefsSnapshot(agreed = true, ignoredVersionCode = 8L)
            fetcher.manifest = manifest
        }
        harness.repository.checkOnLaunch()
        assertEquals(UpdateUiState.Ignored(manifest, NOW), harness.repository.state.value)

        // 记下清空前的持久化现场：resetAfterWipe 是「只清内存」的操作，不得再动这些键。
        val snapshotBefore = harness.prefs.snapshotValue
        val checksBefore = harness.prefs.checks.toList()
        val pendingBefore = harness.prefs.pending.toList()
        val ignoredBefore = harness.prefs.ignored.toList()

        harness.repository.resetAfterWipe()

        assertEquals("清空后回到 Idle：再进设置那行只显示当前版本", UpdateUiState.Idle, harness.repository.state.value)
        assertNull(harness.repository.autoPrompt.value)
        assertEquals("resetAfterWipe 不得写任何持久化键（快照不变）", snapshotBefore, harness.prefs.snapshotValue)
        assertEquals("不得写检查时间", checksBefore, harness.prefs.checks)
        assertEquals("不得写 pending 版本", pendingBefore, harness.prefs.pending)
        assertEquals("不得写忽略版本", ignoredBefore, harness.prefs.ignored)
        assertEquals(
            "resetAfterWipe 不负责清 DataStore 的忽略版本（清空流程负责）",
            8L,
            harness.prefs.snapshotValue.ignoredVersionCode,
        )
    }

    @Test
    fun `resetAfterWipe 也清掉启动提示通道 不再弹清空前的更新提示`() = runBlocking {
        val manifest = TestManifests.manifest(versionCode = 8L, versionName = "0.5.2")
        val harness = Harness().apply { fetcher.manifest = manifest }
        harness.repository.checkOnLaunch()
        assertEquals("前置：Available 且有待弹的启动提示", manifest, harness.repository.autoPrompt.value)

        val snapshotBefore = harness.prefs.snapshotValue
        val checksBefore = harness.prefs.checks.toList()
        val pendingBefore = harness.prefs.pending.toList()

        harness.repository.resetAfterWipe()

        assertEquals(UpdateUiState.Idle, harness.repository.state.value)
        assertNull("清空后不该再弹清空前的更新提示", harness.repository.autoPrompt.value)
        assertEquals("resetAfterWipe 不得写任何持久化键（快照不变）", snapshotBefore, harness.prefs.snapshotValue)
        assertEquals(checksBefore, harness.prefs.checks)
        assertEquals(pendingBefore, harness.prefs.pending)
    }

    /**
 * AppRoot 接线扫描，确认清空代际更新先于清空操作。
 *
 * 为什么源码扫描：清空执行在 `AppRoot` 的 Composable `runClear` 里，本仓库没有
 * Compose UI 测试 / androidTest（ 已列为未覆盖），无法在纯 JVM 里跑这段 UI 逻辑；
 * 同类源码扫描通过检查 AppRoot.kt 锁定接线，cwd = `app/`。
 *
 * （ 决定）：从「清空成功后」提前到「**进入执行的那一刻**
 * （`ExportService.clearAll` 之前）」。这样 clearAll 执行期间返回的在途检查也会被代际挡住，
 * 不会出现「持久键已清、旧结果后到又写回」。本测试锁：调用存在 + 位于 runClear 内 +
 * 早于 `ExportService.clearAll(container)`。
 */
    @Test
    fun `AppRoot 清空执行前（clearAll 之前）调用 resetAfterWipe`() {
        val appRootText = java.io.File("src/main/java/cc/pulsez/app/ui/AppRoot.kt").readText(Charsets.UTF_8)
        assertTrue("AppRoot.kt 读取失败/为空（cwd 应为 app/）", appRootText.isNotBlank())

        val runClearStart = appRootText.indexOf("val runClear: () -> Unit")
        assertTrue("AppRoot.kt 找不到 runClear 定义", runClearStart >= 0)
        val clearAllCall = appRootText.indexOf("ExportService.clearAll(container)", runClearStart)
        assertTrue("runClear 里找不到 ExportService.clearAll(container)", clearAllCall > runClearStart)

        val call = appRootText.indexOf("updateRepository.resetAfterWipe()")
        assertTrue("清空执行前必须调用 updateRepository.resetAfterWipe()", call > runClearStart)
        assertTrue(
            "resetAfterWipe 必须在 ExportService.clearAll 之前",
            call < clearAllCall,
        )
    }

    // ---------------------------------------------------------------- 内存 fake

    private class Harness(
        autoCheckEnabled: Boolean = true,
        installed: Long = 7L,
    ) {
        val prefs = FakePrefs()
        val fetcher = FakeFetcher()
        var onInstalledVersionCode: (() -> Unit)? = null
        val repository = UpdateRepository(
            fetcher = fetcher,
            prefs = prefs,
            installedVersionCode = {
                onInstalledVersionCode?.invoke()
                installed
            },
            clock = { NOW },
            autoCheckEnabled = autoCheckEnabled,
        )
    }

    private class FakeFetcher : ManifestFetcher {
        var manifest: VersionManifest? = null
        var calls: Int = 0
        var onFetch: (() -> Unit)? = null

        /**
 * 「在途检查 + 清空」测试用：下一次 fetch 阻塞到 [releaseFetch]。
 *
 * 只影响显式打开它的用例（默认 false），既有用例的时序/调用次数断言全不变。
 */
        var blockNextFetch: Boolean = false
        /** fetcher 已开始消费请求（此时 `checkOnLaunch`/`checkNow` 的代际与快照都已取好）。 */
        val entered = CompletableDeferred<Unit>()
        private val fetchGate = CompletableDeferred<Unit>()

        fun releaseFetch() {
            fetchGate.complete(Unit)
        }

        override suspend fun fetchLatest(): VersionManifest? {
            calls += 1
            onFetch?.invoke()
            if (blockNextFetch) {
                blockNextFetch = false
                entered.complete(Unit)
                fetchGate.await()
            }
            return manifest
        }
    }

    private class FakePrefs(
        var snapshotValue: UpdatePrefsSnapshot = UpdatePrefsSnapshot(agreed = true),
    ) : UpdatePrefs {
        val checks = mutableListOf<Long>()
        val pending = mutableListOf<Long>()
        val ignored = mutableListOf<Long>()

        /**
 * 测试用：下一次 `recordCheck` / `recordPendingVersion` 阻塞到对应的 release。
 *
 * 生产实现是 DataStore（真挂起），这里用 `CompletableDeferred.await` 模拟同型挂起点：
 * [recordCheckEntered] / [recordPendingEntered] 在 fake **已进入写操作、但还没返回**时
 * complete，测试据此在「写持久化期间」执行清空。默认 false：既有用例的调用次数/时序完全不变。
 */
        var blockNextRecordCheck: Boolean = false
        var blockNextRecordPendingVersion: Boolean = false

        /** fake 已进入对应写操作（挂起点）；此时清空 = 「写持久化期间清空」。 */
        val recordCheckEntered = CompletableDeferred<Unit>()
        val recordPendingEntered = CompletableDeferred<Unit>()
        private val recordCheckGate = CompletableDeferred<Unit>()
        private val recordPendingGate = CompletableDeferred<Unit>()

        fun releaseRecordCheck() {
            recordCheckGate.complete(Unit)
        }

        fun releaseRecordPendingVersion() {
            recordPendingGate.complete(Unit)
        }

        override suspend fun snapshot(): UpdatePrefsSnapshot = snapshotValue

        override suspend fun recordCheck(at: Long) {
            if (blockNextRecordCheck) {
                blockNextRecordCheck = false
                recordCheckEntered.complete(Unit)
                recordCheckGate.await()
            }
            checks += at
            snapshotValue = snapshotValue.copy(lastCheckAt = at)
        }

        override suspend fun recordPendingVersion(versionCode: Long) {
            if (blockNextRecordPendingVersion) {
                blockNextRecordPendingVersion = false
                recordPendingEntered.complete(Unit)
                recordPendingGate.await()
            }
            pending += versionCode
            snapshotValue = snapshotValue.copy(pendingVersionCode = versionCode)
        }

        override suspend fun setIgnoredVersion(versionCode: Long) {
            ignored += versionCode
            snapshotValue = snapshotValue.copy(ignoredVersionCode = versionCode)
        }
    }
}
