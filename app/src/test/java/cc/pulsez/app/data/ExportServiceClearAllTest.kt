package cc.pulsez.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.pulsez.app.AppContainer
import cc.pulsez.app.audio.AsrAvailability
import cc.pulsez.app.audio.VoiceInputGate
import cc.pulsez.app.core.ChatEngine
import cc.pulsez.app.core.SpeakerLedgerSnapshot
import cc.pulsez.app.data.attachments.AttachmentErrorCodes
import cc.pulsez.app.data.attachments.AttachmentException
import cc.pulsez.app.data.attachments.AttachmentStore
import cc.pulsez.app.data.db.AttachmentEntity
import cc.pulsez.app.data.db.AttachmentStatus
import cc.pulsez.app.data.group.GroupLedgerStore
import cc.pulsez.app.data.repo.UsageStore
import cc.pulsez.app.data.settings.Consolidation
import cc.pulsez.app.data.settings.ImportPrefs
import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.audio.VoiceRecorderFiles
import cc.pulsez.app.importer.deepseek.ImportCache
import cc.pulsez.app.media.ImagePipeline
import cc.pulsez.app.media.ThumbnailCache
import cc.pulsez.app.ui.chat.VoiceGateUiResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「清空所有数据」端到端测试。
 *
 * 覆盖：
 * - 4 个 DataStore（settings / importPrefs / groupLedger / usage）+ 3 个缓存目录 + DB + 附件目录
 * 在 `clearAll` 后全部归空，**且协议同意记录保留**（data class 相等）；
 * - 失败分支：未安装 AttachmentStore → 先抛且 DB 未动；密钥回读残留 → `STORE_IO` 且
 * `hasChatKey=false`（不谎报可用）；目录「没删干净 / 读不了」两分支都抛 `STORE_IO`；
 * - 门禁：清空后真实入口（`VoiceInputGate` / `container.voiceAvailability`）不再谎报可用；
 * - 幂等：连清两次不崩。
 *
 * 环境：Robolectric，`@Config(application = Application::class)` 换掉 `PulsezApp`，避免它的后台
 * 凭据迁移/更新检查在测试期间改设置（ 测试同做法）。`clearAll` **直接调用**：它内部已经
 * `withContext(Dispatchers.IO)`（Room `clearAllTables` 有主线程断言），测试不要再外层包 IO。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExportServiceClearAllTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    /** 测试安装临时 `AttachmentStore` 前的进程级实例（@After 复原用）。 */
    private var previousStore: AttachmentStore? = null

    private companion object {
        /** `AppContainer` 里 Room 的库名是写死的（`PulsezDatabase.create`）。 */
        const val APP_DB_NAME = "pulsez.db"

        /** 1×1 的合法 PNG（用于给缩略图缓存预置一张真图）。 */
        const val PNG_1X1_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }

    @Before
    fun setUp() = runBlocking {
        previousStore = AttachmentStore.installedOrNull()
        AttachmentStore.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(APP_DB_NAME)
        // Robolectric 同一进程内 DataStore 是按文件名的单例：用例间共享，先清回默认。
        SettingsStore(context).update { it.clear() }
        ImportPrefs(context).clearAll()
        GroupLedgerStore(context).clearAll()
        UsageStore(context).clear()
        File(context.filesDir, "secrets.bin").delete()
        cacheDirs(context).forEach { it.deleteRecursively() }
        ThumbnailCache.evictAll()
    }

    @After
    fun tearDown() = runBlocking {
        val previous = previousStore
        if (previous != null) AttachmentStore.install(previous) else AttachmentStore.resetForTest()
        context.deleteDatabase(APP_DB_NAME)
        File(context.filesDir, "secrets.bin").delete()
        cacheDirs(context).forEach { it.deleteRecursively() }
        ThumbnailCache.evictAll()
    }

    // ------------------------------------------------------------------
    // 1. 端到端：清全部、只留协议同意、幂等
    // ------------------------------------------------------------------

    @Test
    fun clearAllWipesEverythingAndKeepsOnlyAgreement() = runBlocking {
        val store = AttachmentStore(tempFolder.newFolder("files"))
        AttachmentStore.install(store)
        // 附件目录里真放一个文件（隐私承诺的验收物）。
        val attachmentPath = store.save(
            ByteArrayInputStream(ByteArray(32)),
            "wipe-attachment",
            "jpg",
        )
        assertTrue(store.exists(attachmentPath))

        val container = AppContainer(context)
        try {
            // 「本版已同意」：清空后应保留这两项。
            container.settings.agree(SettingsStore.AGREEMENT_VERSION, at = 1L)

            // ① 设置类 DataStore：造一批横跨各分组的非默认值。
            container.settings.setChatEndpoint("https://api.deepseek.com", "deepseek-flash")
            container.settings.setChatKeyPresent(true)
            container.settings.setWindowTier(Settings.TIER_32K)
            container.settings.setDailyLimit(100)
            container.settings.setVoiceIntroSeen(true)
            container.settings.setVisionProbe("deepseek-flash", supported = true, at = 9L)
            container.settings.setUpdateCheckResult(1_700_000_000_000L)
            container.settings.setUpdateIgnoredVersionCode(9L)
            container.settings.setUpdatePendingVersionCode(8L)

            // ② 导入偏好 DataStore：续导线索 + 整理进度 + 「已整理」标记。
            val (character, thread) = container.characters.createWithThread(
                name = "阿淮",
                persona = "温和、话不多",
            )
            container.importPrefs.setLastImport("old-chat.zip", 1_024L, character.id, 5L)
            container.importPrefs.setConsolidation(
                Consolidation(
                    threadId = thread.id,
                    doneShards = setOf(0),
                    failedShards = setOf(1),
                    totalShards = 2,
                ),
            )
            container.importPrefs.markConsolidated(thread.id)

            // ③ 群聊账本 DataStore。
            container.groupLedger.save(
                thread.id,
                SpeakerLedgerSnapshot(lastSpeakers = listOf("a"), silentTurns = mapOf("a" to 0)),
            )

            // ④ 用量 DataStore。
            container.usage.record(0.02, savedRatio = 0.5)
            container.usage.record(0.01, savedRatio = 0.0, category = cc.pulsez.app.data.repo.UsageCategory.VISION)

            // ⑤ 附件表行（本地文件已在前面落盘）。
            container.database.attachmentDao().upsert(
                AttachmentEntity(
                    id = "wipe-attachment",
                    messageId = null,
                    kind = "IMAGE",
                    mime = "image/jpeg",
                    localPath = attachmentPath,
                    fileName = null,
                    sizeBytes = 32L,
                    width = null,
                    height = null,
                    status = AttachmentStatus.DRAFT,
                    errorKind = null,
                    attempts = 0,
                    remoteFileId = null,
                    expiresAt = null,
                    deletedAt = null,
                    createdAt = 1L,
                ),
            )
            assertTrue("预置：附件表有行", container.database.attachmentDao().idsInUse().isNotEmpty())

            // ⑥ 三个缓存目录里真放文件（端到端测试要先真造文件再断言目录空）。
            cacheDirs(context).forEach { dir ->
                dir.mkdirs()
                File(dir, "leftover.bin").writeText("x")
            }
            // ⑦ 同进程缩略图缓存预置一张图（验证 ⑨ evictAll 真的被调用）。
            val image = File(tempFolder.newFolder("thumb"), "a.png")
            image.writeBytes(Base64.getDecoder().decode(PNG_1X1_BASE64))
            ThumbnailCache.load(image, targetEdge = 16)

            // 执行清空（内部已切 IO；测试不要再包 withContext(IO)）。
            ExportService.clearAll(container, clearSecrets = { clearSecretsWithoutAndroidKeyStore() })

            // ---- 设置：只剩协议同意（data class 相等 = 其余每个键都回默认）----
            assertEquals(
                Settings(agreedVersion = SettingsStore.AGREEMENT_VERSION, agreedAt = 1L),
                container.settings.flow.first(),
            )

            // ---- 用量：全零 ----
            val usage = container.usage.flow.first()
            assertEquals(0.0, usage.todayYuan, 0.0)
            assertEquals(0.0, usage.monthYuan, 0.0)
            assertEquals(0, usage.todayMessages)
            assertEquals(0, usage.monthMessages)
            assertEquals(0.0, usage.todayImageYuan, 0.0)
            assertEquals(0.0, usage.todayVoiceYuan, 0.0)

            // ---- 导入偏好：全清 ----
            assertNull("续导线索要清", container.importPrefs.lastImport.first())
            assertNull("整理进度要清", container.importPrefs.consolidation.first())
            assertTrue(
                "「已整理」标记要清",
                container.importPrefs.consolidatedThreads.first().isEmpty(),
            )

            // ---- 群聊账本：全清 ----
            assertNull(container.groupLedger.load(thread.id))

            // ---- DB：角色/会话/附件表都空 ----
            assertTrue("角色表要空", container.characters.list().isEmpty())
            assertNull("会话要空", container.database.threadDao().byId(thread.id))
            assertTrue(
                "附件表要空（clearAllTables 物理清）",
                container.database.attachmentDao().idsInUse().isEmpty(),
            )

            // ---- 附件目录与三个缓存目录：空或不存在 ----
            assertTrue("附件目录要空", store.dir.listFiles().isNullOrEmpty())
            cacheDirs(context).forEach { dir ->
                assertTrue("缓存目录要空或不存在：${dir.name}", dir.listFiles().isNullOrEmpty())
            }

            // ---- 同进程缩略图缓存：清空（⑨ evictAll）----
            assertEquals("缩略图内存缓存要清空", 0, ThumbnailCache.sizeBytes())

            // ---- 门禁：不谎报可用----
            val after = container.settings.flow.first()
            assertFalse("聊天密钥标记要清", after.hasChatKey)
            assertFalse("找旧事密钥标记要清", after.hasEmbedKey)
            assertTrue("聊天密钥归属要清", after.chatKeyHost.isEmpty())
            assertTrue("找旧事密钥归属要清", after.embedKeyHost.isEmpty())
            assertTrue("聊天地址要清", after.chatBaseUrl.isEmpty())
            assertTrue("找旧事地址要清", after.embedBaseUrl.isEmpty())
            assertEquals(
                "空地址时既有口径是 NOT_DEEPSEEK",
                AsrAvailability.NOT_DEEPSEEK,
                VoiceInputGate.availability(after),
            )
            assertNotEquals(
                "真实入口不得再报可用",
                AsrAvailability.AVAILABLE,
                container.voiceAvailability(),
            )
            assertFalse(
                "hasChatKey=false 时麦克风入口隐藏（纯函数）",
                VoiceGateUiResolver.resolve(
                    availability = AsrAvailability.NO_KEY,
                    hasMicrophone = true,
                ).entryVisible,
            )

            // ---- 幂等：再清一次不崩、状态仍是「只剩协议同意」----
            ExportService.clearAll(container, clearSecrets = { clearSecretsWithoutAndroidKeyStore() })
            assertEquals(
                Settings(agreedVersion = SettingsStore.AGREEMENT_VERSION, agreedAt = 1L),
                container.settings.flow.first(),
            )
        } finally {
            runCatching { container.database.close() }
        }
    }

    // ------------------------------------------------------------------
    // 2. 前置失败：未安装 AttachmentStore → 先抛，DB 仍有数据
    // ------------------------------------------------------------------

    @Test
    fun clearAllFailsFastWhenAttachmentStoreMissing() = runBlocking {
        // setUp 已 resetForTest：进程级实例为 null。
        val container = AppContainer(context)
        try {
            container.characters.createWithThread(name = "阿淮", persona = "温和")
            // 用 runCatching 取异常，避免在 runBlocking 里再套 runBlocking（suspend 调用）。
            val error = runCatching {
                ExportService.clearAll(
                    container,
                    clearSecrets = { clearSecretsWithoutAndroidKeyStore() },
                )
            }.exceptionOrNull()
            assertTrue("未安装 AttachmentStore 必须先抛", error is AttachmentException)
            assertEquals(AttachmentErrorCodes.STORE_NOT_INSTALLED, (error as AttachmentException).code)
            assertTrue("fail-fast 时 DB 不能被清", container.characters.list().isNotEmpty())
        } finally {
            runCatching { container.database.close() }
        }
    }

    // ------------------------------------------------------------------
    // 3. deleteTree：唯一删除实现的三个分支
    // ------------------------------------------------------------------

    @Test
    fun deleteTreeThrowsWhenResidualFilesRemain() {
        val fake = FakeCacheDir(children = arrayOf(File("residual")))
        val error = assertThrows(AttachmentException::class.java) {
            ExportService.deleteTree(fake, delete = { false })
        }
        assertEquals(AttachmentErrorCodes.STORE_IO, error.code)
    }

    @Test
    fun deleteTreeThrowsWhenDirectoryUnreadable() {
        // 真机无法稳定造「读不了目录」，用 fake File 覆写 listFiles 返回 null 模拟 IO 失败。
        val fake = FakeCacheDir(children = null)
        val error = assertThrows(AttachmentException::class.java) {
            ExportService.deleteTree(fake, delete = { false })
        }
        assertEquals(AttachmentErrorCodes.STORE_IO, error.code)
    }

    @Test
    fun deleteTreeAcceptsAlreadyEmptyDirectory() {
        // listFiles 返回空数组 = 目录已空（只是目录本身没删掉）→ 幂等成功，不抛。
        val fake = FakeCacheDir(children = arrayOf())
        ExportService.deleteTree(fake, delete = { false })
    }

    @Test
    fun deleteTreeAcceptsMissingDirectory() {
        ExportService.deleteTree(
            File(tempFolder.root, "no-such-dir"),
            delete = { false },
        )
    }

    // ------------------------------------------------------------------
    // 4. 密钥残留：真删（正向）+ 回读仍在（失败可见，阻断 ）
    // ------------------------------------------------------------------

    @Test
    fun clearAllReallyDeletesSecretsFile() = runBlocking {
        // 直接写密文文件；用注入的假清理器避免触碰 AndroidKeyStore。
        File(context.filesDir, "secrets.bin").writeText("${ChatEngine.CHAT_KEY}\tAAAA")
        AttachmentStore.install(AttachmentStore(tempFolder.newFolder("files")))
        val container = AppContainer(context)
        try {
            assertTrue("预置：清空前密文在", container.secrets.has(ChatEngine.CHAT_KEY))
            ExportService.clearAll(container, clearSecrets = { clearSecretsWithoutAndroidKeyStore() })
            assertFalse("清空后不能再读到聊天密钥", container.secrets.has(ChatEngine.CHAT_KEY))
        } finally {
            runCatching { container.database.close() }
        }
    }

    @Test
    fun clearAllFailsVisiblyWhenSecretSurvivesReadBack() = runBlocking {
        AttachmentStore.install(AttachmentStore(tempFolder.newFolder("files")))
        val container = AppContainer(context)
        try {
            container.settings.agree(SettingsStore.AGREEMENT_VERSION, at = 1L)
            container.settings.setChatKeyPresent(true)
            // 模拟删除动作完成但密钥回读仍在；clearAll 必须把它映射为 STORE_IO。
            // 用 runCatching 取异常，避免在 runBlocking 里再套 runBlocking（suspend 调用）。
            val error = runCatching {
                ExportService.clearAll(
                    container,
                    secretStillPresent = { true },
                    clearSecrets = { clearSecretsWithoutAndroidKeyStore() },
                )
            }.exceptionOrNull()
            assertTrue("密钥回读仍在必须抛错", error is AttachmentException)
            assertEquals(AttachmentErrorCodes.STORE_IO, (error as AttachmentException).code)
            // 失败也不得谎报可用：③ 已先清标志，⑤ 失败时同样保持 false。
            val after = container.settings.flow.first()
            assertFalse("失败后 hasChatKey 必须仍是 false", after.hasChatKey)
            assertFalse("失败后 hasEmbedKey 必须仍是 false", after.hasEmbedKey)
            assertTrue("失败后地址也不再是旧值（wipe 已执行）", after.chatBaseUrl.isEmpty())
        } finally {
            runCatching { container.database.close() }
        }
    }

    @Test
    fun `密钥删除失败时 clearAll 映射为 STORE_IO`() = runBlocking {
        AttachmentStore.install(AttachmentStore(tempFolder.newFolder("files")))
        val container = AppContainer(context)
        try {
            val error = runCatching {
                ExportService.clearAll(
                    container,
                    clearSecrets = { throw IOException("模拟密钥删除失败") },
                )
            }.exceptionOrNull()
            assertTrue("密钥删除失败必须转成 AttachmentException", error is AttachmentException)
            assertEquals(AttachmentErrorCodes.STORE_IO, (error as AttachmentException).code)
        } finally {
            runCatching { container.database.close() }
        }
    }

    @Test
    fun `密钥回读失败时 clearAll 映射为 STORE_IO`() = runBlocking {
        AttachmentStore.install(AttachmentStore(tempFolder.newFolder("files")))
        val container = AppContainer(context)
        try {
            val error = runCatching {
                ExportService.clearAll(
                    container,
                    secretStillPresent = { throw IOException("模拟密钥文件回读失败") },
                    clearSecrets = { clearSecretsWithoutAndroidKeyStore() },
                )
            }.exceptionOrNull()
            assertTrue("密钥回读失败必须转成 AttachmentException", error is AttachmentException)
            assertEquals(AttachmentErrorCodes.STORE_IO, (error as AttachmentException).code)
        } finally {
            runCatching { container.database.close() }
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 三个缓存目录（常量复用既有定义，与 `clearAll` ⑧ 一致）。 */
    private fun cacheDirs(ctx: Context): List<File> = listOf(
        VoiceRecorderFiles.directory(ctx.cacheDir),
        File(ctx.cacheDir, ImagePipeline.TEMP_DIR_NAME),
        ImportCache.importDir(ctx.cacheDir),
    )

    /** 清掉测试文件但不调用设备 Keystore；SecretStore.clear 的核验由独立假别名测试覆盖。 */
    private fun clearSecretsWithoutAndroidKeyStore() {
        val file = File(context.filesDir, "secrets.bin")
        if (file.exists() && !file.delete() && file.exists()) {
            throw IOException("测试密钥文件删除失败")
        }
        if (file.exists()) throw IOException("测试密钥文件仍存在")
    }

    /**
 * 假的缓存目录：只覆写 `exists/listFiles`，用于覆盖「没删干净 / 读不了」两个分支
 * （真机无法稳定造权限失败）。
 */
    private class FakeCacheDir(
        private val children: Array<File>?,
    ) : File("fake-cache-dir") {
        override fun exists(): Boolean = true

        override fun listFiles(): Array<File>? = children
    }
}
