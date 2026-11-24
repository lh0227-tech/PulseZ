package cc.pulsez.app.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「检查更新」的状态机与节流（设置页/启动提示的唯一数据源）。
 *
 * 职责：
 * - **启动静默检查** [checkOnLaunch]：24h 节流、未过门槛不查、失败完全静默、成功才更新状态；
 * - **手动检查** [checkNow]：不受 24h 节流限制（用户主动点的必须真去查），失败给一句人话；
 * - **忽略版本** [markIgnored]：「稍后」写 `update_ignored_version_code`，同版本不再自动提示；
 * - **只打扰一次**：自动检查发现新版且该版本没提示过（`update_pending_version_code`）时，
 *   通过 [autoPrompt] 通知 UI 弹一次非阻断对话框；之后同版本不再弹。
 *
 * 依据（触发与节流）、§4.2（三阶段流程）、§5.1/§5.2（状态与键）、
 * §6.4（失败静默、App 功能不依赖更新源）、§6.5（不做强制更新）。
 *
 * 不比较/不读取 `mandatory`：本版不实现任何阻断式 UI，字段只解析记录。
 *
 * @param installedVersionCode 当前安装版本的 `PackageInfo.longVersionCode`（注入，便于单测）
 * @param clock 本地时钟（注入，便于测 24h 边界与时钟回拨）
 * @param autoCheckEnabled 是否自动查（debug 构建为 false；手动检查不受影响）
 */
class UpdateRepository(
    private val fetcher: ManifestFetcher,
    private val prefs: UpdatePrefs,
    private val installedVersionCode: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val autoCheckEnabled: Boolean = UpdateConfig.AUTO_CHECK_ON_LAUNCH,
) {

    /** 串行化自动/手动检查：避免两个请求同时改状态、同时写时间戳。 */
    private val checkMutex = Mutex()

    /**
     * 清空代际（v0.5.4）：[resetAfterWipe] 自增；检查开始时记下，网络返回后若已变则整个结果作废。
     *
     * 为什么单独一个代际而不复用 `AppContainer.wipeEpoch`：本仓库是纯 JVM 可测的类
     * （`UpdateRepositoryTest` 不构造 Android 容器），代际的**触发点只有它自己的
     * [resetAfterWipe]**——清空的**执行起点**（v0.5.4 起提前到 `ExportService.clearAll` 之前，
     * 调用点见 `AppRoot.runClear`）本来就必须调用它，这样既不新增接线、也不会出现「容器代际变了
     * 而更新状态没重置」的半状态。
     *
     * `AtomicLong` 而不是普通 `var`：自增在 UI 线程、比对在 `appScope` 的 IO 线程。

     * 第 2 条、
     * （`「清空代际校验，清空成功前发起的检查结果不得再落状态/写持久化」）。
     */
    private val wipeEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 串行化「最终代际复核 + 内存发布」与清空复位。
     *
     * 锁内只做同步内存操作；DataStore 写入与安装版本读取（PackageManager IPC）都在锁外。
     * 安装版本读取期间发生清空时也遵循此行为，避免恢复已清除的状态。
     */
    private val publishLock = Any()

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)

    /** 设置页顶部行订阅它。 */
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _autoPrompt = MutableStateFlow<VersionManifest?>(null)

    /**
     * 启动后「发现新版本」的一次性非阻断提示通道（AppRoot 订阅，弹一次对话框）。
     *
     * 与 [state] 分开：手动检查也会让状态变成 [UpdateUiState.Available]，但**不该**再弹启动提示
     * （用户是自己点的，设置页会自己开对话框）。null = 没有待提示的版本。
     */
    val autoPrompt: StateFlow<VersionManifest?> = _autoPrompt.asStateFlow()

    /**
     * 启动时的静默检查（`PulsezApp.onCreate` → `appScope.launch` 调用）。
     *
     * 条件（全满足才发请求）：
     * 1. [autoCheckEnabled]（debug 构建不查；手动检查仍可用）；
     * 2. 用户已通过首启门槛页（「门槛页未完成不检查，避免首启就联网」）；
     * 3. 距上次检查尝试 ≥ 24h（或从没查过 / 时钟回拨）。
     *
     * 结果处理：
     * - 失败 → 只写 `lastCheckAt`，**状态不变、不提示**（静默；否则会变成「每次冷启动都打扰」）；
     * - 最新/回滚指针 → [UpdateUiState.UpToDate]；
     * - 有更新但用户点过「稍后」→ [UpdateUiState.Ignored]（如实保留版本事实，**不得**回落成
     *   「已是最新」）；
     * - 有更新且没提示过 → [UpdateUiState.Available] + [autoPrompt]。
     */
    suspend fun checkOnLaunch() {
        if (!autoCheckEnabled) return
        // v0.5.5 在**进锁之前**记下清空代际。自动检查跨网络持锁时，启动/手动检查会排在
        // `checkMutex` 队列里；若排队期间发生清空，旧实现进锁后才取代际，拿到的是清空后的**新**
        // 代际，于是「清空前发起的排队请求」被当成新一轮，仍会发布状态/写 `lastCheckAt`。
        // 依据：` 。
        val entryEpoch = wipeEpoch.get()
        checkMutex.withLock {
            // v0.5.4 清空前发起的检查，结果回来后不得再发布（否则旧 Ignored 复活 + 重写 lastCheckAt）。
            val epoch = wipeEpoch.get()
            // v0.5.5 排队等锁期间清空 → 本请求属于清空前发起，整份作废
            // （不读设置快照、不出网、不写状态、不写持久化）。
            if (epoch != entryEpoch) return
            val snapshot = prefs.snapshot()
            if (!snapshot.agreed) return
            val now = clock()
            if (!UpdateConfig.isAutoCheckDue(now, snapshot.lastCheckAt)) return

            val manifest = runCatching { fetcher.fetchLatest() }.getOrNull()
            // 网络等待期间发生了「清空所有数据」：本次结果基于清空前的设置快照与时间，
            // 一律作废——不写 `_state`、不写 `lastCheckAt`、不写 pending 版本、不推送提示。
            // 依据：` 第 2 条。
            if (wipeEpoch.get() != epoch) return
            // 失败也写时间戳：不写的话，更新源一直不通时每次冷启动都会再打一次（要求）。
            prefs.recordCheck(now)
            // v0.5.6 `recordCheck` 是**挂起点**（生产实现走 DataStore，
            // `UpdatePrefs.kt:63–65` → `SettingsStore.setUpdateCheckResult`）。若清空发生在这段挂起
            // 期间，恢复后不得再用清空前的快照发布 `Ignored`/`Available`/`UpToDate`，也不得写 pending
            // 或推送/重投自动提示——整份作废（状态已由 `resetAfterWipe` 复位为 Idle）。
            // `lastCheckAt` 的写入发生在挂起点前后、可能已落盘：这是已披露的可接受残余，不改持久化语义。
            // 依据：–152`。
            if (wipeEpoch.get() != epoch) return
            if (manifest == null) return

            // PackageManager IPC 在锁外。最终复核和状态发布必须与 resetAfterWipe 互斥，
            // 这样安装版本读取期间发生清空时，旧检查不能把 Idle 覆盖回去。
            val nextState: UpdateUiState
            val shouldOfferPrompt: Boolean
            if (!manifest.isNewerThan(installedVersionCode())) {
                nextState = UpdateUiState.UpToDate(now)
                shouldOfferPrompt = false
            } else if (manifest.versionCode == snapshot.ignoredVersionCode) {
                // 用户点过「稍后」：不弹提示，但版本事实必须可见（不得显示「已是最新」）。

                nextState = UpdateUiState.Ignored(manifest, now)
                shouldOfferPrompt = false
            } else {
                nextState = UpdateUiState.Available(manifest)
                shouldOfferPrompt = true
            }

            val statePublished = synchronized(publishLock) {
                if (wipeEpoch.get() != epoch) {
                    false
                } else {
                    _state.value = nextState
                    true
                }
            }
            if (!statePublished) return@withLock
            if (!shouldOfferPrompt || manifest.versionCode == snapshot.pendingVersionCode) return@withLock

            // 同一版本只打扰一次：先落盘再推提示（进程被杀也不会下次启动又弹）。
            // DataStore 写入是挂起点，不能放在 publishLock 内。
            prefs.recordPendingVersion(manifest.versionCode)
            // 清空可能在 pending 写入期间完成；锁内复核并发布提示，若已变代则回滚
            // 内存状态。pending 可能已落盘，这是挂起写入无法与内存清空原子化的已知残余。
            // 读取安装版本期间即使发生清空，也不写回已清除的状态。
            val promptPublished = synchronized(publishLock) {
                if (wipeEpoch.get() != epoch) {
                    _state.value = UpdateUiState.Idle
                    _autoPrompt.value = null
                    false
                } else {
                    _autoPrompt.value = manifest
                    true
                }
            }
            if (!promptPublished) return@withLock
        }
    }

    /**
     * 手动检查（用户点设置页「检查更新」）。
     *
     * **不受 24h 节流限制**；失败给 [UpdateUiState.ManualFailed]，
     * 不弹窗、不拦截任何操作（「这次没连上发布页，过一会儿再试。」）。
     *
     * 手动检查**不**因 `ignoredVersionCode` 隐藏新版本：用户主动问了，就如实回答（并允许再次打开
     * 下载对话框）；「忽略」只用于压制**自动**提示，不删除「有新版本」这个事实。
     *
     * @return 本次结果（UI 只读 [state]；返回值主要给测试/将来埋点用，不含用户数据）
     */
    suspend fun checkNow(): UpdateCheckResult {
        // v0.5.5 在**进锁之前**记下清空代际。自动检查跨网络持锁时用户仍能点「检查更新」，
        // 手动检查会排在 `checkMutex` 队列里；若排队期间发生清空，旧实现进锁后才取代际，拿到的是
        // 清空后的**新**代际，于是这条「清空前点击」的请求会照常发布 `Checking`、写 `lastCheckAt`
        // 并发布完成状态，盖掉清空复位后的 Idle。
        // 依据：` 。
        val entryEpoch = wipeEpoch.get()
        return checkMutex.withLock {
            // v0.5.4 同语义：手动检查也可能与清空并发（用户在别的入口点检查后立刻清空）。
            // 排队等锁期间清空 → 本次点击属于清空前发起，按静默失败作废。
            // 代际复核与 Checking 发布同清空复位互斥，避免复位后的 Idle 被覆盖。
            val epoch = synchronized(publishLock) {
                val currentEpoch = wipeEpoch.get()
                if (currentEpoch != entryEpoch) {
                    null
                } else {
                    _state.value = UpdateUiState.Checking
                    currentEpoch
                }
            }
            if (epoch == null) return@withLock UpdateCheckResult.FailedSilent
            val now = clock()
            val manifest = runCatching { fetcher.fetchLatest() }.getOrNull()
            // 网络等待期间清空了：结果作废，状态保持清空后的 Idle（不盖回 Checking/UpToDate/Available），
            // 也不写 `lastCheckAt`。返回值没有「被清空取消」这一类，按静默失败交给调用方（UI 只读 state）。
            if (wipeEpoch.get() != epoch) return@withLock UpdateCheckResult.FailedSilent
            // 手动检查同样记录时间：用户刚查过，下一次冷启动的自动检查没必要立刻再打一次。
            prefs.recordCheck(now)
            // v0.5.6 `recordCheck` 挂起期间清空 → 恢复后不得把清空后的 Idle 盖回
            // `Available`/`UpToDate`/`ManualFailed`。清空复位已在 publishLock 内写入 Idle，
            // 这里仅复核，不再写状态（UI 只读 state）。
            // `lastCheckAt` 的写入可能已落盘：已披露的可接受残余，不改持久化语义。
            // 依据：–152`。
            val currentAfterRecord = synchronized(publishLock) { wipeEpoch.get() == epoch }
            if (!currentAfterRecord) return@withLock UpdateCheckResult.FailedSilent

            val result: UpdateCheckResult = when {
                manifest == null -> UpdateCheckResult.FailedSilent
                manifest.isNewerThan(installedVersionCode()) -> UpdateCheckResult.Available(manifest)
                else -> UpdateCheckResult.UpToDate(now)
            }

            // 安装版本读取（PackageManager IPC）在锁外；结果发布前再在锁内复核代际。
            val statePublished = synchronized(publishLock) {
                if (wipeEpoch.get() != epoch) {
                    false
                } else {
                    _state.value = when (result) {
                        is UpdateCheckResult.Available -> UpdateUiState.Available(result.manifest)
                        is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.checkedAt)
                        UpdateCheckResult.FailedSilent -> UpdateUiState.ManualFailed(UpdateConfig.MANUAL_FAILED_MESSAGE)
                    }
                    true
                }
            }
            if (statePublished) result else UpdateCheckResult.FailedSilent
        }
    }

    /**
     * 用户点「稍后」：记住这个版本，同版本不再自动提示。
     *
     * 只写「跳过」标记，**不删**任何东西；若当前正在提示同一版本，状态改为
     * [UpdateUiState.Ignored]（如实告诉用户「有新版本 X，已跳过」），**不得**回落成
     * `UpToDate`（那是对用户谎报）。
     * 依据（忽略只压制自动提示）。
     */
    suspend fun markIgnored(manifest: VersionManifest) {
        prefs.setIgnoredVersion(manifest.versionCode)
        _autoPrompt.value = null
        val current = _state.value
        if (current is UpdateUiState.Available && current.manifest.versionCode == manifest.versionCode) {
            _state.value = UpdateUiState.Ignored(manifest, clock())
        }
    }

    /** 启动提示已展示/关闭：清空一次性提示（不写忽略标记）。 */
    fun consumeAutoPrompt() {
        _autoPrompt.value = null
    }

    /**
     * 「清空所有数据」成功后重置**进程内**更新状态（v0.5.3，修）。
     *
     * 为什么需要：[cc.pulsez.app.data.ExportService.clearAll] 会经
     * `SettingsStore.wipeUserData()` 把 `update_ignored_version_code` 等持久化键一起清掉
     * （`app/src/main/java/cc/pulsez/app/data/settings/SettingsStore.kt:349-357`，
     * 调用点 `app/src/main/java/cc/pulsez/app/data/ExportService.kt:435`）；但本仓库是
     * `AppContainer` 级单例（`AppContainer.kt:422`），内存里的 [_state] 清空后仍是清空前的
     * [UpdateUiState.Ignored] —— 再进设置页会显示旧的「已跳过」（`ui/AppRoot.kt:129-130`
     * 订阅、`ui/copy/UpdateCopy.kt:89-90` 渲染）。
     *
     * 语义 = 回到「本次进程还没查过」：设置页那行只显示当前版本名
     * （[cc.pulsez.app.ui.copy.UpdateCopy.rowValue] 的 Idle 分支只返回版本名，不谎报「已是最新」）。
     *
     * **只改内存、不写任何持久化键**：持久化由清空流程自己负责；这里再写一次会破坏
     * `wipeUserData()` 的单次 edit 语义。也**不**把状态改成 UpToDate/Checking。
     *
     * v0.5.4：同时**自增内部清空代际**。清空前发起、清空后才返回的 `checkOnLaunch` /
     * `checkNow` 会在落状态前比对代际并整份丢弃——否则它会用清空前的设置快照（其中
     * `ignoredVersionCode` 还是旧值）重新发布 `Ignored`，并重写 `lastCheckAt`，
     * 与手册 §2.2 的残余描述（"不是旧「已跳过」"）冲突。
     *
     * 调用时机：`AppRoot.runClear` **进入执行的那一刻**（早于
     * `ExportService.clearAll`），不再等成功——这样 clearAll 执行期间返回的在途检查也被代际
     * 挡住，不会出现「DB/持久键已清、检查结果后到又被写回」。清空失败时代价只是本行回到
     * 「只显示当前版本」（内存复位，持久键未动），失败对话框照旧显示「没清干净 + 再试一次」。
     *
     * 不取 [checkMutex]：本方法由 UI 在清空开始执行时同步调用，不能为等一次在途网络检查而
     * 卡住「回首页」。这里只取非挂起的 [publishLock]，与检查侧最终代际复核和内存发布互斥；
     * 网络请求、DataStore 写入和 PackageManager IPC 都不在该锁内。
     *
     * 调用点：`app/src/main/java/cc/pulsez/app/ui/AppRoot.kt` 的 `runClear`（清空执行起点，
     * 早于 `ExportService.clearAll`）；**不**改清空流程本身。
     */
    fun resetAfterWipe() {
        synchronized(publishLock) {
            wipeEpoch.incrementAndGet()
            _state.value = UpdateUiState.Idle
            _autoPrompt.value = null
        }
    }
}
