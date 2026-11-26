package cc.pulsez.app.ui

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.pulsez.app.AppContainer
import cc.pulsez.app.BuildConfig
import cc.pulsez.app.audio.ReaderStatus
import cc.pulsez.app.compliance.UsageClock
import cc.pulsez.app.data.ExportResult
import cc.pulsez.app.data.ExportService
import cc.pulsez.app.data.settings.ImageEntryState
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.data.settings.imageEntryState
import cc.pulsez.app.data.settings.isDeepSeekProvider
import cc.pulsez.app.importer.BackupParser
import cc.pulsez.app.importer.BoundedReadResult
import cc.pulsez.app.importer.CharsetDetector
import cc.pulsez.app.importer.Source
import cc.pulsez.app.importer.deepseek.ImportCache
import cc.pulsez.app.ui.chat.ChatScreen
import cc.pulsez.app.ui.chat.ChatViewModel
import cc.pulsez.app.ui.chat.VoiceActions
import cc.pulsez.app.ui.chat.VoicePermissionResult
import cc.pulsez.app.ui.config.ConfigScreen
import cc.pulsez.app.ui.config.ConfigViewModel
import cc.pulsez.app.ui.config.CredentialCopy
import cc.pulsez.app.ui.config.FindOldThingsConfigScreen
import cc.pulsez.app.ui.gate.GateScreen
import cc.pulsez.app.ui.group.GroupChatScreen
import cc.pulsez.app.ui.group.GroupChatViewModel
import cc.pulsez.app.ui.group.GroupMemberUi
import cc.pulsez.app.ui.group.GroupPickerSheet
import cc.pulsez.app.ui.group.GroupPickerViewModel
import cc.pulsez.app.ui.importer.DeepSeekImportScreen
import cc.pulsez.app.ui.importer.DeepSeekImportViewModel
import cc.pulsez.app.ui.importer.ImportScreen
import cc.pulsez.app.ui.importer.ImportViewModel
import cc.pulsez.app.ui.legal.LegalScreen
import cc.pulsez.app.ui.memory.MemoryScreen
import cc.pulsez.app.ui.memory.MemoryViewModel
import cc.pulsez.app.ui.settings.SettingsScreen
import cc.pulsez.app.ui.settings.UsageScreen
import cc.pulsez.app.ui.settings.UsageViewModel
import cc.pulsez.app.ui.settings.UpdateAvailableDialog
import cc.pulsez.app.ui.settings.copyDownloadUrl
import cc.pulsez.app.ui.threads.AllCharactersScreen
import cc.pulsez.app.ui.threads.ThreadsScreen
import cc.pulsez.app.ui.threads.ThreadsViewModel
import cc.pulsez.app.ui.copy.BackupCopy
import cc.pulsez.app.ui.copy.ClearCopy
import cc.pulsez.app.ui.copy.ReadCopy
import cc.pulsez.app.update.UpdateLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 根界面与导航。
 *
 * 交互规范（依据 Android 官方指南，见）：
 * 1. **系统返回手势必须始终可用**：非首屏时返回上一层，首屏时交给系统退出应用。
 *    全局导航用 [PredictiveBackHandler] 接入（手势进行中跟手位移、松手才真正返回，见下方
 *    `PredictiveBackHandler(enabled = nav.canGoBack)`），manifest 已打开
 *    `enableOnBackInvokedCallback`；**只有**首启门槛分支里的协议页用 [BackHandler]
 *    （门槛分支提前 `return@Surface`，不参与全局预测返回动画，见门槛分支注释）。
 * 2. **edge-to-edge + insets**：targetSdk 36 强制 edge-to-edge。主内容容器与首启门槛分支
 *    **同一套口径**：都用 `systemBars`（状态栏/导航条）+ `displayCutout`（刘海）两处
 *    `windowInsetsPadding` 给内容加内边距；**有意不含 IME**——键盘弹出时不压缩整页
 *    （依据 insets）。门槛分支自己那一层在 `return@Surface` 之前，不在主容器内，
 *    所以必须单独加；主容器内的 `Route.Legal` 不再重复加（否则会双倍留白）。
 * 3. **导航层级**：上一层的进入动画从右侧滑入，返回从左侧滑出，方向和手势一致。
 * 4. 屏幕内的返回按钮只是"可见的入口"，不是唯一入口——手势才是主路径。
 *
 * ## 分享入口（v0.5.6）
 * [shareEvents] 是 `MainActivity` 的 **Activity 级事件流**（`StateFlow<String?>`）。为什么不再是
 * 一次性参数 `initialShareText`：`onNewIntent` 只改普通字段时 Compose 观察不到，已在前台的
 * Activity 再分享**不同**文本会丢失。改成事件流后，`onNewIntent` 的写入
 * 会产生可观察变化，[AppRoot] 据此压入导入页（冷启动在导航初始化里压、温启动由效应压，
 * 都是 `push` 不是 `reset`，保留「首页为根、返回手势回首页」的返回栈语义），并按文本去重后
 * 把文本交给导入页 VM。默认值只为「不经过分享的调用点/预览」保持可编译；生产始终由
 * `MainActivity` 传同一个实例。
 */
@Composable
fun AppRoot(
    container: AppContainer,
    shareEvents: StateFlow<String?> = MutableStateFlow<String?>(null),
) {
    val settings by container.settings.flow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 「检查更新」状态（v0.5.1）：设置页顶部行 + 启动时的一次性非阻断提示都读它。
    // 检查动作本身由 PulsezApp.onCreate 的后台静默检查 / 设置页手动检查触发；这里只订阅，不主动联网。
    val updateState by container.updateRepository.state.collectAsState()
    val autoUpdatePrompt by container.updateRepository.autoPrompt.collectAsState()

    // ---- v0.5.1 语音：麦克风权限 launcher + 本机麦克风能力 ----
    // 系统权限框必须由 Activity 发起：launcher 放在根组合（AppRoot），ChatViewModel 只发
    // 「请弹一次」信号（permissionLaunchToken），避免把 Activity/Launcher 塞进 ViewModel。
    val hasMicrophone = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
    var voicePermissionResult by remember { mutableStateOf<VoicePermissionResult?>(null) }
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 「永久拒绝」的判定要用 Activity 的 shouldShowRequestPermissionRationale；
        // 第一次拒绝时它也返回 false，ViewModel 会结合「是否请求过」区分（VoicePermissionPolicy）。
        val rationale = findComponentActivity(context)
            ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            ?: false
        voicePermissionResult = VoicePermissionResult(granted = granted, rationaleVisible = rationale)
    }
    var confirmClear by remember { mutableStateOf(false) }
    // 清空失败状态（v0.5.2）：与 confirmClear / gateLegal 并列。
    // 失败不许静默回首页，必须弹「没清干净」+「再试一次」。
    var clearFailed by remember { mutableStateOf(false) }
    // 清空成功后首页必须从「空」开始渲染，避免显示会话内缓存的旧状态。
    // 首页 VM 是 Activity 级；清空成功回首页时用它换代重建，首帧就不会先闪清空前的角色卡。

    // v0.5.5（+）：必须用 rememberSaveable，不能再用 remember。
    // `AndroidManifest.xml:69` 的 configChanges 不含 fontScale/locale/density 等，这些配置变化会
    // **重建 Activity**；重建时 ViewModelStore 保留（旧 key 的 VM 都还在），而 `remember` 归零 →
    // 本 token 回退到旧值，`viewModel(key=…)` 重新命中清空前的旧 VM（旧预览可被恢复）。
    // `rememberSaveable` 让 token 跨重建保留；同仓库既有用法见 ChatScreen.kt:270/282。

    var homeRebuildToken by rememberSaveable { mutableStateOf(0) }
    // 清空代际（v0.5.4 v0.5.6 起改为**全局**代际）。
    // 在用户确认、**开始执行**清空的那一刻自增（`AppContainer.notifyUserDataWiped()`，早于
    // `ExportService.clearAll`，见下方 runClear），作为 Import / DeepSeekImport / Config /
    // FindOldThings / Memory / Chat / GroupChat 路由 ViewModel 的 key 后缀。
    // 为什么需要：`viewModel(factory=…)` 无 key 时按类名复用 Activity 级 VM，清空后重进导入页
    // 仍拿着清空前的「待恢复备份预览」、重进配置页仍保留旧地址/模型输入。
    // v0.5.6：这里**不再用 AppRoot 本地的 `wipeToken`**。`MainActivity` 无 launchMode
    // （standard），MAIN/LAUNCHER + SEND + PROCESS_TEXT 三个入口允许并存多个 Activity 实例；
    // 本地 token 是每个 AppRoot 各自的 `rememberSaveable`，只有「发起清空的那个实例」换代，
    // 另一个仍存活的实例继续用旧 key 命中清空前的旧 ImportViewModel（旧备份预览仍可恢复）。
    // 改读 `AppContainer.wipeEpoch`：一个进程级 StateFlow，所有 Activity 的 AppRoot 都观察它，
    // 清空一开始全部实例一起换代（旧 VM 自己订阅 wipeEpoch，也会取消在途识别/生成）。
    // 依据 §。
    val wipeEpoch by container.wipeEpoch.collectAsState()
    // 系统分享事件（v0.5.6）：`MainActivity.onNewIntent` 写入 StateFlow → 这里观察到变化
    // 后压入导入页并按文本去重注入（同一份文本只注入一次，见 ShareConsumption）。
    val shareText by shareEvents.collectAsState()
    // 分享文本「一次性消费」记号（v0.5.5）：按**文本内容**去重，只有「非空、且不等于上次
    // 已消费的文本」才交给导入页 VM（纯判定见 ShareConsumption.shouldInject）。
    // 为什么在 AppRoot 组合层：Activity 因字体/语言/密度重建时会从同一个 Intent 再取一次文本
    //（现在 `onNewIntent` 会 `setIntent`，重建取到的是**最近一次**分享，见 MainActivity 注释）；
    // 而清空换代/重建后是**新 ImportViewModel**，实例级的「已消费」标记拦不住同一份旧备份被
    // 重新解析成待恢复预览。
    // 为什么 rememberSaveable：记号本身也必须跨 Activity 重建保留（重建 ≠ 用户重新分享）。
    var lastConsumedShareText by rememberSaveable { mutableStateOf<String?>(null) }
    // 启动提示框的浏览器打不开失败态（v0.5.2）：必须写在组合顶层，
    // 不能放进 `if (updatePrompt != null)` 里，否则条件外会被重置。

    var startUpdateDownloadFailed by remember { mutableStateOf(false) }
    var showTimeReminder by remember { mutableStateOf(false) }
    var gateLegal by remember { mutableStateOf(false) }

    // 连续使用提醒：只累计前台时间。
    val usageClock = remember { UsageClock() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    usageClock.onForeground(SystemClock.elapsedRealtime())
                    // 自动朗读的「前台」标记（ 修 退后台/锁屏后
                    // 才完成的新回复不能开读）。ON_START 在注册观察者时会补发，正常路径不会漏。
                    container.readVisibility.setAppForeground(true)
                    // 之前初始化失败过（UNAVAILABLE）的，回到前台重试一次。
                    // 覆盖「用户去系统设置/朗读引擎界面装完中文语音包再切回来，进程没死」的场景。
                    // 口径说明（保守取舍） 字面是「自动朗读开时重试」，这里放宽成
                    // 「只要状态是 UNAVAILABLE 就重试」——UNAVAILABLE 本身说明用户已经碰过朗读
                    //（进过设置或开过开关），且设置页新增的「去装语音包」入口要这条才能兑现“回来再试一次”；
                    // IDLE/READY 不动，不会给从没碰过朗读的人冷启动就 bind TTS
                    //（设置页初始化时机是 的产品判断 不扩大懒加载）。
                    if (container.reader.state.value.status == ReaderStatus.UNAVAILABLE) {
                        container.reader.ensureInitialized()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    usageClock.onBackground(SystemClock.elapsedRealtime())
                    // 退到后台/锁屏：先置前台标记再停，两者都做：
                    // - 置 false 挡住之后才完成的新回复；
                    // - stop() 停掉已经在播的那条。
                    // 超出 字面停止条件的补充 要求「切后台/锁屏/来电」停读，
                    // 避免锁屏后继续出声/耗电。未真机验证：Android 12+ 后台音频焦点/锁屏行为。
                    container.readVisibility.setAppForeground(false)
                    container.reader.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // AppRoot 离开组合（Activity 销毁/重建）：先清可见性门，避免旧页面状态残留在
            // AppContainer 里被下一次 `LlmEvent.Finished` 误读（的保守兜底）。
            container.readVisibility.setAppForeground(false)
            container.readVisibility.setVisibleChatCharacter(null)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            if (usageClock.shouldRemind(SystemClock.elapsedRealtime())) showTimeReminder = true
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        val current = settings
        if (current == null) {
            Box(Modifier.fillMaxSize())
            return@Surface
        }

        // 门槛判据（v0.5.2）：**必须与
        // `update/UpdatePrefs` 用同一个 `needsAgreement`**。旧口径 `agreedVersion == 0` 会让
        // v0.5.0/v0.5.1 的升级用户直接跳过新增的出网/清空说明（协议版本 1 → 2）。
        if (SettingsStore.needsAgreement(current.agreedVersion)) {
            // （v0.5.4）：门槛分支在 `return@Surface` 之前，**不在**
            // 下方主容器的 insets Box 内；而 `MainActivity` 是 `enableEdgeToEdge()`。所以这里必须
            // 单独加与主容器**同一套**内边距（systemBars + displayCutout，有意不含 IME）：
            // 不加的话门槛内协议页的 48dp 返回按钮会落在状态栏/刘海区域。
            // 口径必须与主容器逐字一致（同样两处 `windowInsetsPadding`），不另造固定 dp 顶边距。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .windowInsetsPadding(WindowInsets.displayCutout),
            ) {
                // 门槛页内打开协议后：系统返回**回门槛页**，不能直接退出 App。
                // 只注册 BackHandler、不接预测返回动画——门槛分支不参与下方 `PredictiveBackHandler`，
                // 注册位置必须在 `return@Surface` 之前、门槛分支内。
                if (gateLegal) {
                    BackHandler { gateLegal = false }
                    LegalScreen(onBack = { gateLegal = false })
                } else {
                    GateScreen(
                        settings = current,
                        onAgree = {
                            scope.launch { container.settings.agree(SettingsStore.AGREEMENT_VERSION) }
                        },
                        onUnderage = { },
                        onOpenAgreement = { gateLegal = true },
                    )
                }
            }
            return@Surface
        }

        // 导航栈初始只有首页（v0.5.6）。冷启动带分享时，这里就把导入页压在首页之上：
        // 这样**首帧**就是导入页（不会先闪一帧首页动画），返回手势回首页而不是退出。
        // 温启动的新分享不经过这里，由下面的分享效应压栈（`onNewIntent` 更新事件流后重组）。
        val nav = remember {
            NavState(Route.Threads).apply {
                // 来自系统分享/选中文字时，把导入页压在首页之上：
                // 返回手势会回首页，而不是直接退出（外部入口必须合成返回栈）。
                if (ShareConsumption.shouldOpenImportPage(shareText, alreadyOnImport = false)) {
                    push(Route.Import)
                }
            }
        }

        // 分享 → 导入页（温启动入口，v0.5.6）：
        // `MainActivity.onNewIntent` 更新事件流 → 这里观察到新文本 → 若当前不在导入页就压入。
        // 冷启动的首次压栈已由上面的 nav 初始化完成（当前已是导入页时这里不重复压栈，
        // 否则一次分享会叠两层导入页、返回要按两次）。
        LaunchedEffect(shareText) {
            if (ShareConsumption.shouldOpenImportPage(shareText, nav.current == Route.Import)) {
                nav.push(Route.Import)
            }
        }

        // 清空执行器（v0.5.2）：确认框按钮与失败对话框的
        // 「再试一次」**共用这同一个 lambda**，禁止复制两份逻辑；成功/失败落点由
        // `ClearFlow.clearOutcomeAfter` 纯函数决定（AppRoot 不在这里分支写死）。

        // 清空代际和相关内存状态在清空开始时复位：
        // 「清空代际 + 相关内存状态复位」从**清空成功后**提前到**用户确认、进入执行的这一刻**
        // （早于 `ExportService.clearAll`）。理由：`clearAll` 本身要跑 DB 清表 + DataStore 写 +
        // 文件删除（几十~几百 ms），等它返回才发信号，期间迟到的生成/导入/更新检查/记忆写入可能
        // 落在「DB 已清、信号未发」之间，被写回已清空的库。
        // 清空**失败**时内存状态/VM 已复位、在途任务已取消；但**不承诺数据完整**——
        // `clearAll` 是分步执行的（库先清，见 `ExportService.kt:433` 第②步 `database.clearAllTables()`；
        // 之后 ⑤ 密钥回读校验、⑦ 附件目录、⑧ 缓存目录都可能抛），前面的步骤**可能已经删掉部分数据**，
        // 且没有统一回滚。失败对话框照旧显示
        // 「有几项没删掉 + 再试一次」，重试走同一个 lambda（会再自增一次代际，无害）。
        // 失败时不留「已清空」假象：首页换代 `homeRebuildToken` 仍只在成功分支做。
        val runClear: () -> Unit = {
            confirmClear = false
            clearFailed = false
            // H：清空执行前先停朗读（清空会删数据，正在读的回复不该继续念）。
            container.reader.stop()
            // （v0.5.4）：进入执行的那一刻就贴「清空代际」并复位相关状态，
            // 这样 clearAll 执行期间的在途写入/检查也按「清空已开始」处理、不会写回：
            // ① 自增清空代际 → ChatViewModel / GroupChatViewModel 立刻取消在途生成；
            //    ChatEngine / ImportTaskStore / MemoryViewModel / ImportViewModel / 群聊记账
            //    的守卫在落库前比对代际，命中即丢弃；
            // ② 复位进程级导入任务（取消在途 job + 回 Idle）；
            // ③ 复位更新检查内存状态（在途检查结果作废，不重发旧「已跳过」/不写 lastCheckAt）；
            // ④ 不需要再动 AppRoot 本地 token：上方 `wipeEpoch` 直接读全局代际，①自增后**所有**
            //    Activity 实例的导入/配置/聊天/群聊/记忆页都会换代拿到全新 VM（v0.5.6）。
            container.notifyUserDataWiped()
            container.importTasks.reset()
            container.updateRepository.resetAfterWipe()
            scope.launch {
                val success = runCatching { ExportService.clearAll(container) }.isSuccess
                when (clearOutcomeAfter(success)) {
                    ClearOutcome.RETURN_HOME -> {
                        clearFailed = false
                        // 首页 VM 换代重建：首帧就是空列表，不会先闪一帧清空前的角色卡。
                        // （只有真成功才做：失败时首页保留原 VM，用户还能看到清空前的角色卡；
                        // 但这**不表示**数据完整——失败可能已部分删除，见上方 runClear 的 勘误。）
                        homeRebuildToken += 1
                        nav.reset(Route.Threads)
                    }
                    ClearOutcome.SHOW_FAILURE -> clearFailed = true
                }
            }
        }

        // 导航变化：维护自动朗读的「可见聊天页」标记，并停掉已离开页面的朗读。
        // 依据 修 ——「切页停止」只停“那一刻正在播的那条”，
        // 挡不住之后才到达 `LlmEvent.Finished` 的新回复；所以导航层必须同时写可见性门。
        // - `Route.Chat(characterId)` → 记为该角色可见（切到别的角色时 id 对不上，旧会话的回复不读）；
        // - 其它路由（首页/设置/记忆/群聊/导入…）→ null。
        // 退后台/锁屏由 Lifecycle 的 ON_STOP 单独维护（的另一半）。
        // 依据「切会话/页面销毁 → stop」；ChatViewModel 按角色缓存，
        // 切页不保证 onCleared，所以必须在导航这一层停。
        LaunchedEffect(nav.current) {
            val route = nav.current
            container.readVisibility.setVisibleChatCharacter((route as? Route.Chat)?.characterId)
            if (route !is Route.Chat) {
                container.reader.stop()
                // 不在聊天页时丢弃迟到的系统权限回调，避免下一个会话页收到不属于它的结果。
                voicePermissionResult = null
            }
        }

        // 「一起聊」成员选择弹层：
        // 选择状态放在导航之上，弹层开着时切页也不会丢。
        val groupPickerVm: GroupPickerViewModel = viewModel(
            factory = GroupPickerViewModel.factory(container),
        )
        val groupPickerState by groupPickerVm.state.collectAsState()
        var showGroupPicker by remember { mutableStateOf(false) }

        // 预测性返回：手势进行中跟手位移，松手完成才真正回上一层。
        // 系统预测动画在注册回调后由应用接管，这里自己驱动进度，避免"滑到一半没反应"。
        var backProgress by remember { mutableStateOf(0f) }
        val animatedBack by androidx.compose.animation.core.animateFloatAsState(
            targetValue = backProgress,
            animationSpec = tween(120),
            label = "back",
        )
        PredictiveBackHandler(enabled = nav.canGoBack) { progress ->
            try {
                progress.collect { event -> backProgress = event.progress.coerceIn(0f, 1f) }
                nav.back()
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 手势取消：不动导航
            } finally {
                backProgress = 0f
            }
        }

        // 导出（v0.5.2 前半）：生成/写出/线程都在 `ExportService.exportTo`（IO）；
        // 这里只负责按结果弹确定性回执。`uri == null` = 用户取消保存，静默（B-5）。
        val exportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val result = ExportService.exportTo(container) { context.contentResolver.openOutputStream(uri) }
                // 顺序固定：超限（含孤儿追加）→ 孤儿跳过 → 普通成功；失败各有专用句。
                val (text, long) = when (result) {
                    is ExportResult.Success -> when {
                        result.overLimit.isNotEmpty() ->
                            BackupCopy.exportOverLimit(result.overLimit.first(), result.skippedOrphanThreads) to true
                        result.skippedOrphanThreads > 0 ->
                            BackupCopy.exportSkippedOrphans(result.skippedOrphanThreads) to true
                        else -> BackupCopy.EXPORT_SUCCESS to false
                    }
                    ExportResult.WriteFailed -> BackupCopy.EXPORT_WRITE_FAILED to false
                    ExportResult.DataFailed -> BackupCopy.EXPORT_DATA_FAILED to false
                }
                Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // edge-to-edge：系统栏与刘海用内边距；IME 不在这里处理，
                // 否则键盘一弹整个页面被压缩（insets）。
                .windowInsetsPadding(WindowInsets.systemBars)
                .windowInsetsPadding(WindowInsets.displayCutout),
        ) {
            AnimatedContent(
                targetState = nav.current,
                modifier = Modifier.graphicsLayer {
                    translationX = animatedBack * size.width * 0.28f
                    alpha = 1f - animatedBack * 0.15f
                },
                transitionSpec = {
                    val duration = 220
                    if (nav.lastMoveWasBack) {
                        (slideInHorizontally(tween(duration)) { -it / 6 } + fadeIn(tween(duration)))
                            .togetherWith(slideOutHorizontally(tween(duration)) { it / 4 } + fadeOut(tween(duration)))
                    } else {
                        (slideInHorizontally(tween(duration)) { it / 6 } + fadeIn(tween(duration)))
                            .togetherWith(slideOutHorizontally(tween(duration)) { -it / 4 } + fadeOut(tween(duration)))
                    }
                },
                label = "route",
            ) { route ->
                when (route) {
                    Route.Threads -> {
                        // key 随 homeRebuildToken 变化 → 清空成功后换一个全新 VM（首帧空表）。
                        // 正常回首页 token 不变，仍是同一个 VM + 既有的 onRefresh 刷新（行为不变）。
                        val vm: ThreadsViewModel = viewModel(
                            key = "threads-home-$homeRebuildToken",
                            factory = ThreadsViewModel.factory(container),
                        )
                        val uiState by vm.state.collectAsState()
                        ThreadsScreen(
                            cards = uiState.cards,
                            memoryCharacterCount = uiState.memoryCharacterCount,
                            groups = uiState.groups,
                            onOpen = { characterId -> nav.push(Route.Chat(characterId)) },
                            onAdd = { nav.push(Route.Import) },
                            // 历史群入口：退出群聊后从首页还能回去
                            onOpenGroup = { threadId -> nav.push(Route.GroupChat(threadId)) },
                            onCreateGroup = {
                                // 每次打开前重新拉角色列表：刚建好的角色要立刻能选
                                groupPickerVm.refresh()
                                showGroupPicker = true
                            },
                            onOpenSettings = { nav.push(Route.Settings) },
                            onRefresh = vm::refresh,
                            totalCharacterCount = uiState.totalCharacterCount,
                            onOpenAllCharacters = { nav.push(Route.AllCharacters) },
                        )
                    }

                    Route.AllCharacters -> {
                        val vm: ThreadsViewModel = viewModel(
                            key = "threads-home-$homeRebuildToken",
                            factory = ThreadsViewModel.factory(container),
                        )
                        val uiState by vm.state.collectAsState()
                        LaunchedEffect(nav.lastMoveWasBack) {
                            if (nav.lastMoveWasBack) vm.refresh()
                        }
                        AllCharactersScreen(
                            items = uiState.directoryItems,
                            onOpen = { characterId -> nav.push(Route.Chat(characterId)) },
                            onBack = { nav.back() },
                            query = uiState.directoryQuery,
                            sort = uiState.directorySort,
                            onQueryChange = vm::updateDirectoryQuery,
                            onSortChange = vm::updateDirectorySort,
                            onDelete = vm::deleteCharacters,
                            deletionNotice = uiState.deletionNotice,
                            deleting = uiState.deletingCharacters,
                            onDismissDeletionNotice = vm::dismissDeletionNotice,
                        )
                    }

                    Route.Import -> {
                        // v0.5.4 key 带清空代际 → 清空后再进导入页是**全新 VM**，
                        // 不会拿着清空前的「待恢复备份预览 / 草稿」一键恢复旧内容。
                        // （旧写法无 key，Activity ViewModelStore 按类名复用同一实例。）
                        // v0.5.6：代际读全局 `container.wipeEpoch`，**任意 Activity 实例**
                        // 都会在清空后换代，不再只换代发起清空的那个实例。
                        val vm: ImportViewModel = viewModel(
                            key = "import-$wipeEpoch",
                            factory = ImportViewModel.factory(container),
                        )
                        val state by vm.state.collectAsState()

                        // 分享注入（v0.5.2 前半：只交给 VM 识别，**不预写 raw**；
                        // v0.5.5 消费记号在 AppRoot 组合级 + rememberSaveable；v0.5.6
                        // 输入来自 Activity 级事件流，温启动的不同文本也能到达这里）。
                        // 只靠 VM 里的「已识别过的分享文本」不够：清空换代或 Activity 重建后是**新 VM**，
                        // 同一份旧分享文本会被重新解析出清空前的备份预览、仍可一键恢复。
                        // 所以这里按文本去重（纯判定见 ShareConsumption.shouldInject），同一份文本只注入一次；
                        // 判定不 trim/不改写原文，注入的仍是事件流里的原文。
                        // key 带 vm：清空换代后是全新 VM，重新跑一次判定；同一份旧文本仍被
                        // `lastConsumedShareText` 拦下（不会把清空前的旧备份再解析出来）。
                        // 已消费的边界：同一份文本第二次分享会被忽略（可接受，见 ShareConsumption KDoc）；
                        // 同一 Activity 内再次分享**不同**文本经事件流照常生效（修复点）。
                        LaunchedEffect(shareText, vm) {
                            val share = shareText
                            if (share != null &&
                                ShareConsumption.shouldInject(share, lastConsumedShareText)
                            ) {
                                lastConsumedShareText = share
                                vm.onShareText(share)
                            }
                        }

                        // 创建完成后跳聊天并消费信号；不能把导航写在 composition 里，
                        // 也不能不消费（否则再次进入导入页会立刻跳回上一个角色）。
                        LaunchedEffect(state.createdId) {
                            val created = state.createdId
                            if (created != null) {
                                vm.consumeCreated()
                                nav.replace(Route.Chat(created))
                            }
                        }

                        // 文件入口（v0.5.2 前半）：SIZE 只作提前拒绝 → 有界读 →
                        // **恰好一次** decode → 无条件交 VM（空白由 `inspectAndParse` 首行守卫静默 return）。
                        // 不预写 raw、不用无界 `readBytes`；读失败/太大不调用 VM。
                        val filePicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetContent(),
                        ) { uri: Uri? ->
                            uri ?: return@rememberLauncherForActivityResult
                            scope.launch {
                                when (val picked = readPickedText(context, uri)) {
                                    is PickedTextResult.Text -> vm.inspectAndParse(picked.text, Source.FILE)
                                    PickedTextResult.TooLarge ->
                                        Toast.makeText(context, BackupCopy.READ_FILE_TOO_LARGE, Toast.LENGTH_LONG).show()
                                    PickedTextResult.IoError ->
                                        Toast.makeText(context, BackupCopy.READ_FILE_FAILED, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        ImportScreen(
                            state = state,
                            onBack = { nav.back() },
                            onRawChange = vm::onRawChange,
                            onPickFile = { filePicker.launch("*/*") },
                            onParse = { vm.parse(Source.PASTE) },
                            onNameChange = vm::onNameChange,
                            onPersonaChange = vm::onPersonaChange,
                            onChooseSpeaker = vm::chooseSpeaker,
                            // 快速克隆：把当前草稿的 name/persona 预填成新角色的起点
                            onCloneLike = { vm.prefill(name = state.name, persona = state.persona) },
                            onCreate = vm::create,
                            // 旧聊天（ZIP）走独立页面；这里只负责压栈，返回手势回本页
                            onOpenZipImport = { nav.push(Route.DeepSeekImport) },
                            // 备份两态的独立出口：换文件先回初始步再选，避免编辑步草稿被清；
                            // onPickFile 保持原样，不包 resetToEdit。
                            onPickAnotherFile = { vm.resetToEdit(); filePicker.launch("*/*") },
                            onCancelBackup = vm::resetToEdit,
                            onRestore = vm::restore,
                            onRestoreFinished = { vm.resetToEdit(); nav.back() },
                        )
                    }

                    Route.DeepSeekImport -> {
                        // v0.5.4 key 带清空代际（清空后再进是全新表单 VM；v0.5.6 起为全局代际）。
                        val vm: DeepSeekImportViewModel = viewModel(
                            key = "deepseek-import-$wipeEpoch",
                            factory = DeepSeekImportViewModel.factory(container),
                        )
                        val form by vm.state.collectAsState()
                        val task by vm.taskState.collectAsState()

                        // SAF 选包：**在回调里立刻**把 Uri 拷进 cacheDir/import（阶段 1）——
                        // SAF 的临时授权只在回调期间可靠，交给 ViewModel 预检前必须是本地 File。
                        val zipPicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.OpenDocument(),
                        ) { uri: Uri? ->
                            uri ?: return@rememberLauncherForActivityResult
                            scope.launch {
                                try {
                                    val picked = withContext(Dispatchers.IO) {
                                        val file = ImportCache.copyToCache(
                                            resolver = context.contentResolver,
                                            uri = uri,
                                            dir = ImportCache.importDir(context.cacheDir),
                                        )
                                        file to displayNameOf(context.contentResolver, uri)
                                    }
                                    vm.onZipPicked(picked.first, picked.second)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    // 拷贝/空间/类型失败：统一交给人话映射，不在 UI 层拼异常文字。
                                    vm.onPickFailed(t)
                                }
                            }
                        }

                        DeepSeekImportScreen(
                            state = form,
                            task = task,
                            onBack = { nav.back() },
                            onPickZip = { zipPicker.launch(arrayOf("*/*")) },
                            onNameChange = vm::onNameChange,
                            onPersonaChange = vm::onPersonaChange,
                            onConsolidateChange = vm::onConsolidateChange,
                            // 「顺便让 TA 以后能按意思找到这些旧事」（默认开、可关；
                            // 没配「找旧事」时该行不显示）。
                            onEmbeddingChange = vm::onEmbeddingChange,
                            onRangeChange = vm::onRangeChange,
                            onResumeChoice = vm::onResumeChoice,
                            onStart = vm::start,
                            onConfirmConsent = vm::consent,
                            onCancel = vm::cancel,
                            onRetry = vm::retry,
                            // 导入完成：替换当前页，返回落回上一层而不是「正在导入」；
                            // 顺手清任务状态，下次从「新建角色 → 旧聊天」进来是干净的一页。
                            onOpenChat = { characterId ->
                                vm.reset()
                                nav.replace(Route.Chat(characterId))
                            },
                            onUndo = {
                                vm.undo {
                                    // 撤销后回首页刷新列表；已有 TA 与后续聊天由精确撤销逻辑保留。
                                    nav.reset(Route.Threads)
                                }
                            },
                            onDone = {
                                vm.reset()
                                nav.back()
                            },
                            onOpenSettings = { nav.push(Route.FindOldThings) },
                            onRefreshAi = vm::refreshAiReady,
                            onAddNewGroup = vm::addNewImportGroup,
                            onRemoveGroup = vm::removeImportGroup,
                            onGroupNameChange = vm::changeImportGroupName,
                            onGroupPersonaChange = vm::changeImportGroupPersona,
                            onAssignWindow = vm::assignWindow,
                            onAutoCluster = vm::autoCluster,
                            onCancelAutoCluster = vm::cancelAutoCluster,
                            onAwakenOnImportChange = vm::onAwakenOnImportChange,
                            onOpenChatConfig = { nav.push(Route.Config) },
                        )
                    }

                    // 聊天服务商设置页（用户指令 2026-11-21：与「找旧事」彻底分成两页）。
                    // 这里只接聊天三格 / 聊天示例 / 聊天测试 / 聊天保存。
                    Route.Config -> {
                        // v0.5.4 key 带清空代际（否则清空后重进仍是旧输入：地址/模型被
                        // `applyPersisted` 的 `ifBlank` 逻辑保留）。与 FindOldThings 共用同一个 key
                        // —— 拆页时两者刻意共用一个 ViewModel（见下方 Route.FindOldThings 注释）。
                        val vm: ConfigViewModel = viewModel(
                            key = "config-$wipeEpoch",
                            factory = ConfigViewModel.factory(container, container.tester),
                        )
                        val state by vm.state.collectAsState()
                        ConfigScreen(
                            state = state,
                            // 「深度思考 / 智能搜索」说明只对聊天地址有意义。
                            deepSeekProvider = current.isDeepSeekProvider(),
                            onBack = { nav.back() },
                            onBaseUrlChange = vm::onBaseUrlChange,
                            onApiKeyChange = vm::onApiKeyChange,
                            onModelChange = vm::onModelChange,
                            onApplyExample = vm::applyExample,
                            onTest = vm::test,
                            // v0.5.2：只有三步协议 + 回读校验
                            // 通过才返回 true；被拒/失败留在本页看错误文案，不再「先返回再说」。
                            onSave = {
                                scope.launch {
                                    if (vm.saveChat()) nav.back()
                                }
                            },
                            // E：清密钥成功才给轻提示；失败由本页错误文案承担（不静默）。
                            onClearChatKey = {
                                scope.launch {
                                    if (vm.clearChatKey()) {
                                        Toast.makeText(
                                            context,
                                            CredentialCopy.KEY_CLEARED_TOAST,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            // I：升级一次性提示「知道了」。
                            onAckKeyReset = { scope.launch { vm.ackKeyResetNotice() } },
                        )
                    }

                    // 「找旧事」独立设置页（用户指令 2026-11-21）。与聊天页共用一个 ViewModel，
                    // 但只读/只写 embed 那一组字段；保存走 saveEmbed，不碰聊天设置。
                    // v0.5.4 key 与 Route.Config 相同（仍是同一个 VM 实例），并带清空代际。
                    Route.FindOldThings -> {
                        val vm: ConfigViewModel = viewModel(
                            key = "config-$wipeEpoch",
                            factory = ConfigViewModel.factory(container, container.tester),
                        )
                        val state by vm.state.collectAsState()
                        FindOldThingsConfigScreen(
                            state = state,
                            onBack = { nav.back() },
                            onEmbedBaseUrlChange = vm::onEmbedBaseUrlChange,
                            onEmbedApiKeyChange = vm::onEmbedApiKeyChange,
                            onEmbedModelChange = vm::onEmbedModelChange,
                            onApplyEmbedExample = vm::applyEmbedExample,
                            onTestEmbed = vm::testEmbed,
                            // 同聊天页：协议 + 回读校验通过才返回；三格没填全留在本页（G）。
                            onSave = {
                                scope.launch {
                                    if (vm.saveEmbed()) nav.back()
                                }
                            },
                            // H：「停止使用找旧事」成功才给轻提示；失败留在本页可重试。
                            onDisconnectEmbed = {
                                scope.launch {
                                    if (vm.disconnectEmbed()) {
                                        Toast.makeText(
                                            context,
                                            CredentialCopy.DISCONNECTED_TOAST,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            // I：找旧事页的升级一次性提示「知道了」。
                            onAckKeyResetNotice = { scope.launch { vm.ackEmbedKeyResetNotice() } },
                        )
                    }

                    Route.Settings -> {
                        // 进设置「声音」组需要音色列表 / 是否可用：这里才初始化朗读器（懒加载，
                        // 没开自动朗读、也没进设置时不会占着 TTS 引擎）。
                        val readState by container.reader.state.collectAsState()
                        LaunchedEffect(Unit) { container.reader.ensureInitialized() }
                        SettingsScreen(
                            settings = current,
                            onBack = { nav.back() },
                            // 两个入口彻底分开（用户指令 2026-11-21）：
                            // 「聊天」→ 聊天设置页；「让 TA 更会找旧事」→ 找旧事设置页。
                            onOpenConnection = { nav.push(Route.Config) },
                            onOpenFindOldThings = { nav.push(Route.FindOldThings) },
                            onOpenUsage = { nav.push(Route.Usage) },
                            onWindowTierChange = { tier ->
                                scope.launch { container.settings.setWindowTier(tier) }
                            },
                            onReasoningEffortChange = { effort ->
                                scope.launch { container.settings.setReasoningEffort(effort) }
                            },
                            // 创作模式（v0.4）：
                            // 依赖 契约：`SettingsStore.setCreativeMode(...)` /
                            // `setCreativeModeIntroSeen(...)`；两个回调按以下顺序调用。
                            // 约定顺序是「先 setCreativeMode、再 setCreativeModeIntroSeen」——
                            // SettingsScreen 的「开启」按钮就是按这个顺序调两个回调的。
                            // 两次写入互不依赖、DataStore 自己串行落盘；顺序的意义是语义清晰：
                            // 先真开模式，再记"说明已看过"，不会出现"只记了看过、模式没开"。
                            onCreativeModeChange = { enabled ->
                                scope.launch { container.settings.setCreativeMode(enabled) }
                            },
                            onCreativeModeIntroSeen = {
                                scope.launch { container.settings.setCreativeModeIntroSeen(true) }
                            },
                            // 自动朗读（v0.5）：
                            // - 开启时顺手初始化引擎，设置页就知道能不能用/有哪些音色；
                            // - 关闭时立刻停读（开关关 = 不再出声，不等下一页）。
                            readState = readState,
                            onAutoReadChange = { enabled ->
                                scope.launch {
                                    container.settings.setAutoRead(enabled)
                                    if (enabled) container.reader.ensureInitialized() else container.reader.stop()
                                }
                            },
                            onReadIntroSeen = {
                                scope.launch { container.settings.setReadIntroSeen(true) }
                            },
                            onReadRateChange = { rateX100 ->
                                scope.launch { container.settings.setReadRateX100(rateX100) }
                            },
                            onReadVoiceChange = { voiceId ->
                                scope.launch { container.settings.setReadVoiceId(voiceId) }
                            },
                            onOpenAgreement = { nav.push(Route.Legal) },
                            onExport = { exportLauncher.launch("PulseZ-backup.json") },
                            onClearAll = { confirmClear = true },
                            // 检查更新（v0.5.1）：设置页顶部行。
                            // - 手动检查不受 24h 节流；「稍后」只会压制自动提示，不删除版本事实；
                            // - 「去下载」只跳浏览器（零新权限，不承诺 App 内安装）。
                            updateState = updateState,
                            onCheckUpdate = {
                                scope.launch { container.updateRepository.checkNow() }
                            },
                            onIgnoreUpdate = { manifest ->
                                scope.launch { container.updateRepository.markIgnored(manifest) }
                            },
                            // 返回是否成功打开浏览器：失败态由对话框正文显示地址 + 复制。
                            onDownloadUpdate = { apkUrl -> UpdateLauncher.openDownloadPage(context, apkUrl) },
                        )
                    }

                    Route.Usage -> {
                        val vm: UsageViewModel = viewModel(factory = UsageViewModel.factory(container))
                        val state by vm.state.collectAsState()
                        UsageScreen(
                            state = state,
                            onBack = { nav.back() },
                            onDailyLimit = vm::setDailyLimit,
                            onMonthlyLimit = vm::setMonthlyLimit,
                        )
                    }

                    Route.Legal -> LegalScreen(onBack = { nav.back() })

                    is Route.Memory -> {
                        // v0.5.4 key 同时含 characterId 与清空代际（v0.5.6 起为全局代际；
                        // 清空后再进是全新 VM；旧 VM 的 MemoryViewModel 订阅 wipeEpoch 取消在途整理/补建，
                        // 不把旧内容写回）。
                        val vm: MemoryViewModel = viewModel(
                            key = "memory-${route.characterId}-$wipeEpoch",
                            factory = MemoryViewModel.factory(container, route.characterId),
                        )
                        val state by vm.state.collectAsState()
                        MemoryScreen(
                            state = state,
                            onBack = { nav.back() },
                            onForget = vm::forget,
                            // 记忆面板的「导入未整理」分支（整理往事 / 别再提 / 未连 AI 去连接）。
                            onAddBoundary = vm::addBoundary,
                            onConsolidate = vm::startConsolidate,
                            onCancelConsolidate = vm::cancelConsolidate,
                            // 老数据「补建」——让 TA 以后能按意思找到导入的旧事
                            //（只在有 dim=0 的导入块且「找旧事」已配置时显示；不自动执行、可停下）。
                            onBackfillSemantic = vm::backfillSemantic,
                            onCancelBackfill = vm::cancelBackfill,
                            onConfirmConsent = vm::confirmConsolidate,
                            onDismissNotice = vm::dismissNotice,
                            // 面板里两类引导分别进两个设置页（用户指令 2026-11-21）：
                            // - 「未连 AI」→ 聊天设置页（Route.Config）；
                            // - 「先在设置里加一个找旧事」→ 找旧事设置页（Route.FindOldThings）。
                            // 具体进哪个由 state.noticeOpenTarget 显式给出（MemoryScreen 按枚举分派，
                            // 不再比对提示语字符串 ）。
                            onOpenSettings = {
                                vm.dismissNotice()
                                nav.push(Route.Config)
                            },
                            onOpenFindOldThings = {
                                vm.dismissNotice()
                                nav.push(Route.FindOldThings)
                            },
                        )
                    }

                    is Route.Chat -> {
                        // v0.5.4 key 必须**同时**含角色 id 与清空代际（v0.5.6 起为全局代际）——
                        // 前者保证不同角色各自的 VM（既有行为），后者保证清空后再进是全新 VM
                        // （不留清空前的消息列表/草稿）；旧 VM 由 ChatViewModel 自己订阅
                        // `container.wipeEpoch` 取消在途生成并丢弃迟到写入。
                        val vm: ChatViewModel = viewModel(
                            key = "chat-${route.characterId}-$wipeEpoch",
                            factory = ChatViewModel.factory(container, route.characterId),
                        )
                        val state by vm.state.collectAsState()
                        // 朗读器状态（v0.5）：播放提示行 + 不可用说明都要它。
                        val readState by container.reader.state.collectAsState()
                        // 切会话 / 回到聊天页：先停掉残留朗读；自动朗读开着才初始化引擎
                        //（默认关，不给不用这个功能的人占 TTS 资源）。
                        LaunchedEffect(route.characterId, current.autoRead) {
                            container.reader.stop()
                            if (current.autoRead) container.reader.ensureInitialized()
                        }
                        // 设置页改了「创作模式」再回到聊天时同步一次：ViewModel 按角色缓存，
                        // 切页回来不会重新 load()，不补这一下会看到过期的「出戏」按钮状态。
                        // 事件驱动（跟着设置流的变化走），不是每轮发送都读设置。
                        LaunchedEffect(current.creativeMode) {
                            vm.syncCreativeMode(current.creativeMode)
                        }
                        // v0.5 识图门禁：端点/模型/能力缓存任一变化 → 重算入口三态。
                        // 非 DeepSeek 端点隐藏入口；DeepSeek 但模型看不了图 → 置灰 + 说明。
                        val imageEntry = current.imageEntryState()
                        val deepSeekEndpoint = current.isDeepSeekProvider()
                        LaunchedEffect(
                            current.chatBaseUrl,
                            current.chatModel,
                            current.visionProbeModel,
                            current.visionProbeSupported,
                        ) {
                            vm.syncImageGate(
                                visible = imageEntry != ImageEntryState.HIDDEN_OTHER_PROVIDER,
                                enabled = imageEntry == ImageEntryState.ENABLED,
                                fileEnabled = deepSeekEndpoint,
                            )
                        }
                        // v0.5.1 语音门禁 + v0.5.2 接 AppContainer 的统一运行守卫——
                        // 非 DeepSeek / 没密钥 / 绑定不成立 / 密文丢失 / 没麦克风 → 入口隐藏
                        //（`/v1/tools/asr` 是 DeepSeek 私有形状不造假开关）。
                        // 守卫要读设置与密文（suspend），放 LaunchedEffect：不阻塞组合；入口默认隐藏
                        //（`VoiceUiState.visible = false`），所以异步求值不会先闪出一个可用的麦克风。
                        LaunchedEffect(
                            current.hasChatKey,
                            current.chatKeyHost,
                            current.chatBaseUrl,
                            hasMicrophone,
                        ) {
                            vm.syncVoiceGate(container.voiceAvailability(), hasMicrophone)
                        }
                        // 语音动作接线（权限 launcher / 系统设置跳转在根组合，ViewModel 只收结果）。
                        val voiceActions = remember(vm) {
                            VoiceActions(
                                onModeEnter = vm::onVoiceModeEnter,
                                onModeExit = vm::onVoiceModeExit,
                                onPressStart = vm::onVoicePressStart,
                                onPressEnd = vm::onVoicePressEnd,
                                onCancel = vm::onVoiceCancel,
                                // v0.5.2：手势里的「松手取消」状态同步进 ViewModel（权威状态）。
                                onSlideCancelArmedChange = vm::onVoiceSlideCancelArmedChange,
                                onAccessibilityToggle = vm::onVoiceToggleAccessibility,
                                onRetry = vm::onVoiceRetry,
                                onTypeInstead = vm::onVoiceTypeInstead,
                                onIntroConfirm = vm::onVoiceIntroConfirm,
                                onIntroDismiss = vm::onVoiceIntroDismiss,
                                onPermissionContinue = vm::onVoicePermissionContinue,
                                onPermissionDismiss = vm::onVoicePermissionDismiss,
                                onPermissionDeniedSettings = vm::onVoicePermissionDeniedSettings,
                                onPermissionDeniedType = vm::onVoicePermissionDeniedType,
                                onPermissionResult = vm::onVoicePermissionResult,
                                onPermissionResultConsumed = { voicePermissionResult = null },
                                onPermissionLaunchConsumed = vm::onVoicePermissionLaunchConsumed,
                                onRequestPermission = {
                                    runCatching {
                                        voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onOpenSystemSettings = { openAppSystemSettings(context) },
                                onTranscriptInserted = vm::onVoiceTranscriptInserted,
                                onAppBackgrounded = vm::onVoiceAppBackgrounded,
                                onPageLeft = vm::onVoicePageLeft,
                            )
                        }
                        ChatScreen(
                            characterName = state.name,
                            messages = state.messages,
                            input = state.input,
                            isStreaming = state.streaming,
                            showAiBadge = state.showAiBadge,
                            // 花费上限 / 失败提示（缺陷）：超限时输入内容保留，只多这一行字
                            error = state.error,
                            // 联网降级的轻提示（中性色，不是错误）
                            notice = state.notice,
                            onInputChange = vm::onInputChange,
                            onSend = { vm.send() },
                            onSendWithAcceptance = { onAccepted -> vm.send(onAccepted) },
                            sendSessionKey = route.characterId,
                            onStop = vm::stop,
                            onBack = { nav.back() },
                            onOpenMemory = { nav.push(Route.Memory(route.characterId)) },
                            onDismissAiBadge = vm::dismissAiBadge,
                            // 深度思考 / 智能搜索：只有 DeepSeek 端点显示并生效。
                            // 点击即时持久化；生成中由 ChatScreen 禁用（避免中途改参数）。
                            // 用户第一次操作「深度思考」开关（无论开/关）时的一次性提示（产品决定 2026-11-20）。
                            deepSeekProvider = current.isDeepSeekProvider(),
                            thinkingEnabled = current.thinkingEnabled,
                            webSearchEnabled = current.webSearchEnabled,
                            onToggleThinking = vm::onToggleThinking,
                            onToggleWebSearch = vm::onToggleWebSearch,
                            thinkingHintVisible = state.thinkingHint,
                            onDismissThinkingHint = vm::dismissThinkingHint,
                            // 「看看更早」分页（v0.4）：首屏 tail(80)，每次往前取 60 条。
                            hasMoreOlder = state.hasMoreOlder,
                            loadingOlder = state.loadingOlder,
                            onLoadOlder = vm::loadOlder,
                            // 创作模式（v0.4）：开着时顶栏有「出戏」，
                            // 关着时顶栏有「入戏」（首次点开弹说明，看过后直接开启）。
                            // 依据：app/src/main/java/cc/pulsez/app/ui/AppRoot.kt:806
                            creativeMode = state.creativeMode,
                            creativeModeIntroSeen = current.creativeModeIntroSeen,
                            onEnterCreativeMode = vm::enterCreativeMode,
                            onExitCreativeMode = vm::exitCreativeMode,
                            onCreativeModeIntroSeen = {
                                scope.launch { container.settings.setCreativeModeIntroSeen(true) }
                            },
                            // 自动朗读（v0.5 验收 4）：
                            // - reading = 正在读（输入条上方「正在朗读…/停下」）；
                            // - 点停下只停本次（不关自动朗读）；
                            // - 自动朗读开着但手机没有中文语音数据/引擎起不来时，输入条上方给
                            //   「这台手机暂时不能朗读」+ 出路，而不是静默不出声。
                            reading = readState.speaking,
                            onStopReading = { container.reader.stop() },
                            readUnavailableMessage = if (
                                current.autoRead && readState.status == ReaderStatus.UNAVAILABLE
                            ) {
                                ReadCopy.UNAVAILABLE
                            } else {
                                null
                            },
                            // v0.5 识图：草稿/面板/首用说明/重发都走 ChatViewModel；
                            // 入口三态由设置 + 能力缓存决定（见上面的 syncImageGate）。
                            imageInputVisible = state.imageInputVisible,
                            imageInputEnabled = state.imageInputEnabled,
                            drafts = state.drafts,
                            showImageSource = state.showImageSource,
                            showImageIntro = state.showImageIntro,
                            onAddImage = vm::onAddImage,
                            onDismissImageSource = vm::dismissImageSource,
                            onImagePicked = vm::onImagePicked,
                            onCameraCaptured = vm::onCameraCaptured,
                            onCameraCancelled = vm::onCameraCancelled,
                            onRemoveDraft = vm::removeDraft,
                            onResendImage = vm::onResendImage,
                            onImageIntroAcknowledged = vm::acknowledgeImageIntro,
                            // v0.5.1 语音输入：状态 + 动作 + 系统权限框结果（见上面的 voiceActions）。
                            voice = state.voice,
                            voiceActions = voiceActions,
                            voicePermissionResult = voicePermissionResult,
                            // v0.6.10 消息操作（长按复制 / 重新生成 / 重试 / 删除）与 DeepSeek 文件上传
                            fileInputEnabled = state.fileInputEnabled,
                            fileDrafts = state.fileDrafts,
                            uploadingFiles = state.uploadingFiles,
                            onFilePicked = { uri -> vm.onFilePicked(uri, context.contentResolver) },
                            onRemoveFileDraft = vm::removeFileDraft,
                            onRegenerateMessage = vm::regenerateMessage,
                            onRetryMessage = vm::retryMessage,
                            onDeleteMessage = vm::deleteMessage,
                            onShowNotice = vm::showNotice,
                        )
                    }

                    is Route.GroupChat -> {
                        // 真实群聊：threadId 来自 createGroupThread。
                        // v0.5.4 key 同时含 threadId 与清空代际（v0.5.6 起为全局代际；
                        // 清空后全新 VM；旧 VM 由 GroupChatViewModel 自己订阅 wipeEpoch 取消在途生成）。
                        val vm: GroupChatViewModel = viewModel(
                            key = "group-${route.threadId}-$wipeEpoch",
                            factory = GroupChatViewModel.factory(container, route.threadId),
                        )
                        // Each time this route becomes visible, read the latest member order and tail window.
                        LaunchedEffect(route.threadId) { vm.refresh() }
                        val state by vm.state.collectAsState()
                        // 记忆入口先选人（顶栏记忆入口；按角色隔离）
                        var memoryTargets by remember(route.threadId) {
                            mutableStateOf<List<GroupMemberUi>?>(null)
                        }
                        GroupChatScreen(
                            state = state,
                            onInputChange = vm::onInputChange,
                            onSend = { vm.send() },
                            onSendWithAcceptance = { onAccepted -> vm.send(onAccepted) },
                            onStop = vm::stop,
                            onBack = { nav.back() },
                            onOpenMemory = {
                                if (state.members.isNotEmpty()) memoryTargets = state.members
                            },
                            onDismissAiNotice = vm::dismissAiNotice,
                            onRetry = vm::retry,
                            onLoadOlder = vm::loadOlderHistory,
                        )

                        val targets = memoryTargets
                        if (targets != null) {
                            AlertDialog(
                                onDismissRequest = { memoryTargets = null },
                                title = { Text("看谁记得的事？") },
                                text = {
                                    Column {
                                        targets.forEach { member ->
                                            TextButton(
                                                onClick = {
                                                    memoryTargets = null
                                                    nav.push(Route.Memory(member.id))
                                                },
                                            ) { Text(member.name) }
                                        }
                                    }
                                },
                                confirmButton = {},
                            )
                        }
                    }
                }
            }
        }

        // 成员选择弹层：从首页「一起聊」拉起，确认后进群聊（返回手势回首页）
        if (showGroupPicker) {
            GroupPickerSheet(
                members = groupPickerState.members,
                selectedIds = groupPickerState.selectedIds,
                onToggle = groupPickerVm::toggle,
                onDismiss = { showGroupPicker = false },
                onConfirm = {
                    val draft = groupPickerVm.buildDraft()
                    if (draft != null) {
                        // 落真群：thread(kind=GROUP) + thread_member(ordinal)
                        val characterIds = draft.members.map { it.id }
                        showGroupPicker = false
                        groupPickerVm.clearSelection()
                        scope.launch {
                            val thread = runCatching {
                                container.chat.createGroupThread(characterIds, title = null)
                            }.getOrNull()
                            if (thread != null) {
                                nav.push(Route.GroupChat(thread.id))
                            } else {
                                // 角色可能在选择后被删：刷新成员列表，不进入空群
                                groupPickerVm.refresh()
                            }
                        }
                    }
                },
            )
        }

        // 清空确认框（v0.5.2 A）：
        // 「会删 / 会留」逐项列出且与 ExportService.clearAll 实行为一一对应；清单可能很长，
        // 所以放进**有界高度 + 可滚动**的 Column（只给 verticalScroll 没有有界高度会有测量风险；
        // 小屏与 200% 系统字号下靠 heightIn(max = 360.dp) 兜底）。
        // 执行点是本对话框的确认按钮（`runClear`），设置行 `onClearAll` 只置 confirmClear=true。
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(ClearCopy.TITLE) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(ClearCopy.INTRO)
                        Spacer(Modifier.height(8.dp))
                        ClearCopy.DELETE_ITEMS.forEach { item ->
                            Text("· $item")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(ClearCopy.KEEP_HEADING)
                        ClearCopy.KEEP_ITEMS.forEach { item ->
                            Text("· $item")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = runClear) { Text(ClearCopy.CONFIRM) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text(ClearCopy.CANCEL) }
                },
            )
        }

        // 清空失败对话框（v0.5.2 C）：
        // 失败可见、不得静默回首页；「再试一次」重跑同一个 runClear（同一份执行逻辑）。
        if (clearFailed) {
            AlertDialog(
                onDismissRequest = { clearFailed = false },
                title = { Text(ClearCopy.FAILED_TITLE) },
                text = { Text(ClearCopy.FAILED_MESSAGE) },
                confirmButton = {
                    TextButton(onClick = runClear) { Text(ClearCopy.RETRY) }
                },
                dismissButton = {
                    TextButton(onClick = { clearFailed = false }) { Text(ClearCopy.DISMISS) }
                },
            )
        }

        if (showTimeReminder) {
            AlertDialog(
                onDismissRequest = { showTimeReminder = false },
                title = { Text("聊了两个小时了") },
                text = { Text("起来走两步？") },
                confirmButton = { TextButton(onClick = { showTimeReminder = false }) { Text("好") } },
            )
        }

        // 启动静默检查发现新版本：给一次**非阻断**提示（v0.5.1）。

        // - 只提示一次：`update_pending_version_code` 已在该版本第一次提示前落盘，之后不再弹；
        // - 用户点「稍后」→ 记忽略版本（同版本不再自动提示），只压制自动提示，不删版本事实；
        // - 点外面/返回 → 只关窗，不算「稍后」；
        // - 「去下载」只跳浏览器（零新权限；本轮不承诺 App 内下载/安装）。
        // 依据「启动时若自动检查发现新版本且未忽略给一次提示」。
        val updatePrompt = autoUpdatePrompt
        if (updatePrompt != null) {
            UpdateAvailableDialog(
                manifest = updatePrompt,
                installedVersionName = BuildConfig.VERSION_NAME,
                // 打开失败（false）→ 对话框正文显示地址 + 「复制地址」（不再只 Toast）。
                onDownload = {
                    startUpdateDownloadFailed =
                        !UpdateLauncher.openDownloadPage(context, updatePrompt.apkUrl)
                },
                onCopyUrl = { copyDownloadUrl(context, updatePrompt.apkUrl) },
                downloadFailed = startUpdateDownloadFailed,
                onLater = {
                    startUpdateDownloadFailed = false
                    container.updateRepository.consumeAutoPrompt()
                    scope.launch {
                        // A：传清单（不是版本号），状态才能如实显示「有新版本 X · 已跳过」。
                        container.updateRepository.markIgnored(updatePrompt)
                    }
                },
                onDismiss = {
                    startUpdateDownloadFailed = false
                    container.updateRepository.consumeAutoPrompt()
                },
            )
        }
    }
}

/**
 * 极简导航栈：只有"进入下一层 / 返回上一层"两种动作。
 * 首屏不拦截返回，交给系统退出应用（Android 官方对根 destination 的要求）。
 */
private class NavState(initial: Route) {
    var current by mutableStateOf(initial)
        private set

    var lastMoveWasBack by mutableStateOf(false)
        private set

    // 用 snapshot 列表：返回可用性变化必须能触发重组，否则 BackHandler/PredictiveBackHandler 的 enabled 不会更新
    private val stack = androidx.compose.runtime.mutableStateListOf<Route>()

    val canGoBack: Boolean get() = stack.isNotEmpty()

    fun push(route: Route) {
        // `List.addLast` is Android API 35; `MutableList.add` appends and is available on our API 31+ floor.
        // 依据：https://developer.android.com/reference/java/util/List#addLast(E)
        // https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-mutable-list/add.html
        stack.add(current)
        current = route
        lastMoveWasBack = false
    }

    /** 替换当前页（例如"导入完成 → 聊天"），返回仍回上一层。 */
    fun replace(route: Route) {
        current = route
        lastMoveWasBack = false
    }

    fun back() {
        val previous = stack.removeLastOrNull() ?: return
        current = previous
        lastMoveWasBack = true
    }

    fun reset(route: Route) {
        stack.clear()
        current = route
        lastMoveWasBack = false
    }
}

private sealed interface Route {
    data object Threads : Route
    data object AllCharacters : Route
    data object Import : Route

    /** DeepSeek 旧聊天（ZIP）导入页（v0.4）；任务状态在 `AppContainer.importTasks` 里跨页存活。*/
    data object DeepSeekImport : Route

    /** 聊天服务商设置页（用户指令 2026-11-21：只放聊天相关）。 */
    data object Config : Route

    /** 找旧事独立设置页（用户指令 2026-11-21：从原连接页拆出，只放找旧事相关）。 */
    data object FindOldThings : Route

    data object Settings : Route
    data object Usage : Route
    data object Legal : Route
    data class Memory(val characterId: String) : Route
    data class Chat(val characterId: String) : Route
    data class GroupChat(val threadId: String) : Route
}

/**
 * SAF 选中文档的显示名（`OpenableColumns.DISPLAY_NAME`）；查不到就退回固定名。
 *
 * 只用于给人看的文件名（`ImportStartParams.zipName` 与续导指纹的一部分）。拿不到时用固定名
 * 不影响导入本身，只影响「继续上次导入」能否匹配上（用户重新选一次即可重新记指纹）。
 */
private fun displayNameOf(resolver: ContentResolver, uri: Uri): String {
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
        }
    }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: "deepseek-export.zip"
}

/**
 * 从 Compose 的 Context（可能是 ContextWrapper 链）里找到宿主 Activity。
 *
 * 麦克风权限的「永久拒绝」判定要用 `Activity.shouldShowRequestPermissionRationale`；
 * LocalContext 在 Compose 里通常是 Activity，但不保证，逐层解包更稳（找不到就按 false 处理）。
 */
private fun findComponentActivity(context: Context): ComponentActivity? {
    var current: Context? = context
    var guard = 0
    while (current != null && guard < 10) {
        if (current is ComponentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
        guard++
    }
    return null
}

/**
 * 打开本应用的系统设置页（麦克风权限被拒后的「去系统设置」出路）。
 *
 * `ActivityNotFoundException` 必须兜住（部分精简系统没有应用详情页）：先试应用详情页，
 * 打不开就退到系统设置首页；两条都失败则静默——弹窗里还有「先打字」这条出路，不崩。
 * 依据：`（拒绝不给死路）；`（不因外部 Activity 缺失崩）。
 */
private fun openAppSystemSettings(context: Context) {
    val detailsIntent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    val opened = runCatching { context.startActivity(detailsIntent) }.isSuccess
    if (!opened) {
        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
    }
}

/** 文件入口「读取 + 解码」的三态结果（v0.5.2）。*/
private sealed interface PickedTextResult {
    data class Text(val text: String) : PickedTextResult
    data object TooLarge : PickedTextResult
    data object IoError : PickedTextResult
}

/**
 * 读一份 SAF 选中的文件并解码成文本（v0.5.2）。
 *
 * 顺序固定：`SIZE`（只作提前拒绝）→ 有界读 `BackupParser.readFile` → `CharsetDetector.decode`
 * **恰好一次**。整个函数体兜底 `catch(Throwable) → IoError`：`decode` 的 OOM/RuntimeException
 * 也不许从 `scope.launch` 逃逸崩溃（OOM 按「没读到这个文件」人话处理，残余见）。
 * 不关闭调用方传入的流由 `use` 负责；不打印文件内容。
 */
private suspend fun readPickedText(context: Context, uri: Uri): PickedTextResult = withContext(Dispatchers.IO) {
    try {
        val declared = openableSizeOrNull(context.contentResolver, uri)
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return@withContext PickedTextResult.IoError
        stream.use { input ->
            when (val read = BackupParser.readFile(declared, input)) {
                is BoundedReadResult.Ok -> PickedTextResult.Text(CharsetDetector.decode(read.bytes).text)
                BoundedReadResult.TooLarge -> PickedTextResult.TooLarge
                is BoundedReadResult.IoError -> PickedTextResult.IoError
            }
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (t: Throwable) {
        PickedTextResult.IoError
    }
}

/**
 * 读 `OpenableColumns.SIZE`；查询失败/无此列/空值 → null（只当提示，绝不决定「可以无界读」）。
 *
 * `ImportCache.declaredSizeOrNull` 是 private 无法复用（且 `importer/` 既有文件只读），
 * 所以这里自建同口径私有实现（helper）。
 */
private fun openableSizeOrNull(resolver: ContentResolver, uri: Uri): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }
} catch (e: Exception) {
    // 查询只是提前拒绝的提示：任何异常都不阻断读取，有界读仍强制字节上限。
    null
}
