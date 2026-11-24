package cc.pulsez.app.audio

/**
 * 自动朗读的「页面 + 前台」可见性门（修）。
 *
 * 为什么需要它：`ChatViewModel` 在 `LlmEvent.Finished` 里触发自动朗读，但它在 ViewModel 里
 * 看不到「当前是否在**这个角色**的 1:1 聊天页」「App 是否在前台」——离开聊天页/退后台/锁屏/
 * 切到另一个角色之后才完成的回复，原先仍会开始朗读（隐私与体验问题，违反
 * 「AppRoot 页面切换时 stop」与 §6 验收 4「切页/发消息停止」的用户预期）。
 *
 * 写入方（都只写不读）：
 * - `AppRoot` 的 Lifecycle 观察者：`ON_START` → [setAppForeground]`(true)`，
 *   `ON_STOP`（退后台/锁屏）→ `(false)`；
 * - `AppRoot` 的 `LaunchedEffect(nav.current)`：`Route.Chat(id)` → [setVisibleChatCharacter]`(id)`，
 *   其它路由（首页/设置/记忆/群聊/导入…）→ `(null)`。
 *
 * 读取方：`ChatViewModel.maybeAutoRead`（先 [canAutoRead]，再把 [snapshot] 交给
 * [ReadPolicy.planForFinishedReply] 用**最新**快照再判一次）。
 *
 * 线程模型（依据 说明选择理由）：
 * - 写入只在主线程（Lifecycle 回调与 Compose `LaunchedEffect`）；读取也在主线程
 *   （`maybeAutoRead` 跑在 `viewModelScope` = `Dispatchers.Main.immediate`），本来没有并发；
 * - 仍然用 `@Volatile` 保存**一个不可变快照**而不是两个布尔字段：
 *   ① 保证「前台/可见角色」这对状态成对一致（不会读到“前台=true 但页面=刚离开”的半新半旧值）；
 *   ② 万一将来读取挪到后台线程（例如 TTS 回调里）也有可见性保证；
 * - 不用锁：状态是小的不可变 `data class`，单次 volatile 赋值即可；锁只会给主线程读增加成本。
 *
 * 纯 Kotlin（不 import Android），可直接 JVM 单测（`ReadVisibilityTest`）。
 */
class ReadVisibility {

    /**
     * 某一时刻的可见性快照。
     *
     * @param appForeground App 是否在前台（Lifecycle STARTED）。初值 false = 保守：没被显式
     *   标记前台就不读（`ON_START` 会在注册观察者时补齐，不会漏掉正常路径）。
     * @param visibleChatCharacterId 当前显示的 1:1 聊天页对应角色 id；不在聊天页时为 null。
     */
    data class Snapshot(
        val appForeground: Boolean,
        val visibleChatCharacterId: String?,
    ) {
        /** 是否允许为 [characterId] 这个角色自动朗读：必须「本角色聊天页正显示」且「App 在前台」。 */
        fun canAutoRead(characterId: String): Boolean =
            appForeground && visibleChatCharacterId == characterId

        /** 交给 [ReadPolicy] 的纯判定参数（两个条件分开传，便于单测逐条覆盖）。 */
        internal fun pageContext(characterId: String): ReadPolicy.PageContext = ReadPolicy.PageContext(
            pageVisible = visibleChatCharacterId == characterId,
            appForeground = appForeground,
        )
    }

    /** 当前快照（volatile：写入主线程，读取可安全跨线程）。 */
    @Volatile
    var snapshot: Snapshot = Snapshot(appForeground = false, visibleChatCharacterId = null)
        private set

    /** `ON_START` → true；`ON_STOP`（退后台/锁屏）→ false。 */
    fun setAppForeground(foreground: Boolean) {
        // 快照是不可变 data class，整对象替换；没必要判断「值没变就不写」，写入频率极低
        //（只在启动/退后台/回前台各一次）。
        snapshot = snapshot.copy(appForeground = foreground)
    }

    /**
     * 导航变化时写入：`Route.Chat(characterId)` → 该 id；其它任何路由 → null。
     *
     * 用 id 而不是布尔值：切到**另一个角色**的聊天页时，上一个角色缓存着的聊天 ViewModel
     * 仍在跑流（按角色 key 缓存，切页不 onCleared），它的回复必须因为「id 对不上」而不读。
     */
    fun setVisibleChatCharacter(characterId: String?) {
        snapshot = snapshot.copy(visibleChatCharacterId = characterId)
    }

    /** 快捷门：`ChatViewModel` 的 `LlmEvent.Finished` 里先调它，省掉不必要的一次设置读取。 */
    fun canAutoRead(characterId: String): Boolean = snapshot.canAutoRead(characterId)
}
