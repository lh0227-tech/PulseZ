package cc.pulsez.app.core

import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.repo.ChatRepository
import cc.pulsez.app.data.repo.MemoryRepository
import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.mem.TokenEstimator
import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ChatMessage
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.LlmClient
import cc.pulsez.app.net.LlmEvent
import cc.pulsez.app.net.Usage
import cc.pulsez.app.net.asInternalRequest
import cc.pulsez.app.net.isDeepSeekEndpoint
import java.io.File
import java.util.TreeSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@Serializable
enum class AwakeningStatus {
    QUEUED,
    STREAMING,
    READY_FOR_CEREMONY,
    FAILED,
    COMPLETED,
}

@Serializable
data class AwakeningProfileData(
    val name: String? = null,
    val userName: String? = null,
    val persona: String? = null,
    val styleSamples: List<String> = emptyList(),
    val memoryPatchJson: String? = null,
)

@Serializable
data class CharacterAwakeningEntry(
    val characterId: String,
    val batchId: String? = null,
    val status: AwakeningStatus = AwakeningStatus.QUEUED,
    val whispers: List<String> = emptyList(),
    val reunionSubtitle: String = "",
    val reunionGreeting: String = "",
    val profile: AwakeningProfileData? = null,
    val errorMessage: String? = null,
    val sampledMessageIds: List<String> = emptyList(),
    val enqueuedAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
private data class AwakeningStoreSnapshot(
    val entries: Map<String, CharacterAwakeningEntry> = emptyMap(),
    val processedBatchIds: List<String> = emptyList(),
)

data class AwakeningCommitResult(
    val updatedName: String? = null,
    val greetingMessage: MessageEntity? = null,
)

/**
 * 协调导入后多角色各自的「回忆苏醒」流程：
 * - 后台按队预生成只写 `filesDir/character_awakening.json`，绝不提前写 Room `memory_item`/`message`，
 *   保证结果页点击「撤销这次导入」时零 `MEMORY_CHANGED` 冲突；
 * - 用户点进某个角色的聊天室时优先提权该角色，并独立消费专属独白与脉冲星重逢仪式，绝不串台；
 * - 仅在仪式启动/跳过落定时刻一次性写入角色资料补全、双时态记忆补丁与首句重逢问候。
 */
class CharacterAwakeningManager(
    private val storageFile: File,
    private val database: PulsezDatabase,
    private val memory: MemoryRepository,
    private val chat: ChatRepository,
    private val settings: SettingsStore,
    private val llmClientProvider: suspend () -> LlmClient?,
    private val recordUsage: suspend (model: String, usage: Usage) -> Unit = { _, _ -> },
    private val wipeEpochFlow: StateFlow<Long>? = null,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<Map<String, CharacterAwakeningEntry>>(emptyMap())
    val entries: StateFlow<Map<String, CharacterAwakeningEntry>> = _entries.asStateFlow()

    private val processedBatchIds = LinkedHashSet<String>()
    private val queueOrder = ArrayDeque<String>()
    private val inFlightCommits = HashMap<String, kotlinx.coroutines.CompletableDeferred<AwakeningCommitResult>>()
    private var activeCharacterId: String? = null
    private var activeJob: Job? = null
    private var lastWipeEpoch: Long = wipeEpochFlow?.value ?: 0L
    private var loadedFromDisk = false

    init {
        if (wipeEpochFlow != null) {
            scope.launch {
                wipeEpochFlow.collect { epoch ->
                    if (epoch != lastWipeEpoch) {
                        lastWipeEpoch = epoch
                        clearAllForWipe()
                    }
                }
            }
        }
    }

    fun observeCharacter(characterId: String): Flow<CharacterAwakeningEntry?> =
        entries.map { it[characterId] }.distinctUntilChanged()

    suspend fun currentEntry(characterId: String): CharacterAwakeningEntry? {
        ensureLoaded()
        if (isCharacterDeleted(characterId)) {
            cancelAndClear(listOf(characterId))
            return null
        }
        return _entries.value[characterId]
    }

    /**
     * 批量入队（幂等：同一 `batchId` 不重复入队）。
     */
    suspend fun enqueueBatch(
        batchId: String?,
        characterIds: List<String>,
    ) {
        val cleanIds = characterIds.asSequence().filter(String::isNotBlank).distinct().toList()
        if (cleanIds.isEmpty()) return
        ensureLoaded()

        val liveIds = cleanIds.filter { !isCharacterDeleted(it) }
        if (liveIds.isEmpty()) return

        mutex.withLock {
            if (batchId != null && batchId in processedBatchIds) return
            val now = nowProvider()
            val updated = _entries.value.toMutableMap()
            for (characterId in liveIds) {
                val existing = updated[characterId]
                if (existing != null &&
                    (existing.status == AwakeningStatus.STREAMING || existing.status == AwakeningStatus.READY_FOR_CEREMONY)
                ) {
                    continue
                }
                updated[characterId] = CharacterAwakeningEntry(
                    characterId = characterId,
                    batchId = batchId,
                    status = AwakeningStatus.QUEUED,
                    enqueuedAt = now,
                    updatedAt = now,
                )
                if (characterId !in queueOrder && characterId != activeCharacterId) {
                    queueOrder.addLast(characterId)
                }
            }
            if (batchId != null) {
                processedBatchIds.add(batchId)
                while (processedBatchIds.size > MAX_PROCESSED_BATCHES) {
                    val first = processedBatchIds.firstOrNull() ?: break
                    processedBatchIds.remove(first)
                }
            }
            publishAndPersistLocked(updated)
            scheduleNextLocked()
        }
    }

    /**
     * 用户点进某角色聊天室时调用：
     * - 已软删/不存在 → 惰性清理；
     * - 尚在 `QUEUED` → 立即插队提权（若后台正跑未进房的其它角色，先将其退回队列并立即切过来）；
     * - 若已到 `READY_FOR_CEREMONY` 或 `STREAMING` → 原样保持供 UI 消费。
     */
    suspend fun onEnterChat(characterId: String): CharacterAwakeningEntry? {
        ensureLoaded()
        if (isCharacterDeleted(characterId)) {
            cancelAndClear(listOf(characterId))
            return null
        }
        val jobToJoin: Job? = mutex.withLock {
            val current = _entries.value[characterId] ?: return@withLock null
            when (current.status) {
                AwakeningStatus.COMPLETED -> null
                AwakeningStatus.QUEUED -> {
                    promoteToFrontLocked(characterId)
                    preemptOtherLocked(targetId = characterId)
                }
                AwakeningStatus.STREAMING,
                AwakeningStatus.READY_FOR_CEREMONY,
                AwakeningStatus.FAILED -> null
            }
        }
        if (jobToJoin != null) {
            runCatching { jobToJoin.join() }
            mutex.withLock {
                if (activeCharacterId == null) {
                    scheduleNextLocked()
                }
            }
        }
        return _entries.value[characterId]
    }

    suspend fun retryCharacter(characterId: String) {
        ensureLoaded()
        if (isCharacterDeleted(characterId)) {
            cancelAndClear(listOf(characterId))
            return
        }
        val jobToJoin: Job? = mutex.withLock {
            val existing = _entries.value[characterId] ?: return@withLock null
            val updated = _entries.value.toMutableMap()
            updated[characterId] = existing.copy(
                status = AwakeningStatus.QUEUED,
                whispers = emptyList(),
                reunionSubtitle = "",
                reunionGreeting = "",
                profile = null,
                errorMessage = null,
                updatedAt = nowProvider(),
            )
            promoteToFrontLocked(characterId)
            publishAndPersistLocked(updated)
            preemptOtherLocked(targetId = characterId)
        }
        if (jobToJoin != null) {
            runCatching { jobToJoin.join() }
            mutex.withLock {
                if (activeCharacterId == null) {
                    scheduleNextLocked()
                }
            }
        }
    }

    /**
     * 撤销导入或角色删除时调用：先取消相关在途任务，再彻底移除缓存条目，绝不碰 Room。
     */
    suspend fun cancelAndClear(
        characterIds: Collection<String>,
        batchId: String? = null,
    ) {
        ensureLoaded()
        val idSet = characterIds.filter(String::isNotBlank).toSet()
        if (idSet.isEmpty() && batchId.isNullOrBlank()) return

        val jobToJoin: Job? = mutex.withLock {
            var toJoin: Job? = null
            val activeId = activeCharacterId
            val activeEntry = activeId?.let { _entries.value[it] }
            val cancelActive = activeId != null && (
                activeId in idSet ||
                    (batchId != null && activeEntry?.batchId == batchId)
                )
            if (cancelActive) {
                toJoin = activeJob
                activeJob?.cancel()
                activeJob = null
                activeCharacterId = null
            }
            val updated = _entries.value.toMutableMap()
            val removedIds = updated.entries
                .filter { (id, entry) ->
                    id in idSet || (batchId != null && entry.batchId == batchId)
                }
                .map { it.key }
            removedIds.forEach { id ->
                updated.remove(id)
                queueOrder.remove(id)
            }
            if (batchId != null) {
                processedBatchIds.remove(batchId)
            }
            publishAndPersistLocked(updated)
            toJoin
        }
        if (jobToJoin != null) {
            withContext(NonCancellable) {
                runCatching { jobToJoin.join() }
            }
        }
        mutex.withLock {
            if (activeCharacterId == null) {
                scheduleNextLocked()
            }
        }
    }

    /**
     * 用户真正进入重逢仪式或点「先直接开聊」时，将缓存的角色资料、双时态记忆补丁与首句问候
     * 一次性写入 Room，并从待唤醒集合中移除该角色（标记完成）。
     */
    suspend fun commitAwakeningToRoom(
        characterId: String,
        includeGreeting: Boolean = true,
    ): AwakeningCommitResult {
        ensureLoaded()
        if (isCharacterDeleted(characterId)) {
            cancelAndClear(listOf(characterId))
            return AwakeningCommitResult()
        }
        val expectedWipe = wipeEpochFlow?.value ?: 0L
        var jobToCancel: Job? = null
        var ownedDeferred: kotlinx.coroutines.CompletableDeferred<AwakeningCommitResult>? = null
        var existingDeferred: kotlinx.coroutines.CompletableDeferred<AwakeningCommitResult>? = null
        val snapshotEntry = mutex.withLock {
            inFlightCommits[characterId]?.let {
                existingDeferred = it
                return@withLock null
            }
            val entry = _entries.value[characterId] ?: return@withLock null
            if (activeCharacterId == characterId) {
                jobToCancel = activeJob
                activeJob?.cancel()
                activeJob = null
                activeCharacterId = null
            }
            queueOrder.remove(characterId)
            val updated = _entries.value.toMutableMap()
            updated.remove(characterId)
            publishAndPersistLocked(updated)
            ownedDeferred = kotlinx.coroutines.CompletableDeferred()
            inFlightCommits[characterId] = ownedDeferred!!
            entry
        }
        existingDeferred?.let { return it.await() }
        val entry = snapshotEntry ?: return AwakeningCommitResult()
        val deferred = ownedDeferred!!

        try {
            if (jobToCancel != null) {
                withContext(NonCancellable) { runCatching { jobToCancel?.join() } }
            }
            mutex.withLock {
                if (activeCharacterId == null) scheduleNextLocked()
            }

            if (wipeEpochFlow != null && wipeEpochFlow.value != expectedWipe) {
                return AwakeningCommitResult().also { deferred.complete(it) }
            }
            val character = database.characterDao().byId(characterId)
                ?: return AwakeningCommitResult().also { deferred.complete(it) }
            val thread = chat.ensureThread(characterId)
            val openSegmentId = thread.activeSegmentId
                ?: return AwakeningCommitResult().also { deferred.complete(it) }
            val profile = entry.profile

            var updatedName: String? = null
            if (profile != null) {
                updatedName = applyCharacterProfileUpdates(character, profile)
                val patchJson = profile.memoryPatchJson
                if (!patchJson.isNullOrBlank()) {
                    runCatching {
                        memory.applyPatch(
                            threadId = thread.id,
                            patchJson = patchJson,
                            segmentId = null,
                            now = nowProvider(),
                            ownerId = null,
                        )
                    }
                }
            }

            var greetingRow: MessageEntity? = null
            val greeting = entry.reunionGreeting.trim()
            if (includeGreeting && greeting.isNotEmpty()) {
                if (wipeEpochFlow == null || wipeEpochFlow.value == expectedWipe) {
                    greetingRow = chat.append(
                        threadId = thread.id,
                        segmentId = openSegmentId,
                        role = "assistant",
                        text = greeting,
                        tokens = TokenEstimator.estimate(greeting),
                        createdAt = nowProvider(),
                    )
                }
            }

            return AwakeningCommitResult(
                updatedName = updatedName,
                greetingMessage = greetingRow,
            ).also { deferred.complete(it) }
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            mutex.withLock {
                if (inFlightCommits[characterId] === deferred) {
                    inFlightCommits.remove(characterId)
                }
            }
        }
    }

    private suspend fun applyCharacterProfileUpdates(
        character: CharacterEntity,
        profile: AwakeningProfileData,
    ): String? {
        val cleanName = profile.name?.trim()?.takeIf {
            it.isNotEmpty() && it !in PLACEHOLDER_NAMES
        }
        val shouldUpdateName = character.name.trim() in PLACEHOLDER_NAMES && cleanName != null
        val cleanPersona = profile.persona?.trim()?.takeIf { it.isNotEmpty() }
        val shouldUpdatePersona = character.persona.isBlank() && cleanPersona != null
        val cleanUserName = profile.userName?.trim()?.takeIf { it.isNotEmpty() }
        val shouldUpdateUserName = character.userName.isNullOrBlank() && cleanUserName != null
        val cleanSamples = profile.styleSamples
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_STYLE_SAMPLES)
            .joinToString("\u0001")
            .takeIf { it.isNotEmpty() }
        val shouldUpdateSamples = character.styleSamples.isNullOrBlank() && cleanSamples != null

        if (!shouldUpdateName && !shouldUpdatePersona && !shouldUpdateUserName && !shouldUpdateSamples) {
            return null
        }

        database.openHelper.writableDatabase.apply {
            beginTransaction()
            try {
                if (shouldUpdateName) {
                    execSQL(
                        "UPDATE `character` SET `name` = ?, `updatedAt` = ? WHERE `id` = ?",
                        arrayOf<Any>(cleanName!!, nowProvider(), character.id),
                    )
                }
                if (shouldUpdatePersona) {
                    execSQL(
                        "UPDATE `character` SET `persona` = ? WHERE `id` = ? AND TRIM(`persona`) = ''",
                        arrayOf<Any>(cleanPersona!!, character.id),
                    )
                }
                if (shouldUpdateUserName) {
                    execSQL(
                        "UPDATE `character` SET `userName` = ? WHERE `id` = ? AND (`userName` IS NULL OR TRIM(`userName`) = '')",
                        arrayOf<Any>(cleanUserName!!, character.id),
                    )
                }
                if (shouldUpdateSamples) {
                    execSQL(
                        "UPDATE `character` SET `styleSamples` = ? WHERE `id` = ? AND (`styleSamples` IS NULL OR TRIM(`styleSamples`) = '')",
                        arrayOf<Any>(cleanSamples!!, character.id),
                    )
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        return if (shouldUpdateName) cleanName else null
    }

    private suspend fun ensureLoaded() {
        if (loadedFromDisk) return
        mutex.withLock {
            if (loadedFromDisk) return
            val loaded = withContext(Dispatchers.IO) { readFromDisk() }
            val restored = LinkedHashMap<String, CharacterAwakeningEntry>()
            val now = nowProvider()
            for ((id, entry) in loaded.entries) {
                if (entry.status == AwakeningStatus.COMPLETED) continue
                val normalized = if (entry.status == AwakeningStatus.STREAMING) {
                    entry.copy(
                        status = AwakeningStatus.QUEUED,
                        whispers = emptyList(),
                        reunionSubtitle = "",
                        reunionGreeting = "",
                        profile = null,
                        updatedAt = now,
                    )
                } else {
                    entry
                }
                restored[id] = normalized
                if (normalized.status == AwakeningStatus.QUEUED) {
                    queueOrder.addLast(id)
                }
            }
            processedBatchIds.addAll(loaded.processedBatchIds.takeLast(MAX_PROCESSED_BATCHES))
            _entries.value = restored
            loadedFromDisk = true
            scheduleNextLocked()
        }
    }

    private fun promoteToFrontLocked(characterId: String) {
        queueOrder.remove(characterId)
        queueOrder.addFirst(characterId)
    }

    private fun preemptOtherLocked(targetId: String): Job? {
        val runningOther = activeCharacterId
        if (runningOther == null || runningOther == targetId) {
            scheduleNextLocked()
            return null
        }
        val otherJob = activeJob
        activeJob = null
        activeCharacterId = null
        val updated = _entries.value.toMutableMap()
        val otherEntry = updated[runningOther]
        if (otherEntry != null &&
            (otherEntry.status == AwakeningStatus.STREAMING || otherEntry.status == AwakeningStatus.QUEUED)
        ) {
            updated[runningOther] = otherEntry.copy(
                status = AwakeningStatus.QUEUED,
                whispers = emptyList(),
                reunionSubtitle = "",
                reunionGreeting = "",
                profile = null,
                updatedAt = nowProvider(),
            )
            queueOrder.remove(runningOther)
            queueOrder.addLast(runningOther)
            publishAndPersistLocked(updated)
        }
        otherJob?.cancel()
        return otherJob
    }

    private fun scheduleNextLocked() {
        if (activeCharacterId != null) return
        while (queueOrder.isNotEmpty()) {
            val nextId = queueOrder.removeFirst()
            val entry = _entries.value[nextId]
            if (entry == null || entry.status != AwakeningStatus.QUEUED) continue
            activeCharacterId = nextId
            activeJob = scope.launch {
                runAwakeningJob(nextId)
            }
            break
        }
    }

    private suspend fun runAwakeningJob(characterId: String) {
        val runWipeEpoch = wipeEpochFlow?.value ?: 0L
        try {
            if (isCharacterDeleted(characterId)) {
                cancelAndClear(listOf(characterId))
                return
            }
            val character = database.characterDao().byId(characterId)
            if (character == null || character.deletedAt != null) {
                cancelAndClear(listOf(characterId))
                return
            }

            mutex.withLock {
                val current = _entries.value[characterId] ?: return
                if (current.status != AwakeningStatus.QUEUED) return
                val updated = _entries.value.toMutableMap()
                updated[characterId] = current.copy(
                    status = AwakeningStatus.STREAMING,
                    whispers = emptyList(),
                    errorMessage = null,
                    updatedAt = nowProvider(),
                )
                publishAndPersistLocked(updated)
            }

            val sampled = loadAndSampleMessages(characterId)
            if (sampled.isEmpty()) {
                markFailed(characterId, FAILURE_COPY)
                return
            }

            val client = runCatching { llmClientProvider() }.getOrNull()
            val currentSettings = runCatching { settings.flow.first() }.getOrNull()
            if (client == null || currentSettings == null || currentSettings.chatModel.isBlank()) {
                markFailed(characterId, FAILURE_COPY)
                return
            }

            val request = buildAwakeningRequest(
                model = currentSettings.chatModel,
                baseUrl = currentSettings.chatBaseUrl,
                character = character,
                sampledMessages = sampled,
            )

            val parser = AwakeningStreamParser()
            var terminalError: String? = null
            var clearRefusedWhispers = false
            var finished = false
            var latestUsage: Usage? = null
            val deepSeekEndpoint = isDeepSeekEndpoint(currentSettings.chatBaseUrl)

            withTimeout(timeoutMs) {
                client.stream(request).collect { event ->
                    if (wipeEpochFlow != null && wipeEpochFlow.value != runWipeEpoch) {
                        throw CancellationException("wiped")
                    }
                    when (event) {
                        is LlmEvent.Delta -> {
                            val newWhispers = parser.appendDelta(event.text)
                            if (newWhispers.isNotEmpty()) {
                                appendWhispers(characterId, newWhispers)
                            }
                        }
                        is LlmEvent.Reasoning -> Unit
                        is LlmEvent.ToolProgress -> Unit
                        is LlmEvent.Finished -> {
                            finished = true
                            latestUsage = event.usage
                            val tailWhispers = parser.finish()
                            if (tailWhispers.isNotEmpty()) {
                                appendWhispers(characterId, tailWhispers)
                            }
                        }
                        is LlmEvent.Failed -> {
                            terminalError = FAILURE_COPY
                            // DeepSeek safety docs: https://api-docs.deepseek.com/guides/safety/；审核在输出完成后，
                            // 终态拒绝时撤掉本轮已增量解析的 whispers，普通断流/取消沿用旧语义。
                            clearRefusedWhispers = deepSeekEndpoint &&
                                event.error.kind == ApiError.Kind.FILTERED
                        }
                    }
                }
            }

            if (wipeEpochFlow != null && wipeEpochFlow.value != runWipeEpoch) return

            if (!finished || terminalError != null) {
                markFailed(
                    characterId,
                    terminalError ?: FAILURE_COPY,
                    clearWhispers = clearRefusedWhispers,
                )
                return
            }

            val finalParsed = parser.buildResult(sampled.map { it.id })
            if (finalParsed.whispers.isEmpty()) {
                markFailed(characterId, FAILURE_COPY)
                return
            }

            latestUsage?.let { usageData ->
                if (wipeEpochFlow == null || wipeEpochFlow.value == runWipeEpoch) {
                    runCatching { recordUsage(currentSettings.chatModel, usageData) }
                }
            }

            mutex.withLock {
                val current = _entries.value[characterId] ?: return@withLock
                if (current.status != AwakeningStatus.STREAMING) return@withLock
                val updated = _entries.value.toMutableMap()
                updated[characterId] = current.copy(
                    status = AwakeningStatus.READY_FOR_CEREMONY,
                    whispers = finalParsed.whispers,
                    reunionSubtitle = finalParsed.reunionSubtitle,
                    reunionGreeting = finalParsed.reunionGreeting,
                    profile = finalParsed.profile,
                    sampledMessageIds = sampled.map { it.id },
                    errorMessage = null,
                    updatedAt = nowProvider(),
                )
                publishAndPersistLocked(updated)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            markFailed(characterId, FAILURE_COPY)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (activeCharacterId == characterId) {
                        activeCharacterId = null
                        activeJob = null
                        scheduleNextLocked()
                    }
                }
            }
        }
    }

    private suspend fun appendWhispers(characterId: String, newWhispers: List<String>) {
        mutex.withLock {
            val current = _entries.value[characterId] ?: return@withLock
            if (current.status != AwakeningStatus.STREAMING) return@withLock
            val merged = (current.whispers + newWhispers)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_WHISPERS)
            if (merged == current.whispers) return@withLock
            val updated = _entries.value.toMutableMap()
            updated[characterId] = current.copy(
                whispers = merged,
                updatedAt = nowProvider(),
            )
            _entries.value = updated
        }
    }

    private suspend fun markFailed(
        characterId: String,
        message: String,
        clearWhispers: Boolean = false,
    ) {
        mutex.withLock {
            val current = _entries.value[characterId] ?: return@withLock
            if (current.status == AwakeningStatus.COMPLETED) return@withLock
            val updated = _entries.value.toMutableMap()
            updated[characterId] = current.copy(
                status = AwakeningStatus.FAILED,
                errorMessage = message,
                whispers = if (clearWhispers) emptyList() else current.whispers,
                updatedAt = nowProvider(),
            )
            publishAndPersistLocked(updated)
        }
    }

    private suspend fun clearAllForWipe() {
        val jobToJoin: Job? = mutex.withLock {
            val job = activeJob
            activeJob?.cancel()
            activeJob = null
            activeCharacterId = null
            queueOrder.clear()
            processedBatchIds.clear()
            loadedFromDisk = true
            publishAndPersistLocked(emptyMap())
            job
        }
        if (jobToJoin != null) {
            withContext(NonCancellable) { runCatching { jobToJoin.join() } }
        }
    }

    private suspend fun isCharacterDeleted(characterId: String): Boolean {
        if (database.deletedCharacterDao().contains(characterId)) return true
        val character = database.characterDao().byId(characterId)
        return character == null || character.deletedAt != null
    }

    private suspend fun loadAndSampleMessages(characterId: String): List<MessageEntity> {
        val thread = database.threadDao().latestForCharacter(characterId) ?: return emptyList()
        val segments = database.segmentDao().forThread(thread.id).sortedBy { it.ordinal }
        val chronological = buildList {
            for (segment in segments) {
                val rows = database.messageDao().bySegment(segment.id)
                    .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                addAll(rows)
            }
        }
        return sampleRealMessages(chronological)
    }

    private fun publishAndPersistLocked(map: Map<String, CharacterAwakeningEntry>) {
        val immutable = map.toMap()
        _entries.value = immutable
        writeToDisk(
            AwakeningStoreSnapshot(
                entries = immutable,
                processedBatchIds = processedBatchIds.toList(),
            ),
        )
    }

    private fun readFromDisk(): AwakeningStoreSnapshot {
        if (!storageFile.isFile) return AwakeningStoreSnapshot()
        return runCatching {
            val raw = storageFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) return AwakeningStoreSnapshot()
            json.decodeFromString<AwakeningStoreSnapshot>(raw)
        }.getOrDefault(AwakeningStoreSnapshot())
    }

    private fun writeToDisk(snapshot: AwakeningStoreSnapshot) {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(snapshot)
            val temp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            temp.writeText(encoded, Charsets.UTF_8)
            if (!temp.renameTo(storageFile)) {
                storageFile.writeText(encoded, Charsets.UTF_8)
                temp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 28_000L
        const val FULL_FEED_MAX_CHARS: Int = 14_000
        const val EARLIEST_TURN_COUNT: Int = 4
        const val MIDDLE_BUCKET_COUNT: Int = 8
        const val LATEST_TURN_COUNT: Int = 20
        const val AWAKEN_TEMPERATURE: Double = 1.1
        const val MAX_WHISPERS: Int = 5
        const val MAX_REUNION_SUBTITLE_CHARS: Int = 18
        const val MAX_STYLE_SAMPLES: Int = 4
        private const val MAX_SINGLE_MESSAGE_CHARS: Int = 1_200
        private const val MAX_PROCESSED_BATCHES: Int = 32

        const val FAILURE_COPY: String = "信号有些摇晃，TA 还没完全理清思绪"
        const val DEFAULT_REUNION_SUBTITLE: String = "隔着漫长距离，你的信号我一直听得见"

        private val PLACEHOLDER_NAMES = setOf("", "TA", "未命名", "新对话", "未命名角色")
        private val FENCED_CODE_BLOCK = Regex("```[\\s\\S]*?```")
        private val INLINE_CODE = Regex("`[^`\\n]+`")

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 纯时间轴与自然对话长度的三段采样（零预设词表）：
         * - 总字数 <= 14,000：全量按时间正序；
         * - 总字数 > 14,000：最早 4 轮 + 中间均分 8 桶每桶非代码块字数最长 1 轮及其前后邻句 + 最新 20 轮。
         */
        internal fun sampleRealMessages(chronological: List<MessageEntity>): List<MessageEntity> {
            if (chronological.isEmpty()) return emptyList()
            val totalChars = chronological.sumOf { it.content.length }
            if (totalChars <= FULL_FEED_MAX_CHARS) return chronological

            val turns = groupIntoTurns(chronological)
            if (turns.isEmpty()) return emptyList()

            val selectedTurnIndices = TreeSet<Int>()
            val earliestEnd = minOf(EARLIEST_TURN_COUNT, turns.size)
            for (i in 0 until earliestEnd) selectedTurnIndices.add(i)

            val latestStart = maxOf(0, turns.size - LATEST_TURN_COUNT)
            for (i in latestStart until turns.size) selectedTurnIndices.add(i)

            if (latestStart > earliestEnd) {
                val middleSize = latestStart - earliestEnd
                val bucketCount = minOf(MIDDLE_BUCKET_COUNT, middleSize)
                for (b in 0 until bucketCount) {
                    val bucketStart = earliestEnd + (b * middleSize) / bucketCount
                    val bucketEnd = earliestEnd + ((b + 1) * middleSize) / bucketCount
                    if (bucketStart >= bucketEnd) continue
                    var bestIdx = bucketStart
                    var bestScore = -1
                    for (idx in bucketStart until bucketEnd) {
                        val score = turnNonCodeLength(turns[idx])
                        if (score > bestScore) {
                            bestScore = score
                            bestIdx = idx
                        }
                    }
                    selectedTurnIndices.add((bestIdx - 1).coerceAtLeast(0))
                    selectedTurnIndices.add(bestIdx)
                    selectedTurnIndices.add((bestIdx + 1).coerceAtMost(turns.lastIndex))
                }
            }

            return selectedTurnIndices.flatMap { turns[it] }
        }

        private fun groupIntoTurns(messages: List<MessageEntity>): List<List<MessageEntity>> {
            val turns = mutableListOf<List<MessageEntity>>()
            var current = mutableListOf<MessageEntity>()
            for (msg in messages) {
                if (msg.role == "user" && current.isNotEmpty()) {
                    turns.add(current)
                    current = mutableListOf()
                }
                current.add(msg)
            }
            if (current.isNotEmpty()) turns.add(current)
            return turns
        }

        private fun turnNonCodeLength(turn: List<MessageEntity>): Int =
            turn.sumOf { stripCodeLength(it.content) }

        internal fun stripCodeLength(text: String): Int {
            val withoutBlocks = FENCED_CODE_BLOCK.replace(text, "")
            val withoutInline = INLINE_CODE.replace(withoutBlocks, "")
            return withoutInline.trim().length
        }

        internal fun buildAwakeningRequest(
            model: String,
            baseUrl: String,
            character: CharacterEntity,
            sampledMessages: List<MessageEntity>,
        ): ChatRequest {
            val systemPrompt = """
                你正在唤醒一位基于真实历史对话重建的陪伴角色。请严格只根据下面提供的真实聊天记录，以该角色（TA）的第一人称口吻回忆与用户的过往。
                硬性规则：
                1. 必须 100% 忠于提供的真实聊天记录，严禁编造、臆测记录里不存在的事件、称呼或细节；没有根据的记忆项请留空。
                2. 独白（[[whisper]]）与问候（[[greeting]]）必须使用“你”和“我”对话，贴合记录里 TA 的真实说话语气与性格，严禁任何客服套话。
                3. [[profile]] 里的 persona 字段是系统档案描述，聚焦性格、说话方式与相处氛围，不要写入“AI 助手”“数字伙伴”“没有实体”等身份标签；代词只能用“TA”或角色名，严禁使用“她”或“他”。
                4. 严格按以下顺序逐行输出，不要加多余开场白或解释：
                [[whisper]] 第一条具体回忆独白（12到35字，第一人称）
                [[whisper]] 第二条具体回忆独白
                [[whisper]] 第三条具体回忆独白
                [[whisper]] 第四条具体回忆独白
                [[reunion_sub]] 一句重逢副标题（不超过18个字）
                [[greeting]] 解锁后发在聊天里的第一句自然重逢问候
                [[profile]] {"name":"角色名","userName":null,"persona":"人设描述","styleSamples":["代表台词1","代表台词2"],"facts":[{"k":"键","v":"事实"}],"events":[{"v":"一起经历的事"}],"prefs":[{"v":"用户偏好"}],"relation":{"stage":"关系阶段","nicknameByMe":"我怎么叫用户","nicknameForMe":"用户怎么叫我","mood":"相处氛围"},"boundaries":[{"v":"不想被提起的边界"}]}
            """.trimIndent()

            val transcript = buildString {
                append("当前已知名字：").append(character.name).append('\n')
                if (character.persona.isNotBlank()) {
                    append("当前已知人设：").append(character.persona.take(500)).append('\n')
                }
                if (!character.userName.isNullOrBlank()) {
                    append("已知称呼：").append(character.userName).append('\n')
                }
                append("以下是按时间顺序采样的真实历史聊天记录：\n")
                sampledMessages.forEach { msg ->
                    val speaker = if (msg.role == "user") "用户" else "TA"
                    val body = msg.content.trim().let {
                        if (it.length > MAX_SINGLE_MESSAGE_CHARS) it.take(MAX_SINGLE_MESSAGE_CHARS) else it
                    }
                    append(speaker).append("：").append(body).append('\n')
                }
            }

            return ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = transcript),
                ),
                temperature = AWAKEN_TEMPERATURE,
                topP = 1.0,
                maxTokens = 2_048,
                stream = true,
            ).asInternalRequest(baseUrl)
        }
    }

    internal data class ParsedAwakeningOutput(
        val whispers: List<String>,
        val reunionSubtitle: String,
        val reunionGreeting: String,
        val profile: AwakeningProfileData?,
    )

    internal class AwakeningStreamParser {
        private val lineBuffer = StringBuilder()
        private val whispers = mutableListOf<String>()
        private var reunionSubtitle: String = ""
        private var reunionGreeting: String = ""
        private var inProfile = false
        private val profileBuffer = StringBuilder()

        fun appendDelta(chunk: String): List<String> {
            lineBuffer.append(chunk)
            val newlyParsedWhispers = mutableListOf<String>()
            while (true) {
                val nl = lineBuffer.indexOf('\n')
                if (nl < 0) break
                val line = lineBuffer.substring(0, nl).trimEnd('\r')
                lineBuffer.delete(0, nl + 1)
                processLine(line, newlyParsedWhispers)
            }
            return newlyParsedWhispers
        }

        fun finish(): List<String> {
            val newlyParsedWhispers = mutableListOf<String>()
            if (lineBuffer.isNotEmpty()) {
                val remaining = lineBuffer.toString().trimEnd('\r')
                lineBuffer.clear()
                processLine(remaining, newlyParsedWhispers)
            }
            return newlyParsedWhispers
        }

        private fun processLine(rawLine: String, outWhispers: MutableList<String>) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (inProfile) profileBuffer.append('\n')
                return
            }
            when {
                line.startsWith("[[profile]]", ignoreCase = true) -> {
                    inProfile = true
                    val tail = line.substringAfter("]]").trim()
                    if (tail.isNotEmpty()) profileBuffer.append(tail).append('\n')
                }
                inProfile -> {
                    profileBuffer.append(rawLine).append('\n')
                }
                line.startsWith("[[whisper]]", ignoreCase = true) -> {
                    val body = line.substringAfter("]]").trim()
                    if (body.isNotEmpty() && whispers.size < MAX_WHISPERS) {
                        whispers.add(body)
                        outWhispers.add(body)
                    }
                }
                line.startsWith("[[reunion_sub]]", ignoreCase = true) -> {
                    val body = line.substringAfter("]]").trim()
                    if (body.isNotEmpty()) {
                        reunionSubtitle = body.take(MAX_REUNION_SUBTITLE_CHARS)
                    }
                }
                line.startsWith("[[greeting]]", ignoreCase = true) -> {
                    val body = line.substringAfter("]]").trim()
                    if (body.isNotEmpty()) {
                        reunionGreeting = body
                    }
                }
            }
        }

        fun buildResult(sampledIds: List<String>): ParsedAwakeningOutput {
            val profile = parseProfile(profileBuffer.toString(), sampledIds)
            val safeSubtitle = reunionSubtitle.ifBlank { DEFAULT_REUNION_SUBTITLE }
                .take(MAX_REUNION_SUBTITLE_CHARS)
            return ParsedAwakeningOutput(
                whispers = whispers.toList(),
                reunionSubtitle = safeSubtitle,
                reunionGreeting = reunionGreeting.trim(),
                profile = profile,
            )
        }

        private fun parseProfile(raw: String, sampledIds: List<String>): AwakeningProfileData? {
            if (raw.isBlank()) return null
            val cleaned = raw
                .replace(Regex("```(?:json|JSON)?"), "")
                .replace("```", "")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val root = runCatching {
                json.parseToJsonElement(cleaned.substring(start, end + 1)) as? JsonObject
            }.getOrNull() ?: return null

            val name = root["name"].asCleanString()
            val userName = root["userName"].asCleanString()
            val persona = root["persona"].asCleanString()
            val samples = (root["styleSamples"] as? JsonArray)
                ?.mapNotNull { it.asCleanString() }
                ?.distinct()
                ?.take(MAX_STYLE_SAMPLES)
                .orEmpty()

            val patchJson = buildMemoryPatchJson(root, sampledIds)
            return AwakeningProfileData(
                name = name,
                userName = userName,
                persona = persona,
                styleSamples = samples,
                memoryPatchJson = patchJson,
            )
        }

        private fun buildMemoryPatchJson(root: JsonObject, sampledIds: List<String>): String? {
            val sourceId = sampledIds.lastOrNull()
            val facts = normalizeMemoryArray(root["facts"], requireKey = true, sourceId = sourceId)
            val events = normalizeMemoryArray(root["events"], requireKey = false, sourceId = sourceId)
            val prefs = normalizeMemoryArray(root["prefs"], requireKey = false, sourceId = sourceId)
            val boundaries = normalizeMemoryArray(root["boundaries"], requireKey = false, sourceId = sourceId)
            val relationObj = (root["relation"] as? JsonObject)?.takeIf { obj ->
                listOf("stage", "nicknameByMe", "nickname_by_me", "nicknameForMe", "nickname_for_me", "mood")
                    .any { key -> obj[key].asCleanString() != null }
            }
            if (facts.isEmpty() && events.isEmpty() && prefs.isEmpty() && boundaries.isEmpty() && relationObj == null) {
                return null
            }
            val patch = buildJsonObject {
                put("facts", facts)
                put("events", events)
                put("prefs", prefs)
                put("boundaries", boundaries)
                if (relationObj != null) {
                    put(
                        "relation",
                        buildJsonObject {
                            (relationObj["stage"].asCleanString())?.let { put("stage", it) }
                            (relationObj["nicknameByMe"].asCleanString() ?: relationObj["nickname_by_me"].asCleanString())
                                ?.let { put("nicknameByMe", it) }
                            (relationObj["nicknameForMe"].asCleanString() ?: relationObj["nickname_for_me"].asCleanString())
                                ?.let { put("nicknameForMe", it) }
                            (relationObj["mood"].asCleanString())?.let { put("mood", it) }
                        },
                    )
                }
            }
            return patch.toString()
        }

        private fun normalizeMemoryArray(
            element: JsonElement?,
            requireKey: Boolean,
            sourceId: String?,
        ): JsonArray {
            val arr = element as? JsonArray ?: return JsonArray(emptyList())
            return buildJsonArray {
                for (item in arr) {
                    when (item) {
                        is JsonPrimitive -> {
                            val v = item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                            add(
                                buildJsonObject {
                                    if (requireKey) put("k", v.take(24))
                                    put("v", v)
                                    put("conf", 0.85)
                                    if (sourceId != null) {
                                        put("sourceMessageIds", buildJsonArray { add(JsonPrimitive(sourceId)) })
                                    }
                                },
                            )
                        }
                        is JsonObject -> {
                            val v = (item["v"] ?: item["value"]).asCleanString() ?: continue
                            val k = (item["k"] ?: item["key"]).asCleanString()
                            add(
                                buildJsonObject {
                                    if (requireKey) put("k", k ?: v.take(24))
                                    put("v", v)
                                    put("conf", 0.85)
                                    if (sourceId != null) {
                                        put("sourceMessageIds", buildJsonArray { add(JsonPrimitive(sourceId)) })
                                    }
                                },
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }

        private fun JsonElement?.asCleanString(): String? =
            (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("null", ignoreCase = true)
            }
    }
}
