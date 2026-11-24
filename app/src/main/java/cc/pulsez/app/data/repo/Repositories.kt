package cc.pulsez.app.data.repo

import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.data.db.MessageEntity
import cc.pulsez.app.data.db.MessageWithSegmentOrdinal
import cc.pulsez.app.data.db.PulsezDatabase
import cc.pulsez.app.data.db.SegmentEntity
import cc.pulsez.app.data.db.ThreadEntity
import cc.pulsez.app.data.db.ThreadKind
import cc.pulsez.app.data.db.ThreadMemberEntity
import cc.pulsez.app.util.Ids
import androidx.room.withTransaction

/**
 * 角色仓库。字段语义见 。
 */
class CharacterRepository(private val db: PulsezDatabase) {

    suspend fun list(): List<CharacterEntity> = db.characterDao().all()

    suspend fun get(id: String): CharacterEntity? = db.characterDao().byId(id)

    suspend fun save(character: CharacterEntity) = db.characterDao().upsert(character)

    suspend fun delete(id: String) = db.characterDao().softDelete(id, System.currentTimeMillis())

    /**
     * 新建角色并顺手建一条 thread 与一个空段，保证"开始聊天"永远有落点。
     *
     * 注意顺序：**必须先落角色再落 thread**，thread 有指向 character 的外键。
     * 整个动作放在一个事务里，避免出现"角色建了但没有会话"的半成品。
     */
    suspend fun createWithThread(
        name: String,
        persona: String,
        greeting: String? = null,
        styleSamples: List<String> = emptyList(),
        sourceType: String = "MANUAL",
        sourceRef: String? = null,
        rawImported: String? = null,
        systemPromptOverride: String? = null,
    ): Pair<CharacterEntity, ThreadEntity> = db.withTransaction {
        val now = System.currentTimeMillis()
        val character = CharacterEntity(
            id = Ids.new(),
            name = name.ifBlank { "未命名" },
            avatarUri = null,
            persona = persona,
            greeting = greeting,
            styleSamples = styleSamples.takeIf { it.isNotEmpty() }?.let { samples ->
                samples.joinToString("\u0001")
            },
            userName = null,
            systemPromptOverride = systemPromptOverride,
            protocolEnabled = true,
            sourceType = sourceType,
            sourceRef = sourceRef,
            rawImported = rawImported,
            emoAssetDir = null,
            createdAt = now,
            updatedAt = now,
        )
        // 1) 先落角色（外键的父行）
        db.characterDao().upsert(character)
        // 2) 再落会话与首个空段
        val thread = ChatRepository(db).ensureThread(character.id, now)
        character to thread
    }
}

/**
 * 会话与消息仓库。
 *
 * 一个角色默认只有一条 thread；"重新开始"会新建 thread 并保留旧的（§thread）。
 */
class ChatRepository(private val db: PulsezDatabase) {

    suspend fun ensureThread(characterId: String, now: Long = System.currentTimeMillis()): ThreadEntity =
        db.withTransaction {
            db.threadDao().latestForCharacter(characterId)?.let { return@withTransaction it }
            val threadId = Ids.new()
            val segmentId = Ids.new()
            val thread = ThreadEntity(
                id = threadId,
                characterId = characterId,
                title = null,
                createdAt = now,
                lastMessageAt = now,
                activeSegmentId = segmentId,
                segmentOrdinal = 0,
                totalMessages = 0,
                totalTokens = 0,
                archived = false,
                kind = ThreadKind.SINGLE,
            )
            db.threadDao().upsert(thread)
            db.segmentDao().upsert(
                SegmentEntity(
                    id = segmentId,
                    threadId = threadId,
                    ordinal = 0,
                    summary = null,
                    styleNotes = null,
                    firstMessageAt = now,
                    lastMessageAt = now,
                    tokenSum = 0,
                    state = "OPEN",
                    batonReason = null,
                    createdAt = now,
                ),
            )
            thread
        }

    /**
     * 建一个群聊 thread。
     *
     * 一个事务内写：`thread`（kind=GROUP、characterId=null、title=群名）+ 首个空段 +
     * `thread_member`（按传入顺序写 ordinal，0 起）。成员上限 4、下限 2；
     * 角色必须已存在——`thread_member` 契约里没有外键，悬空成员会污染群聊，因此在写库前挡掉。
     *
     * @param characterIds 成员角色 id，顺序即发言顺序（会去重，去重后仍需 2–4 人）
     * @param title 群名；null/空白表示用默认标题（UI 显示「你们几个」）
     */
    suspend fun createGroupThread(characterIds: List<String>, title: String? = null): ThreadEntity =
        db.withTransaction {
            val memberIds = characterIds.distinct()
            require(memberIds.size in MIN_GROUP_MEMBERS..MAX_GROUP_MEMBERS) {
                "群聊需要 $MIN_GROUP_MEMBERS–$MAX_GROUP_MEMBERS 位成员，当前 ${memberIds.size} 位"
            }
            memberIds.forEach { characterId ->
                requireNotNull(db.characterDao().byId(characterId)) { "角色不存在：$characterId" }
            }
            val now = System.currentTimeMillis()
            val threadId = Ids.new()
            val segmentId = Ids.new()
            val thread = ThreadEntity(
                id = threadId,
                characterId = null,
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
                lastMessageAt = now,
                activeSegmentId = segmentId,
                segmentOrdinal = 0,
                totalMessages = 0,
                totalTokens = 0,
                archived = false,
                kind = ThreadKind.GROUP,
            )
            db.threadDao().upsert(thread)
            db.segmentDao().upsert(
                SegmentEntity(
                    id = segmentId,
                    threadId = threadId,
                    ordinal = 0,
                    summary = null,
                    styleNotes = null,
                    firstMessageAt = now,
                    lastMessageAt = now,
                    tokenSum = 0,
                    state = "OPEN",
                    batonReason = null,
                    createdAt = now,
                ),
            )
            db.threadMemberDao().insertAll(
                memberIds.mapIndexed { ordinal, characterId ->
                    ThreadMemberEntity(
                        threadId = threadId,
                        characterId = characterId,
                        ordinal = ordinal,
                        joinedAt = now,
                    )
                },
            )
            thread
        }

    /** 群成员，按发言顺序（ordinal 升序）。单聊 thread 返回空列表。 */
    suspend fun membersOf(threadId: String): List<ThreadMemberEntity> =
        db.threadMemberDao().membersOf(threadId)

    /**
     * 群聊共享历史（步骤 3）：所有成员 + 用户消息，时间正序。
     *
     * 排序稳定性：`createdAt` 相同的消息再按「用户消息优先 → 成员 ordinal → id」兜底。
     * 一轮群里多个角色可能落在同一毫秒（尤其离线/假引擎），若只按时间排，UI 与下一位成员的
     * prompt 里发言顺序会和成员列表不一致（验收第 2 条要求顺序与成员列表一致）。
     */
    suspend fun groupHistory(threadId: String): List<MessageEntity> {
        val messages = db.messageDao().allForThread(threadId)
        if (messages.size < 2) return messages
        val ordinals = db.threadMemberDao().membersOf(threadId).associate { it.characterId to it.ordinal }
        return messages.sortedWith(
            compareBy<MessageEntity> { it.createdAt }
                .thenBy { message ->
                    message.speakerCharacterId?.let { ordinals[it] ?: Int.MAX_VALUE } ?: -1
                }
                .thenBy { it.id },
        )
    }

    /** 某角色在某 thread 说过的消息（按发言人过滤），时间正序。 */
    suspend fun historyForSpeaker(threadId: String, speakerCharacterId: String): List<MessageEntity> =
        db.messageDao().bySpeaker(threadId, speakerCharacterId)

    suspend fun tail(threadId: String, limit: Int = 60): List<MessageEntity> =
        db.messageDao().tail(threadId, limit).reversed()

    /**
     * 聊天页「查看更早」的游标翻页（v0.4）。
     *
     * 游标是**已加载的最早一条**的 `(createdAt, id)`；DAO 用
     * `(createdAt < c OR (createdAt = c AND id < id))` 取更早的一页，同毫秒消息由主键兜底，
     * 翻页不重不漏（`PulsezDatabase.kt` 的 `MessageDao.before` 已加）。
     *
     * @return 时间正序（与 [tail] 一致，方便直接前插到消息列表头部）
     */
    suspend fun tailBefore(
        threadId: String,
        beforeCreatedAt: Long,
        beforeId: String,
        limit: Int = 60,
    ): List<MessageEntity> =
        db.messageDao().before(threadId, beforeCreatedAt, beforeId, limit).reversed()

    /**
     * A frozen member rank used only for group UI keyset paging. It is captured when a group screen opens;
     * reopening reads the current ordinal values and starts from the latest tail again.
     */
    data class GroupUiMemberOrder(val characterId: String, val ordinal: Int)

    data class GroupUiHistoryPage(
        val messages: List<MessageEntity>,
        /** The last user row before [messages], when the page starts in the middle of a turn. */
        val precedingUser: MessageEntity? = null,
        /** True when an assistant in this turn is already before the page boundary. */
        val precedingAssistantSeen: Boolean = false,
        val hasOlder: Boolean,
    )

    data class GroupLedgerFallbackSummary(
        val lastSpeakers: List<String>,
        val silentTurns: Map<String, Int>,
    )

    private data class GroupUiCursor(val createdAt: Long, val memberOrdinal: Int, val id: String)

    private data class GroupUiMemberArgs(
        val id0: String?, val ordinal0: Int?,
        val id1: String?, val ordinal1: Int?,
        val id2: String?, val ordinal2: Int?,
        val id3: String?, val ordinal3: Int?,
    )

    private fun groupUiMemberArgs(orders: List<GroupUiMemberOrder>): GroupUiMemberArgs {
        // 群聊规则限定最多四名成员（GroupRules.MAX_MEMBERS）；缺位用 null，不会与 SQL NULL speaker 匹配。
        val frozen = orders.sortedBy { it.ordinal }.take(4)
        return GroupUiMemberArgs(
            id0 = frozen.getOrNull(0)?.characterId, ordinal0 = frozen.getOrNull(0)?.ordinal,
            id1 = frozen.getOrNull(1)?.characterId, ordinal1 = frozen.getOrNull(1)?.ordinal,
            id2 = frozen.getOrNull(2)?.characterId, ordinal2 = frozen.getOrNull(2)?.ordinal,
            id3 = frozen.getOrNull(3)?.characterId, ordinal3 = frozen.getOrNull(3)?.ordinal,
        )
    }

    private fun groupUiCursor(message: MessageEntity, orders: List<GroupUiMemberOrder>): GroupUiCursor =
        GroupUiCursor(
            createdAt = message.createdAt,
            memberOrdinal = if (message.speakerCharacterId == null) {
                -1
            } else {
                orders.firstOrNull { it.characterId == message.speakerCharacterId }?.ordinal ?: Int.MAX_VALUE
            },
            id = message.id,
        )

    private suspend fun groupUiBeforeRows(
        threadId: String,
        cursor: GroupUiCursor,
        orders: List<GroupUiMemberOrder>,
        limit: Int,
    ): List<MessageEntity> {
        val args = groupUiMemberArgs(orders)
        return db.messageDao().groupUiBefore(
            threadId = threadId,
            beforeCreatedAt = cursor.createdAt,
            beforeMemberOrdinal = cursor.memberOrdinal,
            beforeId = cursor.id,
            memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
            memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
            memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
            memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
            limit = limit,
        ).reversed()
    }

    /** Latest UI window only; full group history remains available to the engine and ledger fallback. */
    suspend fun groupUiLatestPage(
        threadId: String,
        memberOrder: List<GroupUiMemberOrder>,
        limit: Int = 80,
    ): GroupUiHistoryPage {
        val args = groupUiMemberArgs(memberOrder)
        val rows = db.messageDao().groupUiTail(
            threadId = threadId,
            memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
            memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
            memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
            memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
            limit = limit,
        ).reversed()
        return groupUiPageWithPrefix(threadId, rows, memberOrder)
    }

    /** Older UI window strictly before the oldest loaded row; inserted rows are kept separate by id upstream. */
    suspend fun groupUiBeforePage(
        threadId: String,
        before: MessageEntity,
        memberOrder: List<GroupUiMemberOrder>,
        limit: Int = 60,
    ): GroupUiHistoryPage {
        val rows = groupUiBeforeRows(threadId, groupUiCursor(before, memberOrder), memberOrder, limit)
        return groupUiPageWithPrefix(threadId, rows, memberOrder)
    }

    /**
     * Exact history-derived ledger fallback, expressed as SQL aggregates rather than materialized history.
     * The aggregate query returns one row per current member; the cooldown query returns at most two IDs.
     */
    suspend fun groupLedgerFallback(
        threadId: String,
        memberOrder: List<GroupUiMemberOrder>,
    ): GroupLedgerFallbackSummary {
        val args = groupUiMemberArgs(memberOrder)
        val memberIds = memberOrder.sortedBy { it.ordinal }.take(4).map { it.characterId }
        val silent = db.messageDao().groupUiFallbackSilentTurns(
            threadId = threadId,
            memberIds = memberIds,
            memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
            memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
            memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
            memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
        ).associate { it.characterId to it.silentTurns }
        val lastSpeakers = db.messageDao().groupUiFallbackLastSpeakers(
            threadId = threadId,
            memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
            memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
            memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
            memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
            limit = 2,
        )
        return GroupLedgerFallbackSummary(lastSpeakers = lastSpeakers, silentTurns = silent)
    }

    /** Recent assistant text is the only history content needed for prompt de-duplication on screen open. */
    suspend fun groupUiRecentAssistantReplies(
        threadId: String,
        memberOrder: List<GroupUiMemberOrder>,
        limit: Int = 3,
    ): List<String> {
        val args = groupUiMemberArgs(memberOrder)
        return db.messageDao().groupUiRecentAssistantReplies(
            threadId = threadId,
            memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
            memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
            memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
            memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
            limit = limit,
        ).reversed()
    }

    private suspend fun groupUiPageWithPrefix(
        threadId: String,
        rows: List<MessageEntity>,
        memberOrder: List<GroupUiMemberOrder>,
    ): GroupUiHistoryPage {
        val first = rows.firstOrNull() ?: return GroupUiHistoryPage(messages = emptyList(), hasOlder = false)
        if (first.role != "assistant") {
            val hasOlder = groupUiBeforeRows(threadId, groupUiCursor(first, memberOrder), memberOrder, limit = 1).isNotEmpty()
            return GroupUiHistoryPage(messages = rows, hasOlder = hasOlder)
        }

        val boundary = groupUiCursor(first, memberOrder)
        val args = groupUiMemberArgs(memberOrder)
        val precedingUser = db.messageDao().groupUiPreviousUser(
            threadId = threadId,
            beforeCreatedAt = boundary.createdAt,
            beforeMemberOrdinal = boundary.memberOrdinal,
            beforeId = boundary.id,
            memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
            memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
            memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
            memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
        )
        val countBefore = if (precedingUser == null) {
            db.messageDao().groupUiAssistantCountBefore(
                threadId = threadId,
                beforeCreatedAt = boundary.createdAt,
                beforeMemberOrdinal = boundary.memberOrdinal,
                beforeId = boundary.id,
                memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
                memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
                memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
                memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
            )
        } else {
            val userCursor = groupUiCursor(precedingUser, memberOrder)
            db.messageDao().groupUiAssistantCountSinceUser(
                threadId = threadId,
                afterCreatedAt = userCursor.createdAt,
                afterMemberOrdinal = userCursor.memberOrdinal,
                afterId = userCursor.id,
                beforeCreatedAt = boundary.createdAt,
                beforeMemberOrdinal = boundary.memberOrdinal,
                beforeId = boundary.id,
                memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
                memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
                memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
                memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
            )
        }

        // A second response belongs to the MAIN row immediately before this page. Include that one row so
        // `toMessageRows` keeps the same MAIN key when pages join. For later reactions, the compact prefix
        // state is enough; no unbounded history scan is needed even if a malformed turn has many assistants.
        val leadingMain = if (countBefore == 1) {
            if (precedingUser == null) {
                db.messageDao().groupUiPreviousAssistantSinceUser(
                    threadId = threadId,
                    afterCreatedAt = Long.MIN_VALUE,
                    afterMemberOrdinal = Int.MIN_VALUE,
                    afterId = "",
                    beforeCreatedAt = boundary.createdAt,
                    beforeMemberOrdinal = boundary.memberOrdinal,
                    beforeId = boundary.id,
                    memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
                    memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
                    memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
                    memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
                )
            } else {
                val userCursor = groupUiCursor(precedingUser, memberOrder)
                db.messageDao().groupUiPreviousAssistantSinceUser(
                    threadId = threadId,
                    afterCreatedAt = userCursor.createdAt,
                    afterMemberOrdinal = userCursor.memberOrdinal,
                    afterId = userCursor.id,
                    beforeCreatedAt = boundary.createdAt,
                    beforeMemberOrdinal = boundary.memberOrdinal,
                    beforeId = boundary.id,
                    memberId0 = args.id0, memberOrdinal0 = args.ordinal0,
                    memberId1 = args.id1, memberOrdinal1 = args.ordinal1,
                    memberId2 = args.id2, memberOrdinal2 = args.ordinal2,
                    memberId3 = args.id3, memberOrdinal3 = args.ordinal3,
                )
            }
        } else {
            null
        }
        val messages = leadingMain?.let { listOf(it) + rows } ?: rows
        val firstCursor = groupUiCursor(messages.first(), memberOrder)
        val hasOlder = groupUiBeforeRows(threadId, firstCursor, memberOrder, limit = 1).isNotEmpty()
        return GroupUiHistoryPage(
            messages = messages,
            precedingUser = precedingUser,
            precedingAssistantSeen = countBefore > 1,
            hasOlder = hasOlder,
        )
    }

    /**
     * Chat-only ordering keeps each segment contiguous; imports create one sealed segment per source
     * in source-start order (ImportRepository.commitBatchGroup), and regular OPEN/SEALED history keeps
     * its stored ordinal. Backup export/restore already carries segment ordinals.
     */
    suspend fun chatTailInSegmentOrder(threadId: String, limit: Int = 60): List<MessageWithSegmentOrdinal> =
        db.messageDao().chatTailInSegmentOrder(threadId, limit).reversed()

    /** Cursor pagination for [chatTailInSegmentOrder], ordered by (segment ordinal, createdAt, id). */
    suspend fun chatTailBeforeInSegmentOrder(
        threadId: String,
        beforeSegmentOrdinal: Int,
        beforeCreatedAt: Long,
        beforeId: String,
        limit: Int = 60,
    ): List<MessageWithSegmentOrdinal> = db.messageDao()
        .chatBeforeInSegmentOrder(threadId, beforeSegmentOrdinal, beforeCreatedAt, beforeId, limit)
        .reversed()

    suspend fun append(
        threadId: String,
        segmentId: String,
        role: String,
        text: String,
        tokens: Int,
        status: String = "OK",
        emotion: String? = null,
        usageIn: Int? = null,
        usageOut: Int? = null,
        createdAt: Long = System.currentTimeMillis(),
        // v2：群聊里这条回复是哪个角色说的；用户消息与单聊保持 null。
        speakerCharacterId: String? = null,
        /** Only the live chat input path opts in; imports and assistant/group writes keep the default. */
        recordSingleChatActivity: Boolean = false,
    ): MessageEntity {
        val message = MessageEntity(
            id = Ids.new(),
            threadId = threadId,
            segmentId = segmentId,
            role = role,
            content = text,
            tokens = tokens,
            emotion = emotion,
            status = status,
            draft = null,
            usageIn = usageIn,
            usageOut = usageOut,
            previousContent = null,
            createdAt = createdAt,
            deletedAt = null,
            speakerCharacterId = speakerCharacterId,
        )
        return db.withTransaction {
            db.messageDao().upsert(message)
            db.threadDao().touch(threadId, createdAt)
            // A send that fails after this transaction still counts: the user's message is already durable.
            // The separate marker is explicit so imported user-role history never becomes recent activity.
            // 只有消息已成功写入后，才记录单聊活跃时间。
            if (recordSingleChatActivity && role == "user" && text.isNotBlank()) {
                db.threadDao().recordSingleChatActivity(threadId, System.currentTimeMillis())
            }
            message
        }
    }

    /**
     * Record a live single-chat activity event after an image-only user message is durable and its
     * attachments have been bound. Text-bearing user messages record the event in [append].
     */
    suspend fun recordActivityForImageMessage(
        threadId: String,
        messageId: String,
        at: Long = System.currentTimeMillis(),
    ): Boolean = db.withTransaction {
        val message = db.messageDao().byId(messageId) ?: return@withTransaction false
        if (message.threadId != threadId || message.role != "user" || message.deletedAt != null || message.content.isNotBlank()) {
            return@withTransaction false
        }
        if (db.attachmentDao().countByMessage(messageId) == 0) return@withTransaction false
        db.threadDao().recordSingleChatActivity(threadId, at)
        true
    }

    companion object {
        /** 群聊成员数量上下限（选 2–4 人；§3 步骤 2 硬上限 4）。*/
        const val MIN_GROUP_MEMBERS: Int = 2
        const val MAX_GROUP_MEMBERS: Int = 4
    }
}
