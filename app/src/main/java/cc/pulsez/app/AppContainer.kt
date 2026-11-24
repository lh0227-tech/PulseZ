package cc.pulsez.app

import android.content.Context
import cc.pulsez.app.audio.AndroidTtsEngine
import cc.pulsez.app.audio.ReadVisibility
import cc.pulsez.app.audio.Reader
import cc.pulsez.app.core.ChatEngine
import cc.pulsez.app.core.EmbedPort
import cc.pulsez.app.core.ImportIndexer
import cc.pulsez.app.core.ImportMemoryConsolidator
import cc.pulsez.app.core.ImportMessage
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.CharacterRepository
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.ImportRepository
import cc.pulsez.app.data.settings.ImportPrefs
import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.importer.deepseek.DeepSeekImporter
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.BaseUrl
import cc.pulsez.app.net.EmbeddingClient
import cc.pulsez.app.net.HttpFactory
import cc.pulsez.app.net.LlmClient
import cc.pulsez.app.net.Usage
import cc.pulsez.app.net.asInternalRequest
import cc.pulsez.app.ui.config.CredentialSaveResult
import cc.pulsez.app.ui.config.CredentialSaveWorkflow
import cc.pulsez.app.ui.config.CredentialSlot
import cc.pulsez.app.ui.config.ConnectionTester
import cc.pulsez.app.ui.config.KeyBinding
import cc.pulsez.app.ui.config.KeyStatus
import cc.pulsez.app.ui.config.chatCredentialSlot
import cc.pulsez.app.ui.config.embedCredentialSlot
import cc.pulsez.app.ui.config.keyStatus
import cc.pulsez.app.ui.config.needsLegacyKeyReset
import cc.pulsez.app.ui.config.resolveBinding
import cc.pulsez.app.ui.importer.ImportTaskStore
import cc.pulsez.app.util.SecretStore
import cc.pulsez.app.wire.Pricing
import cc.pulsez.app.wire.ProbeTester
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** 按 id 批量读消息时每批的 SQL 变量数上限（与 `ImportRepository` 的 500 一致，避开 SQLite 上限）。 */
private const val IMPORT_MESSAGE_BATCH = 500

/**
 * 手写依赖容器。刻意不引入 Hilt：这里没有复杂作用域，一个 lazy 就够。
 *
 * v0.5.2：新增三个**可注入的密钥缝**，只为可测性——
 * 生产默认行为完全不变（仍走 [SecretStore] / AndroidKeyStore）；Robolectric 单测用占位实现
 * 绕开 AndroidKeyStore（Robolectric 里 Keystore 不可用，运行时守卫测试只能靠注入）。
 * 默认值不能写成类成员引用（默认参数里没有 `this`），所以这里用「可空参数 + 内部回退」。
 */
class AppContainer(
    context: Context,
    /** 可注入的密文读取缝（null = 生产默认 `SecretStore.get`）。 */
    private val secretReader: ((String) -> String?)? = null,
    /** 可注入的密文删除缝（迁移 / 清除测试要断言「密钥不存在」）。 */
    private val secretRemover: ((String) -> Unit)? = null,
    /** 可注入的密文写入缝（保存协议测试用占位密钥，不碰 AndroidKeyStore）。 */
    private val secretWriter: ((String, String) -> Unit)? = null,
    /**
     * 仅测试缝（v0.5.2）：替换聊天保存协议**第③步**「写绑定」的动作，
     * 让容器级测试能模拟「第③步写失败」与「第③步写了但④回读不一致」。
     * 生产恒为 null → 走 [SettingsStore.setChatBoundEndpoint]，行为不变。
     *
     */
    private val chatBoundEndpointWriterForTest:
        (suspend (baseUrl: String, model: String, keyHost: String) -> Unit)? = null,
) {

    private val appContext = context.applicationContext

    /**
     * 应用缓存目录（只读）。
     *
     * v0.5.2：**唯一用途**是「清空所有数据」时删掉
     * `voice/`、`camera/`、`import/` 三个临时目录。不加别的暴露面——`filesDir` 之类仍由
     * 各自进程级实例（`AttachmentStore` 等）持有，避免把私有路径散给 UI 层。
     */
    val cacheDir: java.io.File get() = appContext.cacheDir

    val database: PulsezDatabase by lazy { PulsezDatabase.create(appContext) }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val secrets: SecretStore by lazy { SecretStore(appContext) }

    // ---------------------------------------------------------------- v0.5.2 密钥缝

    /** 读密文：注入了 [secretReader] 就只走注入实现（测试断言可预期），否则走生产 [secrets]。 */
    private fun readSecret(name: String): String? {
        val injected = secretReader
        return if (injected != null) injected(name) else secrets.get(name)
    }

    private fun writeSecret(name: String, value: String) {
        val injected = secretWriter
        if (injected != null) injected(name, value) else secrets.put(name, value)
    }

    private fun removeSecret(name: String) {
        val injected = secretRemover
        if (injected != null) injected(name) else secrets.remove(name)
    }

    /**
     * 读路径的公共守卫：**先判绑定、再读密钥**。
     *
     * 只有 [KeyBinding.Usable] 才返回密文；绑定不成立时连 `readSecret` 都不会被调用
     * （`resolveBinding` 的 lambda 只在 `KeyBindingState.Expected` 后执行）。这样旧服务商的
     * 密钥永远不会和当前地址拼成请求。返回 null = 该服务不可用，**不新增任何网络请求**。
     */
    private fun usableKey(
        hasKey: Boolean,
        keyHost: String,
        baseUrl: String,
        secretName: String,
    ): String? {
        var secret: String? = null
        val binding = resolveBinding(hasKey, keyHost, baseUrl) {
            readSecret(secretName).also { secret = it }
        }
        return if (binding is KeyBinding.Usable) secret else null
    }

    /**
     * 「找旧事」的一次性请求快照（v0.5.2）。
     *
     * 为什么要有它：`core/ChatEngine` 的两处向量调用原先直接把密钥和当前地址拼成请求，
     * 没有先检查密钥是否属于这个地址。快照把守卫用的设置与请求用的地址、模型和密钥锁在**同一次**
     * `settings.flow.first()` 上；设置中途变化也不会拼出「A 家密钥 + B 家地址」。
     *
     * 刻意**不是** data class：默认 `toString()` 会打印 `apiKey`，所以这里显式遮掉；
     * 日志和异常栈不得打印密钥。字段本身只给同模块的请求构造用。
     */
    class EmbeddingSession internal constructor(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
    ) {
        override fun toString(): String = "EmbeddingSession(baseUrl=$baseUrl, model=$model, apiKey=***)"
    }

    /**
     * 取「找旧事」请求快照：未配置 / 绑定不成立 / 无密文 → null；
     * 调用方拿到 null 时**不得构造 [EmbeddingClient]、不得发请求**。
     *
     * 顺序：先判地址/模型与绑定关系，再读密钥；绑定不成立时一次 Keystore 都不碰。
     */
    suspend fun embeddingSession(): EmbeddingSession? = withContext(Dispatchers.IO) {
        // v0.5.3：快照 + 密文读取整体放进 credentialMutex，与保存 / 迁移互斥，
        // 不会出现「旧设置（旧地址） + 新密钥」；锁内不构造网络请求（这里只建内存快照对象）。
        withCredentialReadLock<EmbeddingSession?> { current ->
            if (current.embedBaseUrl.isBlank() || current.embedModel.isBlank()) {
                return@withCredentialReadLock null
            }
            val key = usableKey(
                hasKey = current.hasEmbedKey,
                keyHost = current.embedKeyHost,
                baseUrl = current.embedBaseUrl,
                secretName = ChatEngine.EMBED_KEY,
            ) ?: return@withCredentialReadLock null
            EmbeddingSession(current.embedBaseUrl, current.embedModel, key)
        }
    }

    /**
     * 保存 / 清除 / 迁移 / 孤儿对账 / **读路径快照**共用的**同一把锁**
     * 读路径也由 [withCredentialReadLock] 保护。
     *
     * 不能省：迁移里的「密文删除」在 DataStore edit 之外，与孤儿对账判据重合；不互斥时
     * 可能删掉刚保存的新密钥。
     *
     * 四条读路径也在这把锁里取「设置快照 + 密文」。没有互斥时，读路径
     * 无锁，交错顺序可以是：读路径先取旧设置（旧地址 + 旧 host）→ 保存 / 迁移完成
     * （新地址 + 新密钥）→ 读路径再按**旧快照**的绑定关系读到**新密钥**，拼出「旧地址 +
     * 新密钥」的客户端，把 B 家密钥发给 A 家。现在读与写互斥，读只能看到「保存前」或
     * 「保存后」的完整状态，中间态不可见。
     */
    private val credentialMutex = Mutex()

    /**
     * 读路径的互斥段（v0.5.3）：在 [credentialMutex] 内**原子地**完成
     * 「取设置快照 → 判绑定 → 读密文」。
     *
     * 锁内只允许：`settings.flow.first()`、[usableKey]（Keystore 读）、构造内存对象
     * （`EmbeddingSession` / 客户端对象；构造本身不出网）。**网络请求一律在锁外**，由调用方
     * 拿到返回对象后自己发起——本方法只返回内存对象 / null。
     *
     * 无死锁的调用约定：[Mutex] **不可重入**，本方法只能从**没有**持 [credentialMutex] 的
     * 路径调用。本类持有该锁的位置只有保存 / 清除 / 迁移 5 个方法
     * （[saveChatCredential] / [saveEmbedCredential] / [clearChatCredential] /
     * [disconnectEmbedCredential] / [migrateLegacyCredentialBinding]）；它们内部只调
     * `CredentialSaveWorkflow` / `SettingsStore` / [readSecret] / [removeSecret]，
     * 不回调四条读路径（[embeddingSession] / [voiceAvailability] / [asrClient] / [llmClient]）。
     */
    private suspend fun <T> withCredentialReadLock(block: suspend (Settings) -> T): T =
        credentialMutex.withLock { block(settings.flow.first()) }

    private val credentialWorkflow: CredentialSaveWorkflow by lazy {
        CredentialSaveWorkflow(
            readSettings = { settings.flow.first() },
            writeSecret = { name, value -> writeSecret(name, value) },
            removeSecret = { name -> removeSecret(name) },
        )
    }

    private val chatCredentialSlot: CredentialSlot by lazy {
        val productionSlot = chatCredentialSlot(settings)
        val injectedWriter = chatBoundEndpointWriterForTest
        if (injectedWriter == null) {
            productionSlot
        } else {
            // 仅测试：第③步换成注入动作（生产恒为 null，不走这支）。
            productionSlot.copy(
                writeBoundEndpoint = { baseUrl, model, keyHost ->
                    injectedWriter(baseUrl, model, keyHost)
                },
            )
        }
    }

    private val embedCredentialSlot: CredentialSlot by lazy { embedCredentialSlot(settings) }

    /**
     * 保存聊天三格（v0.5.2 三步协议）。
     *
     * 返回 [CredentialSaveResult]：只有 `SAVED` 能在 UI 层变成 `saved=true` + `nav.back()`。
     *
     * v0.5.2 第③步可能已经改过 endpoint/model，而失败回滚只还原「绑定」不还原
     * endpoint；「这个地址 + 这个模型」的识图探测结论可能已失效，所以**失败且 endpoint 确实
     * 变过**时清掉探测结论。只做精确判定：①/② 步失败时 endpoint 没变，无条件清会丢掉仍然
     * 有效的结论（清掉只是让它下次重新探测，不会丢用户数据，但没有必要时不做）。
     * 落点放在这里（而不是 `ConfigViewModel.saveChat()`）是因为本包不改 `ui/config/` 生产代码。
     */
    internal suspend fun saveChatCredential(
        baseUrl: String,
        model: String,
        keyInput: String,
    ): CredentialSaveResult = withContext(Dispatchers.IO) {
        credentialMutex.withLock {
            val before = runCatching { settings.flow.first() }.getOrNull()
            val result = credentialWorkflow.save(chatCredentialSlot, baseUrl, model, keyInput)
            if (result != CredentialSaveResult.SAVED) {
                val after = runCatching { settings.flow.first() }.getOrNull()
                val endpointChanged = before == null || after == null ||
                    before.chatBaseUrl != after.chatBaseUrl || before.chatModel != after.chatModel
                if (endpointChanged) runCatching { settings.clearVisionProbe() }
            }
            result
        }
    }

    /** 保存找旧事三格（同协议）。 */
    internal suspend fun saveEmbedCredential(
        baseUrl: String,
        model: String,
        keyInput: String,
    ): CredentialSaveResult = withContext(Dispatchers.IO) {
        credentialMutex.withLock { credentialWorkflow.save(embedCredentialSlot, baseUrl, model, keyInput) }
    }

    /**
     * 「清除密钥」（E）：一次 edit 让聊天绑定失效，再删密文。
     *
     * 顺序上先让设置不可用：即使删密文失败（Keystore/IO 异常），聊天与语音门禁也已关闭
     * （读路径只认 `bindingState`）；残留密文由下次启动的孤儿对账重试删除。
     */
    internal suspend fun clearChatCredential(): Boolean = withContext(Dispatchers.IO) {
        credentialMutex.withLock {
            runCatching {
                settings.clearChatKeyBinding()
                removeSecret(cc.pulsez.app.ui.config.ConfigViewModel.SECRET_CHAT_KEY)
                // E：换域/清密钥后旧探测结论失效（识图能力缓存属于「这个地址 + 这个模型」）。
                settings.clearVisionProbe()
            }.isSuccess
        }
    }

    /**
     * 「停止使用找旧事」（H）：一次 edit 清地址/模型/绑定/`hasKey`，再删密文。
     *
     * 先清设置：`embedBaseUrl` 一空，`core/ChatEngine` 的检索/索引就直接短路（零出网），
     * 不依赖密文删除是否成功。关键词检索不受影响（它不读这个服务）。
     * 另外 `core/ChatEngine` 的请求统一经 [embeddingSession] 的绑定/密文守卫。
     */
    internal suspend fun disconnectEmbedCredential(): Boolean = withContext(Dispatchers.IO) {
        credentialMutex.withLock {
            runCatching {
                settings.clearEmbedServiceConfig()
                removeSecret(ChatEngine.EMBED_KEY)
            }.isSuccess
        }
    }

    /**
     * 配置页「密钥状态行」的聊天派生值（F）；读密文存在性，不解密明文。
     *
     * 本方法只影响设置页状态行的**显示**（「可用 / 要重填」），
     * 不构造客户端、不产生任何请求；即使保存在并发中让它看到半旧半新的状态，也不会把密钥
     * 发出去。它只读取密文是否存在，不解密明文。
     */
    internal suspend fun chatKeyStatus(current: Settings): KeyStatus = withContext(Dispatchers.IO) {
        keyStatus(
            hasKey = current.hasChatKey,
            keyHost = current.chatKeyHost,
            baseUrl = current.chatBaseUrl,
            hasSecret = !readSecret(cc.pulsez.app.ui.config.ConfigViewModel.SECRET_CHAT_KEY).isNullOrBlank(),
        )
    }

    /**
     * 配置页「密钥状态行」的找旧事派生值（F）；只读显示残余同 [chatKeyStatus]（不产生请求）。
     */
    internal suspend fun embedKeyStatus(current: Settings): KeyStatus = withContext(Dispatchers.IO) {
        keyStatus(
            hasKey = current.hasEmbedKey,
            keyHost = current.embedKeyHost,
            baseUrl = current.embedBaseUrl,
            hasSecret = !readSecret(ChatEngine.EMBED_KEY).isNullOrBlank(),
        )
    }

    /**
     * 升级迁移挂在 `PulsezApp.onCreate` 的 `appScope`。
     *
     * 规则：
     * 1. `hasKey && keyHost 为空` → 删该密钥 + `hasKey=false` + `keyHost=""` + 置一次性提示；
     *    **绝不回填 host**：App 无法证明旧密钥属于谁，回填就是猜。
     * 2. 顺带对账「`hasKey=false` 但密文仍在」的孤儿密文（删掉）：覆盖「保存第 ② 步后进程
     *    被杀」「旧版清空数据只清密文不清标记」等形状。
     *
     * 与保存/清除共用 [credentialMutex]；每一步 best-effort、失败不崩（迁移失败时读路径守卫
     * 独立生效，旧密钥不会因为迁移没跑完就可用）。重复执行幂等。
     */
    internal suspend fun migrateLegacyCredentialBinding() {
        runCatching {
            credentialMutex.withLock {
                migrateCredentialSlot(
                    secretName = cc.pulsez.app.ui.config.ConfigViewModel.SECRET_CHAT_KEY,
                    keyPresent = { it.hasChatKey },
                    keyHost = { it.chatKeyHost },
                    resetBinding = { settings.resetLegacyChatKeyBinding() },
                )
                migrateCredentialSlot(
                    secretName = ChatEngine.EMBED_KEY,
                    keyPresent = { it.hasEmbedKey },
                    keyHost = { it.embedKeyHost },
                    resetBinding = { settings.resetLegacyEmbedKeyBinding() },
                )
            }
        }
    }

    /** [migrateLegacyCredentialBinding] 的单槽实现（chat / embed 同规则）。 */
    private suspend fun migrateCredentialSlot(
        secretName: String,
        keyPresent: (Settings) -> Boolean,
        keyHost: (Settings) -> String,
        resetBinding: suspend () -> Unit,
    ) {
        val before = runCatching { settings.flow.first() }.getOrNull() ?: return
        if (needsLegacyKeyReset(keyPresent(before), keyHost(before))) {
            // 先立「不可用 + 一次性提示」，再删密文：删密文失败也不影响 chat/ASR 读路径守卫；
            // 找旧事读路径已统一走 [embeddingSession] 守卫，旧密文不会再和当前地址拼成
            // 请求，下面孤儿对账保留作为兜底删除。
            runCatching { resetBinding() }
            runCatching { removeSecret(secretName) }
        }
        // 孤儿密文对账（必须在 reset 之后重新读设置，不能拿 before 的快照判断）。
        val after = runCatching { settings.flow.first() }.getOrNull() ?: return
        if (!keyPresent(after)) {
            val secretStillThere = runCatching { readSecret(secretName) != null }.getOrDefault(false)
            if (secretStillThere) runCatching { removeSecret(secretName) }
        }
    }

    val characters: CharacterRepository by lazy { CharacterRepository(database) }

    val roleDeletions: cc.pulsez.app.data.repo.CharacterDeletionRepository by lazy {
        cc.pulsez.app.data.repo.CharacterDeletionRepository(
            db = database,
            onCharactersDeleted = { deletedIds -> awakeningManager.cancelAndClear(deletedIds) },
        )
    }

    val chat: ChatRepository by lazy { ChatRepository(database) }

    /**
     * 附件仓储，供图片导入流程使用。
     *
     * 文件落盘由 `AttachmentStore`（进程级，`PulsezApp.onCreate` 安装）负责；这里只暴露
     * "DB 行 + 文件"的成套操作，UI/图片管线不直接碰 DAO。
     */
    val attachments: cc.pulsez.app.data.repo.AttachmentRepository by lazy {
        cc.pulsez.app.data.repo.AttachmentRepository(database)
    }

    /** 本地附件目录（进程级实例；`PulsezApp` 在任何 UI 之前安装）。 */
    val attachmentStore: cc.pulsez.app.data.attachments.AttachmentStore by lazy {
        cc.pulsez.app.data.attachments.AttachmentStore.required()
    }

    /**
     * 图片导入流水线（v0.5.0 识图）：相册 Uri / 相机临时文件 → 压到长边 ≤1536 的 JPEG →
     * `filesDir/attachments`。
     *
     * 缩略图内存缓存的 `onTrimMemory` 注册（`ThumbnailCache.install`）已在 `PulsezApp.onCreate`
     * 的进程启动阶段完成。只在首次选图时注册会漏掉「只浏览历史图」的会话；这里保留一次
     * 幂等调用作为兜底（其它入口直接取本容器时），
     * 只注册回调、不预解码，无冷启动成本。
     */
    val imagePipeline: cc.pulsez.app.media.ImagePipeline by lazy {
        cc.pulsez.app.media.ThumbnailCache.install(appContext)
        cc.pulsez.app.media.ImagePipeline(appContext, attachments)
    }

    val memory: cc.pulsez.app.data.repo.MemoryRepository by lazy {
        cc.pulsez.app.data.repo.MemoryRepository(database)
    }

    val usage: cc.pulsez.app.data.repo.UsageStore by lazy {
        cc.pulsez.app.data.repo.UsageStore(appContext)
    }

    /** 群聊轮换账本持久化。 */
    val groupLedger: cc.pulsez.app.data.group.GroupLedgerStore by lazy {
        cc.pulsez.app.data.group.GroupLedgerStore(appContext)
    }

    val engine: cc.pulsez.app.core.ChatEngine by lazy { cc.pulsez.app.core.ChatEngine(this) }

    /** 后台维护（增量抽取 / 交接）用应用级作用域，切页锁屏都不中断。 */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // ---------------------------------------------------------------- v0.5.4 清空代际

    private val _wipeEpoch = MutableStateFlow(0L)

    /**
     * 「清空所有数据」的**代际**。
     *
     * 只有一个写入点 [notifyUserDataWiped]，时机是清空的**执行起点**：
     * `AppRoot.runClear` 在 `ExportService.clearAll` 之前调用。
     * 读者是清空前就在跑的异步流程：
     * - `ChatViewModel` / `GroupChatViewModel` 订阅它 → 立刻取消在途生成；
     * - 生成流程在开始时记下当时的代际，`LlmEvent.Finished`／写库／写用量／写索引前比对；
     * - `ChatEngine.indexTurn` / `maintenance` 落库前比对。
     *
     * 语义是「本次进程里用户主动清空了几次」，**不是时间戳**，也不持久化：清空后重启进程归零，
     * 因为进程被杀时在途任务也一起没了。0L = 本次进程还没清空过。
     *
     */
    val wipeEpoch: StateFlow<Long> = _wipeEpoch.asStateFlow()

    /**
     * 清空**开始执行**时自增代际（v0.5.4）。**只允许** `AppRoot.runClear` 调用，
     * 时机是「用户确认清空、即将执行 `ExportService.clearAll` 的那一刻」（早于 clearAll）。
     *
     * 为什么不再等 clearAll 返回才发信号：`clearAll` 要跑 DB 清表 + DataStore 写 + 文件删除，
     * 等它返回才发信号，期间迟到的写入可能落在「DB 已清、信号未发」之间被写回。
     * 因此在执行起点发出信号；代价是「清空失败时内存状态/
     * VM 已复位」；而 `clearAll` 分步执行（库先清，见 `ExportService.kt:433`），失败时本地数据
     * **可能已被部分删除**——失败对话框照旧显示「有几项没删掉 + 再试一次」，但**不承诺数据完整**，
     * 也没有统一回滚。
     *
     * 线程：唯一写入点是主线程（`AppRoot` 的 Compose 回调），读取方在各自的 ViewModel 作用域
     * 与 `appScope`。用 `StateFlow` 而不是裸 `var` 是仓库既有的跨线程状态模式——同一定位的
     * `ImportTaskStore` 的 `_state` 也是「appScope 写、UI 读」（见该类类注释与
     * `ui/importer/ImportTaskStore.kt:143-177`），`UpdateRepository._state` 同理。
     */
    internal fun notifyUserDataWiped() {
        _wipeEpoch.value = _wipeEpoch.value + 1L
    }

    val http: OkHttpClient by lazy { HttpFactory.create() }

    /**
     * 「检查更新」（v0.5.1只检查 + 非阻断提示 + 跳浏览器，零新权限）。
     *
     * 更新检查通过独立仓储接入：
     * - 检查器用**独立**的轻量 OkHttp 客户端（`update.VersionChecker` 内部建），
     *   不污染 [http] 的流式超时语义；
     * - 状态落 DataStore（`update.SettingsUpdatePrefs`），启动/手动检查共用同一个 `UpdateRepository`；
     * - 自动检查的开关是 `UpdateConfig.AUTO_CHECK_ON_LAUNCH`（debug 构建不自动查，手动仍可用）。
     *
     * 懒加载：只有启动检查/进设置才会创建；创建本身不发任何请求。
     */
    val updateRepository: cc.pulsez.app.update.UpdateRepository by lazy {
        cc.pulsez.app.update.UpdateRepository(
            fetcher = cc.pulsez.app.update.VersionChecker(
                cc.pulsez.app.update.UpdateSources.defaults(),
            ),
            prefs = cc.pulsez.app.update.SettingsUpdatePrefs(settings),
            installedVersionCode = { cc.pulsez.app.update.InstalledVersion.versionCode(appContext) },
            // debug 构建不自动检查；手动检查不受影响。
            autoCheckEnabled = cc.pulsez.app.update.UpdateConfig.AUTO_CHECK_ON_LAUNCH &&
                (!BuildConfig.DEBUG || cc.pulsez.app.update.UpdateConfig.AUTO_CHECK_ON_DEBUG),
        )
    }

    /**
     * 连接测试（"测一下"）。
     *
     * v0.5.0 识图：把 `SettingsStore` 传进去，`/models` 探测到的 `input_modalities` 结论会缓存成
     * 「当前模型能不能看图」，图片入口据此显示/置灰。
     */
    val tester: ConnectionTester by lazy { ProbeTester(http, settings) }

    /**
     * 自动朗读（系统 TTS，v0.5）。
     *
     * App 级单例：聊天页与设置「声音」组共用同一个引擎（设置里选的音色/语速立刻对聊天生效）。
     * 懒加载：只有真正用到（自动朗读开 / 进设置）才创建 `TextToSpeech`；
     * `ChatViewModel.onCleared()` 会 stop+shutdown，之后再用会由 [Reader.ensureInitialized] 重建。
     */
    val reader: Reader by lazy { Reader { AndroidTtsEngine(appContext) } }

    /**
      * 自动朗读的「页面 + 前台」可见性门。
     *
     * 由 `AppRoot` 写（Lifecycle `ON_START`/`ON_STOP` + 导航变化），由 `ChatViewModel` 在
     * `LlmEvent.Finished` 读；纯 Kotlin 单例，不持有任何 Android 资源（JVM 可测）。
     * 懒加载：不触发任何 TTS 初始化。
     */
    val readVisibility: ReadVisibility by lazy { ReadVisibility() }

    // ---------------------------------------------------------- v0.5.1 语音输入基建

    /**
     * 语音录音器（v0.5.1；`audio/AndroidVoiceRecorder`，`AudioRecord` 16k/单声道/PCM16）。
     *
     * App 级单例：聊天页的语音状态机共用一个麦克风句柄，避免两个录音器抢麦。
     * 懒加载：第一次点语音才创建；创建本身**不打开麦克风**（`start()` 才打开）。
     * 录音临时文件落 `cacheDir/voice/`，取消/转写后由调用方删除。
     */
    val voiceRecorder: cc.pulsez.app.audio.VoiceRecorder by lazy {
        cc.pulsez.app.audio.AndroidVoiceRecorder(appContext)
    }

    /**
     * 当前设置下语音输入是否可用（纯函数门禁 + 运行时绑定/密文守卫）。
     *
     * 门禁口径见 `audio/VoiceInputGate.kt`：第三方端点不显示入口（`/v1/tools/asr` 是
     * DeepSeek 私有形状），没密钥则引导去设置。
     *
     * v0.5.2 追加：门禁原本只看 `hasChatKey`；密钥按服务商绑定后，「有密钥但绑定不成立」
     * （换域没重填、升级脏状态）同样不可用——否则会显示麦克风入口却拿不到 `asrClient()`。
     * 归到 [AsrAvailability.NO_KEY]（用户动作都是「去设置填密钥」，与没密钥同一出路）。
     *
     * v0.5.2 与 [asrClient] 同一口径——**密文读不到也归 NO_KEY**（清数据只清密文/解密
     * 失败时不再拖到转写期 `SERVICE_UNSUPPORTED`）；本条是生产**唯一**语音门禁入口
     * （`ui/AppRoot` 的设置变化副作用与 `ChatViewModel` 的「按下说话」都调它）。读设置与密文
     * 放 IO：门禁会在入口求值与按下说话时各评估一次，不能占用主线程（Keystore/DataStore）。
     */
    suspend fun voiceAvailability(): cc.pulsez.app.audio.AsrAvailability = withContext(Dispatchers.IO) {
        // v0.5.3：门禁用到的设置快照与密文读取在同一互斥段内，
        // 不会出现「旧地址判定 + 新密钥」的半旧半新状态。
        withCredentialReadLock<cc.pulsez.app.audio.AsrAvailability> { current ->
            val gate = cc.pulsez.app.audio.VoiceInputGate.availability(current)
            if (gate != cc.pulsez.app.audio.AsrAvailability.AVAILABLE) {
                gate
            } else {
                val usable = usableKey(
                    hasKey = current.hasChatKey,
                    keyHost = current.chatKeyHost,
                    baseUrl = current.chatBaseUrl,
                    secretName = cc.pulsez.app.ui.config.ConfigViewModel.SECRET_CHAT_KEY,
                )
                if (usable != null) {
                    cc.pulsez.app.audio.AsrAvailability.AVAILABLE
                } else {
                    cc.pulsez.app.audio.AsrAvailability.NO_KEY
                }
            }
        }
    }

    /**
     * ASR 客户端（v0.5.1）：配置齐全 + v0.5.2 绑定成立才返回，否则 null。
     *
     * 只有「DeepSeek 官方端点 + 聊天密钥已配置且属于这个地址」才创建；第三方端点不会拿到
     * 这个客户端（`/v1/tools/asr` 不是 OpenAI 兼容端点）。返回的客户端内部使用独立的
     * OkHttp 超时/不自动重试配置，避免与全局流式客户端互相影响（见 `net/AsrClient`）。
     *
     * 顺序：先判绑定，再读密钥；绑定不成立时一次 Keystore 都不碰。
     *
     * v0.5.3：「设置快照 + 判绑定 + 读密钥 + 构造客户端」整体在 [credentialMutex]
     * 内（[withCredentialReadLock]），与保存 / 清除 / 迁移互斥；构造客户端本身不出网，
     * 真正的转写请求仍由调用方在锁外发起。
     */
    suspend fun asrClient(): cc.pulsez.app.net.AsrClient? =
        withCredentialReadLock<cc.pulsez.app.net.AsrClient?> { current ->
            if (cc.pulsez.app.audio.VoiceInputGate.availability(current) !=
                cc.pulsez.app.audio.AsrAvailability.AVAILABLE
            ) {
                return@withCredentialReadLock null
            }
            val key = usableKey(
                hasKey = current.hasChatKey,
                keyHost = current.chatKeyHost,
                baseUrl = current.chatBaseUrl,
                secretName = cc.pulsez.app.ui.config.ConfigViewModel.SECRET_CHAT_KEY,
            ) ?: return@withCredentialReadLock null
            cc.pulsez.app.net.AsrClient(http = http, baseUrl = current.chatBaseUrl, apiKey = key)
        }

    // ---------------------------------------------------------------- v0.4 导入接线

    /** 导入专用偏好（独立 DataStore `pulsez_import`）。*/
    val importPrefs: ImportPrefs by lazy { ImportPrefs(appContext) }

    /** DeepSeek 旧聊天导入的数据层。*/
    val importRepository: ImportRepository by lazy { ImportRepository(database) }

    /**
     * 本次导入是否「顺便让 TA 以后能按意思找到这些旧事」（确认屏复选框；默认开、可关）。
     *
     * 属于**一次导入的运行时意愿**，不是持久设置（用户下次导入重新选）。由
     * `DeepSeekImportViewModel.start()` 在点「开始导入」前写入；`DeepSeekImporter` 通过
     * 构造参数 `embedConsent` 读取，`ImportIndexer.index(embedEnabled=…)` 据此决定是否建向量。
     * 默认 true = 老调用方/测试不写这一项时按「已同意」处理，真正是否联网仍由
     * [embeddingConfigured] 决定（未配置 = 零网络）。
     */
    @Volatile
    var importEmbedConsent: Boolean = true

    /**
     * 「找旧事」服务是否配置齐全（地址 + 模型 + 密钥），且密钥**属于这个地址**（v0.5.2）。
     *
     * 导入确认屏的「顺便……」复选框、索引器、记忆面板「补建」共用这一口径：
     * **未配置/绑定不成立就不显示入口、不发网络请求**；不配置时仍可用关键词检索。
     * 导入和记忆面板都走 [embeddingSession]，避免出现两套不同的密钥守卫。
     */
    suspend fun embeddingConfigured(): Boolean = embeddingSession() != null

    /** 当前「找旧事」模型名（写 `chunk.model`，换模型要重建索引）；未配置时为空串。 */
    suspend fun embeddingModel(): String = settings.flow.first().embedModel

    /**
     * Stable non-secret identity for import block checkpoints. It includes the configured normalized
     * endpoint (including non-secret routing query parameters) and model, never the API key.
     * The provider may still replace a model behind the same endpoint/name without exposing that fact.
     */
    suspend fun importEmbeddingConfigFingerprint(): String {
        val session = embeddingSession() ?: return ""
        val identity = "${BaseUrl.normalize(session.baseUrl)}\u0000${session.model}"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /**
     * 建向量端口：`ImportIndexer` 与记忆面板「补建」共用。
     *
     * - 未配置 / 绑定不成立 / 无密钥 → 返回 `null`（**不发起任何网络请求**，用于「未配置不调用」验收）；
     * - 已配置且密钥属于这个地址 → 用 [EmbeddingClient] 调用户填的「找旧事」服务；调用失败返回 null，
     *   由调用方按「整批重试 2 次、仍失败跳过该批」处理，不阻断导入/补建。
     *
     * v0.5.2：保持 `val`，lambda 内先判绑定关系再读密钥；
     * 换服务商后旧密钥不会被拼到新地址上（的读路径守卫）。
     * 与 [embeddingConfigured] 共用 [embeddingSession] 快照（同一份设置，不漂移）。
     */
    val importEmbedPort: EmbedPort by lazy {
        EmbedPort { texts ->
            val session = embeddingSession()
            when {
                session == null -> null
                texts.isEmpty() -> emptyList()
                else -> runCatching {
                    EmbeddingClient(http, session.baseUrl, session.apiKey)
                        .embed(texts, session.model)
                }.getOrNull()
            }
        }
    }

    /**
     * 导入本地索引（关键词 + 向量，v0.4.1）。
     *
     * 接线口径（契约）：`loadMessages` 按 id 批量取消息 → `ImportMessage`；
     * `saveChunks` 直接走现有 `MemoryRepository.saveChunks`（内部带 `chunk`/`chunk_term` 两表写入）。
     *
     * 向量部分：「找旧事」服务配置齐全时，导入的旧事也按批（≤16）
     * 建向量，之后能被**按意思**召回；未配置/无密钥时 `embedConfigured()` 为 false，
     * `ImportIndexer` 一次网络调用都不发，行为与 v0.4.0 一致（关键词-only）。
     */
    val importIndexer: ImportIndexer by lazy {
        ImportIndexer(
            loadMessages = { ids -> loadImportMessages(ids) },
            saveChunks = { chunks, terms -> memory.saveChunks(chunks, terms) },
            embed = importEmbedPort,
            embedModel = { embeddingModel() },
            embedConfigured = { embeddingConfigured() },
            embedConfigFingerprint = { importEmbeddingConfigFingerprint() },
            loadBatchChunks = { batchId, segmentId, chunkIds ->
                importRepository.loadBatchChunksIfActive(batchId, segmentId, chunkIds)
            },
            saveBatchChunks = { batchId, chunks, terms, sourceIndexComplete ->
                importRepository.saveBatchChunksIfActive(batchId, chunks, terms, sourceIndexComplete)
            },
        )
    }

    /**
     * 导入专用记忆整理：分片、续跑、费用估算、记账。
     *
     * `completeJson` 没配置 AI（`llmClient()` 为 null）时返回 null —— 按「无 Key 不调用」处理；
     * 内部请求沿用 ChatEngine 的口径（非流式 / json 模式 / 2000 输出 / 温度 0.2 / 显式关思考）。
     */
    val importConsolidator: ImportMemoryConsolidator by lazy {
        ImportMemoryConsolidator(
            loadMessages = { ids -> loadImportMessages(ids) },
            applyPatch = { threadId, patchJson ->
                // 导入整理不归属某个新段：segmentId=null；单聊维度 ownerId=null。
                memory.applyPatch(threadId, patchJson, segmentId = null, ownerId = null)
            },
            completeJson = { system, user ->
                val client = llmClient()
                val current = settings.flow.first()
                if (client == null || current.chatModel.isBlank()) {
                    null
                } else {
                    client.completeJson(
                        ChatRequest(
                            model = current.chatModel,
                            messages = listOf(
                                ChatMessage(role = "system", content = system),
                                ChatMessage(role = "user", content = user),
                            ),
                            stream = false,
                            jsonMode = true,
                            maxTokens = 2000,
                            temperature = 0.2,
                            // 内部请求显式关思考：否则 2000 输出预算会被思考吃光。
                        ).asInternalRequest(current.chatBaseUrl),
                    )
                }
            },
            recordUsage = { inputTokens, outputTokens ->
                val model = settings.flow.first().chatModel
                val cost = Pricing.costOf(
                    model,
                    Usage(promptTokens = inputTokens, completionTokens = outputTokens),
                )
                usage.record(cost, 0.0)
            },
            costOf = { inputTokens, outputTokens ->
                // 契约是普通函数（非 suspend）；它只在导入/整理的 IO 线程上被调用，
                // 这里短暂读一次当前模型名（DataStore 是内存缓存，代价很小）。
                val model = runBlocking { settings.flow.first().chatModel }
                Pricing.costOf(
                    model,
                    Usage(promptTokens = inputTokens, completionTokens = outputTokens),
                )
            },
            loadProgress = { threadId ->
                // 进度结构改为「已成功片索引 + 失败片索引 + 总数」，
                // 这里原样返回该 thread 的断点；`ImportPrefs.consolidation` 已经过滤掉
                // 「全部完成/无进度」的情况（返回 null）。
                importPrefs.consolidation.first()?.takeIf { it.threadId == threadId }
            },
            saveProgress = { state ->
                // 每片成功后整体覆盖断点（含 done/failed/total），失败片下次续跑只重试未完成片。
                importPrefs.setConsolidation(state)
            },
            clearProgress = { threadId -> importPrefs.clearConsolidation(threadId) },
        )
    }

    /**
     * 导入编排器：`ImportTaskStore` 的依赖，也单独暴露给导入页用来观察
     * 「是否正在建向量」（`semanticIndexing`，UI 据此显示「正在让 TA 按意思找旧事」；
     * 不新增 `ImportRunner` 接口方法，避免动任务状态机）。
     */
    val deepSeekImporter: DeepSeekImporter by lazy {
        DeepSeekImporter(
            repo = importRepository,
            prefs = importPrefs,
            indexer = importIndexer,
            consolidator = importConsolidator,
            database = database,
            // 用户意愿（确认屏复选框）；是否真的联网还由 ImportIndexer.embeddingReady() 决定。
            embedConsent = { importEmbedConsent },
        )
    }

    val awakeningManager: cc.pulsez.app.core.CharacterAwakeningManager by lazy {
        cc.pulsez.app.core.CharacterAwakeningManager(
            storageFile = File(appContext.filesDir, "character_awakening.json"),
            database = database,
            memory = memory,
            chat = chat,
            settings = settings,
            llmClientProvider = { llmClient() },
            recordUsage = { model, usageData ->
                val cost = Pricing.costOf(model, usageData)
                usage.record(cost, 0.0)
            },
            wipeEpochFlow = wipeEpoch,
            scope = appScope,
        )
    }

    /**
     * 进程级导入任务：挂在 [appScope]，切页/锁屏不中断；同一时刻只跑一个任务。
     *
     * 额外确认规则（「>¥1 或超当日剩余额度」）：¥1 由 importer 判定，
     * 这里补「本次整理会让今天超过用户设置的上限」的情况（上限 0 = 不限制）。
     */
    val importTasks: ImportTaskStore by lazy {
        ImportTaskStore(
            scope = appScope,
            importer = deepSeekImporter,
            prefs = importPrefs,
            requiresConsent = { plan ->
                val current = settings.flow.first()
                val snapshot = usage.flow.first()
                val dailyLimitYuanX100 = current.dailyLimitYuanX100
                dailyLimitYuanX100 > 0 &&
                    snapshot.todayYuan + plan.estimatedYuan > dailyLimitYuanX100 / 100.0
            },
        ).also { store ->
            appScope.launch {
                store.state.collect { task ->
                    if (task is cc.pulsez.app.ui.importer.ImportTaskState.Done) {
                        val batch = task.batch
                        if (batch != null && batch.awakenOnImport && batch.createdOrUpdatedCharacterIds.isNotEmpty()) {
                            awakeningManager.enqueueBatch(
                                batchId = batch.batchId,
                                characterIds = batch.createdOrUpdatedCharacterIds,
                            )
                        }
                    }
                }
            }
        }
    }

    /** 导入链路共用的「id 批量取消息 → `ImportMessage`」映射（每批 ≤500 个 SQL 变量）。*/
    private suspend fun loadImportMessages(ids: List<String>): List<ImportMessage> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(IMPORT_MESSAGE_BATCH).flatMap { batch ->
            database.messageDao().byIds(batch).map { message ->
                ImportMessage(
                    id = message.id,
                    role = message.role,
                    text = message.content,
                    at = message.createdAt,
                )
            }
        }
    }

    /** 本次进程内是否已收起过 AI 标识（收起后不可移除，只能折叠）。 */
    var aiBadgeDismissed: Boolean = false

    /**
     * 聊天客户端：配置齐全 + 密钥绑定成立才返回；否则 null，由 UI 引导去配置。
     *
     * v0.5.2：顺序是**先判绑定、再读密钥**——
     * `bindingState` 只做纯字符串/URL 比较；绑定不成立时一次 Keystore 都不碰。
     * 这样「v0.5.1 升级后的脏状态」与「换服务商没重填」都不会把旧密钥拼到当前地址上
     * 旧实现直接读取 `chat_api_key` 并拼接当前 `baseUrl`，可能组合不匹配的密钥与地址。
     *
     * v0.5.3：「设置快照 + 判绑定 + 读密钥 + 构造客户端」整体在 [credentialMutex]
     * 内（[withCredentialReadLock]），与保存 / 清除 / 迁移互斥；构造客户端本身不出网
     * （构造时零请求，测试已锁），真正的对话请求仍由调用方在锁外发起。
     */
    suspend fun llmClient(): LlmClient? = withCredentialReadLock<LlmClient?> { current ->
        if (current.chatBaseUrl.isBlank() || current.chatModel.isBlank()) {
            return@withCredentialReadLock null
        }
        val key = usableKey(
            hasKey = current.hasChatKey,
            keyHost = current.chatKeyHost,
            baseUrl = current.chatBaseUrl,
            secretName = cc.pulsez.app.ui.config.ConfigViewModel.SECRET_CHAT_KEY,
        ) ?: return@withCredentialReadLock null
        LlmClient(http = http, baseUrl = current.chatBaseUrl, apiKey = key)
    }
}
