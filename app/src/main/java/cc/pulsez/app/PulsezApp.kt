package cc.pulsez.app

import android.app.Application
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.repo.AttachmentRepository
import cc.pulsez.app.importer.deepseek.ImportCache
import cc.pulsez.app.media.ThumbnailCache
import kotlinx.coroutines.launch

class PulsezApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 附件目录实例要在任何 UI 之前装进进程（ExportService.clearAll 只拿得到 AppContainer，
        // 而 AppContainer 不暴露 filesDir；见 AttachmentStore 的进程级安装说明）。
        val attachments = AttachmentStore(filesDir)
        AttachmentStore.install(attachments)
        // 缩略图内存缓存的 onTrimMemory 回调在**进程启动**就注册（v0.5.0
        // ` ）：只在首次选图时注册的话，"冷启动后只浏览历史图"的会话
        // 永远收不到系统内存回调。install 是幂等的、只注册回调、不预解码，不拖慢冷启动。
        ThumbnailCache.install(this)
        cleanupStaleImportCacheInBackground()
        cleanupOrphanAttachmentsInBackground(attachments)
        migrateLegacyCredentialBindingInBackground()
        checkForUpdatesInBackground()
    }

    /**
     * 启动迁移：让 v0.5.1（及更早）留下的「有密钥但没有归属服务商」的旧密钥不可用
     * （v0.5.2，` I / §3.3.5）。
     *
     * 为什么不在设置页打开时做：升级用户可能直接进聊天，旧密钥必须在任何出网之前失效；
     * 放在 [AppContainer.appScope]（`Dispatchers.IO`）异步执行，不阻塞冷启动。
     *
     * 迁移本身每一步 best-effort、失败不崩（`runCatching`）；即使迁移没跑完，`llmClient()` /
     * `asrClient()` / `embeddingConfigured()` / `importEmbedPort` 的读路径守卫也独立生效
     * （`bindingState` 只认已绑定的密钥），所以不会出现「旧密钥配当前地址」。
     * 与设置页保存/清除共用同一把 Mutex（`AppContainer.migrateLegacyCredentialBinding`）。
     */
    private fun migrateLegacyCredentialBindingInBackground() {
        container.appScope.launch {
            runCatching { container.migrateLegacyCredentialBinding() }
        }
    }

    /**
     * 启动时静默检查更新（v0.5.1「App 冷启动」）。
     *
     * 与上面的启动清理同一模式：放 `appScope`（`Dispatchers.IO`）异步执行，**不阻塞冷启动**；
     * 内部再按「已过门槛 + 距上次 ≥24h + 自动检查开关」决定要不要真发请求
     * （`UpdateRepository.checkOnLaunch`）；
     * 任何失败都吞掉（更新源不通不影响聊天；不弹任何东西、不发通知、不请求权限）。
     *
     * 真实链路（releases.pulsez.cc / GitHub）当前可能未就绪（#13–14），
     * 失败是**预期内**的静默结果，不是崩溃点。
     */
    private fun checkForUpdatesInBackground() {
        container.appScope.launch {
            runCatching { container.updateRepository.checkOnLaunch() }
        }
    }

    /**
     * 启动时清掉 24h 前的导入残留 ZIP（`「清理」行）：
     * 成功/失败/停止都会删 cache ZIP，但进程被杀会留下垃圾，这里兜底。
     *
     * 放 `appScope`（`Dispatchers.IO`）异步执行：不阻塞冷启动；清理是 best-effort，
     * 任何异常都吞掉（不写日志、不影响启动，不打印文件名之外的信息）。
     */
    private fun cleanupStaleImportCacheInBackground() {
        val importDir = ImportCache.importDir(cacheDir)
        container.appScope.launch {
            runCatching { ImportCache.cleanupStale(importDir) }
        }
    }

    /**
     * 启动时清理陈旧草稿 + 孤儿附件文件（v0.5.0，`「清空/启动清理」、
     * ` ）。
     *
     * 分两步，**顺序不能反**：
     * 1. `sweepStaleDrafts`：把 24h 前、还没绑定消息的 DRAFT/FAILED 行软删 + 删文件
     *    （v0.5.0 `）。没有这一步，这些行是 keep 集合里的活行，
     *    下面那步永远不会回收它们的文件；
     * 2. `cleanupOrphans`：对账口径 = DB 里 `deletedAt IS NULL` 的附件 id 是 keep 集合；
     *    `filesDir/attachments` 下文件名对应的 id 不在集合里（含 `.part` 半成品）就是孤儿，删掉。
     *    覆盖场景：进程在「消息软删 → 删文件」之间被杀、写文件中途崩溃、旧版本残留。
     *
     * 放 `appScope` 后台执行、失败静默（`runCatching`）：启动清理不能拖慢/拖崩冷启动。
     */
    private fun cleanupOrphanAttachmentsInBackground(store: AttachmentStore) {
        container.appScope.launch {
            // Retry durable role-deletion file records even when the directory is empty.
            // Basis: CharacterDeletionRepository.retryPendingAttachmentFiles; AttachmentStore.delete is idempotent for missing files.
            runCatching { container.roleDeletions.retryPendingAttachmentFiles(store) }
            // 没有附件目录/目录为空 = 从没存过附件，不用开 DB 对账：
            // 既省陈旧草稿与孤儿扫描的冷启动 IO，也避免 Robolectric 里产生无谓的扫描副作用。
            if (store.dir.listFiles().isNullOrEmpty()) return@launch
            val repo = AttachmentRepository(container.database)
            // 先软删陈旧草稿/失败态（同时删文件），再让孤儿清理兜底。
            runCatching { repo.sweepStaleDrafts(store) }
            runCatching { repo.cleanupOrphans(store) }
        }
    }
}
