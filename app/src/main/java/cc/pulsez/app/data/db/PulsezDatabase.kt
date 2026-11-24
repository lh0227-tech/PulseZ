package cc.pulsez.app.data.db

import android.content.Context
import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ---------------------------------------------------------------- 实体
// 字段语义见 。这里先落全表，DAO 随里程碑补齐。

@Entity(tableName = "character")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatarUri: String?,
    val persona: String,
    val greeting: String?,
    val styleSamples: String?,
    val userName: String?,
    val systemPromptOverride: String?,
    val protocolEnabled: Boolean = true,
    val sourceType: String,
    val sourceRef: String?,
    val rawImported: String?,
    val emoAssetDir: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** Role row with its latest live single-chat activity event. */
data class CharacterWithChatActivity(
    @Embedded val character: CharacterEntity,
    val lastSingleChatActivityAt: Long?,
)

@Entity(
    tableName = "thread",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("characterId"), Index("lastMessageAt")],
)
data class ThreadEntity(
    @PrimaryKey val id: String,
    // v2 起可空：群聊没有单一主角。单聊仍写角色 id，并保留外键级联。
    val characterId: String?,
    val title: String?,
    val createdAt: Long,
    val lastMessageAt: Long,
    val activeSegmentId: String?,
    val segmentOrdinal: Int,
    val totalMessages: Int,
    val totalTokens: Long,
    val lastExtractAt: Long = 0L,
    val archived: Boolean = false,
    // v2 新增；DB 层默认 'SINGLE'，保证 v1 旧行迁移后仍是单聊。
    // defaultValue 必须与 Migration(1, 2) 的 `DEFAULT 'SINGLE'` 一致，否则 Room 迁移校验失败。
    @ColumnInfo(defaultValue = "SINGLE")
    val kind: String = ThreadKind.SINGLE,
    /**
     * Updated only for a successfully stored live user message in this non-archived single chat.
     * 依据 方案 B；事件写入边界按本轮任务 。
     */
    val lastSingleChatActivityAt: Long? = null,
)

/** `thread.kind` 取值。*/
object ThreadKind {
    const val SINGLE: String = "SINGLE"
    const val GROUP: String = "GROUP"
}

/**
 * 群成员（thread_member）。
 *
 * 复合主键 `(threadId, characterId)` 保证同一角色在同群只出现一次；
 * `ordinal` 是发言顺序（0 起 步骤 3 按它串行生成）。
 *
 * 注意：契约没有给本表声明外键，故这里不加 ForeignKey，
 * 由仓储层 `createGroupThread` 在写库前校验角色存在，避免悬空成员。
 */
@Entity(
    tableName = "thread_member",
    primaryKeys = ["threadId", "characterId"],
    indices = [Index("characterId")],
)
data class ThreadMemberEntity(
    val threadId: String,
    val characterId: String,
    val ordinal: Int,
    val joinedAt: Long,
)

@Entity(
    tableName = "message",
    indices = [
        Index("threadId", "createdAt"),
        Index("segmentId"),
        Index("status"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val segmentId: String,
    val role: String,
    val content: String,
    val tokens: Int,
    val emotion: String?,
    val status: String,
    val draft: String?,
    val usageIn: Int?,
    val usageOut: Int?,
    val previousContent: String?,
    val createdAt: Long,
    val deletedAt: Long? = null,
    // v2 新增：群聊里这条是哪个角色说的；用户消息与单聊为 null。
    val speakerCharacterId: String? = null,
)

/** Chat-list projection; segment ordinal keeps source sessions contiguous without changing stored timestamps. */
data class MessageWithSegmentOrdinal(
    @Embedded val message: MessageEntity,
    val segmentOrdinal: Int,
)

/** Bounded database projection used only when a group's persisted ledger is absent or unreadable. */
data class GroupLedgerSilentTurns(
    val characterId: String,
    val silentTurns: Int,
)

@Entity(
    tableName = "segment",
    indices = [Index("threadId", "ordinal")],
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val ordinal: Int,
    val summary: String?,
    val styleNotes: String?,
    val firstMessageAt: Long,
    val lastMessageAt: Long,
    val tokenSum: Int,
    val state: String,
    val batonReason: String?,
    val createdAt: Long,
)

/** A durable import attempt. Successful chat writes update the owning group and source rows atomically. */
@Entity(tableName = "import_batch")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val zipName: String,
    val createdAt: Long,
    val status: String,
    val postProcessStatus: String,
)

/** One target TA inside a batch. Assignment rows survive process death so failed groups can be retried. */
@Entity(tableName = "import_batch_group", indices = [Index("batchId"), Index("targetCharacterId"), Index("targetThreadId")])
data class ImportBatchGroupEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val targetCharacterId: String?,
    val targetThreadId: String?,
    val isNewCharacter: Boolean,
    val name: String,
    val persona: String?,
    val status: String,
    val memoryFingerprint: String?,
    val beforeLastMessageAt: Long?,
    val beforeLastExtractAt: Long?,
    val beforeCharacterUpdatedAt: Long?,
    val importLastMessageAt: Long?,
    val importMessageCount: Int,
    val errorCode: String?,
)

/** Preview and user assignments are durable; null groupId means the user explicitly skipped that source. */
@Entity(tableName = "import_batch_window", primaryKeys = ["batchId", "sourceSessionId"], indices = [Index("groupId")])
data class ImportBatchWindowEntity(
    val batchId: String,
    val sourceSessionId: String,
    val entryName: String,
    val title: String?,
    val sourceStartedAt: Long?,
    val messageCount: Int,
    val damageCode: String?,
    val groupId: String?,
    val status: String,
    val errorCode: String?,
)

/** Durable source boundary and de-duplication key: the same source ID is unique per target TA. */
@Entity(
    tableName = "import_source_session",
    indices = [
        Index(value = ["targetCharacterId", "sourceSessionId"], unique = true),
        Index("batchId"),
        Index("targetThreadId"),
        Index("segmentId", unique = true),
    ],
)
data class ImportSourceSessionEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val targetCharacterId: String,
    val targetThreadId: String,
    val sourceSessionId: String,
    val title: String?,
    val sourceStartedAt: Long?,
    val messageCount: Int,
    val segmentId: String,
    val indexStatus: String,
)

@Entity(
    tableName = "memory_item",
    indices = [
        Index("threadId", "kind", "status"),
        Index("threadId", "lastSeenAt"),
    ],
)
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val kind: String,
    val key: String?,
    val value: String,
    val confidence: Float,
    val importance: Int,
    val happenedAt: Long?,
    val dueAt: Long?,
    val validFrom: Long,
    val validTo: Long?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val sourceSegmentId: String?,
    val sourceMessageIds: String?,
    val status: String,
    val supersededBy: String?,
    // v2 新增：群聊里按角色隔离记忆；单聊为 null（沿用 thread 维度）。
    val characterId: String? = null,
)

@Entity(
    tableName = "thread_state",
    primaryKeys = ["threadId"],
)
data class ThreadStateEntity(
    val threadId: String,
    val relationStage: String?,
    val nicknameByMe: String?,
    val nicknameForMe: String?,
    val mood: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "chunk",
    indices = [
        Index("threadId", "createdAt"),
        Index("threadId", "lastMessageAt"),
        Index("threadId", "model"),
    ],
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val segmentId: String,
    val text: String,
    val firstMessageAt: Long,
    val lastMessageAt: Long,
    val messageIds: String,
    val dim: Int,
    val vec: ByteArray,
    val scale: Float,
    val model: String,
    val createdAt: Long,
    val deletedAt: Long? = null,
    /** SHA-256 of the non-secret embedding endpoint identity and model; empty means legacy/unknown. */
    @ColumnInfo(defaultValue = "''")
    val embeddingConfigFingerprint: String = "",
)

@Entity(
    tableName = "chunk_term",
    primaryKeys = ["term", "chunkId"],
    indices = [Index("chunkId")],
)
data class ChunkTermEntity(
    val term: String,
    val chunkId: String,
    val threadId: String,
    val weight: Int,
)

/**
 * 附件（v0.5.0 新增，DB v3）。
 *
 * 契约：`（字段与索引逐字对齐）、`（附件段落
 * 由文档代理在 v0.5 同步）。设计要点：
 * - `messageId` 可空：草稿（DRAFT）还没有 message 行；发送成功后才由
 *   `AttachmentRepository.bindToMessage` 绑定（旧稿把 messageId 定成非空，草稿无法落库）。
 * - `deletedAt` 软删：查询一律过滤 `deletedAt IS NULL`。删除传播的仓储层入口已备
 *   （`AttachmentRepository.softDeleteForMessage`），但 **message 软删接线时必须与附件软删
 *   放同一事务**——当前尚未接线，「同事务」尚未成立，不能当作已实现
 *   （` 规则 6、`「软删」行；依据：`）。
 * - 刻意**不加** `messageId → message.id` 外键：草稿期 messageId 为 null 且 message 行尚不存在，
 *   FK 只保护硬删而本版没有 message 硬删；软删传播 + 启动清理（`PulsezApp`）已覆盖
 *   （依据：` 「若不加 FK，至少加 sweeper」）。
 *   ** 补充**：启动孤儿清理的 keep 集合含 DRAFT/FAILED 活行，
 *   单靠它回收不掉「删草稿/绑定失败」留下的行，所以另有 `staleUnboundDrafts` +
 *   `AttachmentRepository.sweepStaleDrafts`（24h TTL）先把它们软删，孤儿清理才看得到文件。
 * - `remoteFileId` / `expiresAt`：v0.6 文件能力用，v0.5 恒 null。
 * - `localPath` 是 **app 私有目录相对路径**（`attachments/<id>.<ext>`），绝不导出；
 *   文件名只用 id，不用用户原名（防路径穿越，见 `AttachmentStore`）。
 */
@Entity(tableName = "attachment", indices = [Index("messageId"), Index("status"), Index("ownerThreadId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    /** 草稿期为 null；发送后绑定到 [MessageEntity.id]。 */
    val messageId: String?,
    /** [AttachmentKind] 之一；v0.5.0 只有 IMAGE。*/
    val kind: String,
    val mime: String,
    /** `filesDir` 下的相对路径，如 `attachments/<id>.jpg`；导出时不含（隐私）。 */
    val localPath: String,
    /** 用户可见的原始文件名（仅展示用，不参与落盘路径）。 */
    val fileName: String?,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    /** [AttachmentStatus] 之一：DRAFT | READY | SENDING | SENT | FAILED。 */
    val status: String,
    /** 失败原因码（可读错误，UI 换人话）；成功/草稿为 null。 */
    val errorKind: String?,
    /** 已尝试发送/上传次数（v0.6 文件重试用；v0.5 由图片发送链路维护）。*/
    val attempts: Int = 0,
    /** 服务商文件句柄（v0.6）；v0.5 恒 null，且**不导出**。*/
    val remoteFileId: String?,
    /** 服务商文件过期时间（v0.6）；v0.5 恒 null。*/
    val expiresAt: Long?,
    /** 软删时间；null = 未删。 */
    val deletedAt: Long?,
    val createdAt: Long,
    /** Thread owner for new unsent drafts; legacy unowned drafts stay null rather than guessing ownership. */
    val ownerThreadId: String? = null,
)

/** Local tombstones prevent a late importer or chat worker from recreating a permanently deleted role id. */
@Entity(tableName = "deleted_character")
data class DeletedCharacterEntity(
    @PrimaryKey val characterId: String,
    val deletedAt: Long,
)

/** Durable retry record for files that could not be removed during role deletion. */
@Entity(tableName = "pending_attachment_delete")
data class PendingAttachmentDeleteEntity(
    @PrimaryKey val attachmentId: String,
    val localPath: String,
    val requestedAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
)

/** `attachment.kind` 取值。v0.5.0 只做识图，故只有 IMAGE。*/
object AttachmentKind {
    const val IMAGE: String = "IMAGE"
}

/** `attachment.status` 取值。*/
object AttachmentStatus {
    const val DRAFT: String = "DRAFT"
    const val READY: String = "READY"
    const val SENDING: String = "SENDING"
    const val SENT: String = "SENT"
    const val FAILED: String = "FAILED"
}

// ---------------------------------------------------------------- DAO

@Dao
interface CharacterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(character: CharacterEntity)

    @Query("SELECT * FROM character WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun all(): List<CharacterEntity>

    /** All visible roles with activity from non-archived single chats only; old rows naturally stay null. */
    @Query(
        "SELECT character.*, MAX(thread.lastSingleChatActivityAt) AS lastSingleChatActivityAt " +
            "FROM character LEFT JOIN thread ON thread.characterId = character.id " +
            "AND thread.kind = 'SINGLE' AND thread.archived = 0 " +
            "WHERE character.deletedAt IS NULL GROUP BY character.id",
    )
    suspend fun allWithChatActivity(): List<CharacterWithChatActivity>

    /** Includes soft-deleted roles so old source conversations remain de-duplicated after hiding a role. */
    @Query("SELECT * FROM character ORDER BY updatedAt DESC")
    suspend fun allIncludingDeleted(): List<CharacterEntity>

    @Query("SELECT * FROM character WHERE id = :id AND deletedAt IS NULL")
    suspend fun byId(id: String): CharacterEntity?

    @Query("UPDATE character SET deletedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    /**
     * 定向更新「最近改动时间」（导入收尾用 第 5 条）。
     *
     * 用定向 `UPDATE` 而不是整行 upsert：导入期间用户可能同时在聊天/改角色，
     * 整行写回会用导入开始时的旧快照覆盖并发写入（不动用户已有数据）。
     */
    @Query("UPDATE character SET updatedAt = :at WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, at: Long)

    @Query("UPDATE character SET updatedAt = :at WHERE id = :id")
    suspend fun setUpdatedAt(id: String, at: Long)

    @Query("DELETE FROM `character` WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DeletedCharacterDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tombstone(value: DeletedCharacterEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_character WHERE characterId = :id)")
    suspend fun contains(id: String): Boolean
}

@Dao
interface ThreadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: ThreadEntity)

    @Query("SELECT * FROM thread WHERE archived = 0 ORDER BY lastMessageAt DESC")
    suspend fun active(): List<ThreadEntity>

    @Query("SELECT * FROM thread WHERE id = :id")
    suspend fun byId(id: String): ThreadEntity?

    @Query("SELECT * FROM thread WHERE characterId = :characterId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForCharacter(characterId: String): ThreadEntity?

    /** Read active single chats in one query; callers choose one per role without creating empty rows. */
    @Query("SELECT * FROM thread WHERE archived = 0 AND kind = 'SINGLE' ORDER BY createdAt DESC, id DESC")
    suspend fun activeSinglesByCreation(): List<ThreadEntity>

    @Query("UPDATE thread SET lastMessageAt = :at, totalMessages = totalMessages + 1 WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    /** Monotonic event time; no message-time inference or rollback when a message is later deleted. */
    @Query(
        "UPDATE thread SET lastSingleChatActivityAt = :at " +
            "WHERE id = :id AND kind = 'SINGLE' AND archived = 0 " +
            "AND (lastSingleChatActivityAt IS NULL OR lastSingleChatActivityAt < :at)",
    )
    suspend fun recordSingleChatActivity(id: String, at: Long)

    @Query("UPDATE thread SET lastExtractAt = :at WHERE id = :id")
    suspend fun markExtracted(id: String, at: Long)

    @Query("UPDATE thread SET activeSegmentId = :segmentId, segmentOrdinal = :ordinal WHERE id = :id")
    suspend fun setActiveSegment(id: String, segmentId: String, ordinal: Int)

    // ---- v0.4 导入落库（第 5 条 / §6）----
    // 一律用定向 UPDATE，不用整行 upsert：导入是长任务、和用户的聊天可能并发，
    // 整行写回会用旧快照覆盖用户刚写入的计数/时间。

    /** 定向累加消息数（导入收尾）。 */
    @Query("UPDATE thread SET totalMessages = totalMessages + :delta WHERE id = :id")
    suspend fun addTotalMessages(id: String, delta: Int)

    /** 只在更晚时前移 `lastMessageAt`：导入的是旧时间，不能把导入期间新聊消息的时间改早。 */
    @Query("UPDATE thread SET lastMessageAt = :at WHERE id = :id AND lastMessageAt < :at")
    suspend fun bumpLastMessageAt(id: String, at: Long)

    /**
     * 只在更晚时推进 `lastExtractAt`（自动抽取隔离）。
     *
     * `ChatEngine.extractIfDue` 用 `after(lastExtractAt)` 取待抽取消息；导入收尾把它推到
     * 「最后一条导入消息时间」，导入的往事就不会被下一次自动抽取当成新对话读走。
     */
    @Query("UPDATE thread SET lastExtractAt = :at WHERE id = :id AND lastExtractAt < :at")
    suspend fun bumpLastExtractAt(id: String, at: Long)

    // ---- v2 多角色----

    /** 按 kind 取未归档会话（SINGLE / GROUP）。 */
    @Query("SELECT * FROM thread WHERE archived = 0 AND kind = :kind ORDER BY lastMessageAt DESC")
    suspend fun activeByKind(kind: String): List<ThreadEntity>

    @Query("SELECT * FROM thread WHERE kind = :kind ORDER BY lastMessageAt DESC")
    suspend fun allByKind(kind: String): List<ThreadEntity>

    @Query("UPDATE thread SET kind = :kind WHERE id = :id")
    suspend fun setKind(id: String, kind: String)

    /** 群名复用 thread.title（title 复用；null 表示用默认「你们几个」）。*/
    @Query("UPDATE thread SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String?)

    @Query("UPDATE thread SET totalMessages = :totalMessages, lastMessageAt = :lastMessageAt, lastExtractAt = :lastExtractAt WHERE id = :id")
    suspend fun restoreAfterImportUndo(id: String, totalMessages: Int, lastMessageAt: Long, lastExtractAt: Long)

    @Query("DELETE FROM thread WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT id FROM thread WHERE characterId = :characterId AND kind = 'SINGLE'")
    suspend fun singleThreadIdsForCharacter(characterId: String): List<String>
}

/** 群成员读写（thread_member）。*/
@Dao
interface ThreadMemberDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<ThreadMemberEntity>)

    /** 按发言顺序（ordinal 升序）返回群成员。 */
    @Query("SELECT * FROM thread_member WHERE threadId = :threadId ORDER BY ordinal ASC")
    suspend fun membersOf(threadId: String): List<ThreadMemberEntity>

    /** 某角色所在的所有会话（群聊；用于"这个角色加入了哪些群"）。 */
    @Query("SELECT * FROM thread_member WHERE characterId = :characterId ORDER BY joinedAt ASC, threadId ASC")
    suspend fun ofCharacter(characterId: String): List<ThreadMemberEntity>

    @Query("DELETE FROM thread_member WHERE threadId = :threadId AND characterId = :characterId")
    suspend fun remove(threadId: String, characterId: String)

    @Query("DELETE FROM thread_member WHERE threadId = :threadId")
    suspend fun removeAllForThread(threadId: String)

    @Query("DELETE FROM thread_member WHERE characterId = :characterId")
    suspend fun removeForCharacter(characterId: String)
}

/** Shared exact-order CTE for the no-persisted-ledger fallback; projects keys only, never message bodies. */
private const val GROUP_UI_PRIMARY_TURNS_CTE = """
WITH ordered AS (
    SELECT id, role, speakerCharacterId, createdAt,
        CASE WHEN speakerCharacterId IS NULL THEN -1
             WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0
             WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1
             WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2
             WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3
             ELSE 2147483647 END AS groupRank
    FROM message
    WHERE threadId = :threadId AND deletedAt IS NULL
), primary_turns AS (
    SELECT a.speakerCharacterId AS speakerId, a.createdAt, a.groupRank, a.id
    FROM ordered a
    WHERE a.role = 'assistant' AND a.speakerCharacterId IS NOT NULL
      AND EXISTS (
          SELECT 1 FROM ordered u
          WHERE u.role = 'user' AND (
              u.createdAt < a.createdAt OR (u.createdAt = a.createdAt AND (
                  u.groupRank < a.groupRank OR (u.groupRank = a.groupRank AND u.id < a.id)
              ))
          )
      )
      AND NOT EXISTS (
          SELECT 1 FROM ordered x
          WHERE x.role = 'assistant' AND x.speakerCharacterId IS NOT NULL AND (
              x.createdAt < a.createdAt OR (x.createdAt = a.createdAt AND (
                  x.groupRank < a.groupRank OR (x.groupRank = a.groupRank AND x.id < a.id)
              ))
          )
          AND NOT EXISTS (
              SELECT 1 FROM ordered y
              WHERE y.role = 'user'
                AND (y.createdAt > x.createdAt OR (y.createdAt = x.createdAt AND (
                    y.groupRank > x.groupRank OR (y.groupRank = x.groupRank AND y.id > x.id)
                )))
                AND (y.createdAt < a.createdAt OR (y.createdAt = a.createdAt AND (
                    y.groupRank < a.groupRank OR (y.groupRank = a.groupRank AND y.id < a.id)
                )))
          )
      )
)
"""

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    // 倒序 + 主键兜底：同一毫秒的多条消息（群聊一轮里有多个角色）也要稳定排序；
    // 否则上一条/下一条翻页会漂移（v0.4）。
    @Query(
        "SELECT * FROM message WHERE threadId = :threadId AND deletedAt IS NULL " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    suspend fun tail(threadId: String, limit: Int): List<MessageEntity>

    /**
     * Chat history follows segment order, then original message time/id within each segment.
     * Missing segment metadata is retained at the oldest end instead of hiding a historical message.
     */
    @Query(
        "SELECT message.*, COALESCE(segment.ordinal, -1) AS segmentOrdinal " +
            "FROM message LEFT JOIN segment ON segment.id = message.segmentId " +
            "WHERE message.threadId = :threadId AND message.deletedAt IS NULL " +
            "ORDER BY COALESCE(segment.ordinal, -1) DESC, message.createdAt DESC, message.id DESC LIMIT :limit",
    )
    suspend fun chatTailInSegmentOrder(threadId: String, limit: Int): List<MessageWithSegmentOrdinal>

    /**
     * 批量插入，主键冲突就跳过（导入幂等兜底）。
     *
     * **不要用返回值做幂等计数**：`OnConflictStrategy.IGNORE` 下 Room 对被忽略的行返回 -1，
     * 语义脆；幂等计数以同事务的 [existingIds] 为准（「不依赖 IGNORE 返回值语义」）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(messages: List<MessageEntity>): List<Long>

    /** 幂等判定：给定确定性 id 集合，返回其中已存在的 id（由调用方按 ≤500 分批）。 */
    @Query("SELECT id FROM message WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query("SELECT id FROM message WHERE threadId = :threadId AND deletedAt IS NULL AND status = 'OK' AND id IN (:ids)")
    suspend fun existingIdsForThread(threadId: String, ids: List<String>): List<String>

    /** 按 id 批量取消息（记忆整理 用 作用域）。*/
    @Query("SELECT * FROM message WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    /**
     * 聊天页「查看更早」的游标分页（v0.4 消费）。
     *
     * 游标是 `(createdAt, id)`：同一毫秒的消息用主键兜底，翻页不重不漏。
     * 依据 §8 作用域。
     */
    @Query(
        "SELECT * FROM message WHERE threadId = :threadId AND deletedAt IS NULL " +
            "AND (createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND id < :beforeId)) " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    suspend fun before(
        threadId: String,
        beforeCreatedAt: Long,
        beforeId: String,
        limit: Int,
    ): List<MessageEntity>

    /**
     * 群聊 UI 正序窗口：排序与 `ChatRepository.groupHistory` 相同，但成员 ordinal 由本次打开时的
     * 快照传入，避免成员改序期间前后页使用不同排序键。依据：app/src/main/java/cc/pulsez/app/data/repo/Repositories.kt:190。
     */
    @Query(
        "SELECT * FROM message WHERE threadId = :threadId AND deletedAt IS NULL " +
            "ORDER BY createdAt DESC, " +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END DESC, id DESC LIMIT :limit",
    )
    suspend fun groupUiTail(
        threadId: String,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
        limit: Int,
    ): List<MessageEntity>

    /** Strict keyset page before a composite cursor, using one frozen member-order snapshot. */
    @Query(
        "SELECT * FROM message WHERE threadId = :threadId AND deletedAt IS NULL AND (" +
            "createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END < :beforeMemberOrdinal OR (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END = :beforeMemberOrdinal AND id < :beforeId)))) " +
            "ORDER BY createdAt DESC, " +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END DESC, id DESC LIMIT :limit",
    )
    suspend fun groupUiBefore(
        threadId: String,
        beforeCreatedAt: Long,
        beforeMemberOrdinal: Int,
        beforeId: String,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
        limit: Int,
    ): List<MessageEntity>

    /** Last user row before a page boundary; its text supplies cross-page @ context. */
    @Query(
        "SELECT * FROM message WHERE threadId = :threadId AND deletedAt IS NULL AND role = 'user' AND (" +
            "createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END < :beforeMemberOrdinal OR (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END = :beforeMemberOrdinal AND id < :beforeId)))) " +
            "ORDER BY createdAt DESC, " +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END DESC, id DESC LIMIT 1",
    )
    suspend fun groupUiPreviousUser(
        threadId: String,
        beforeCreatedAt: Long,
        beforeMemberOrdinal: Int,
        beforeId: String,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
    ): MessageEntity?

    /** Number of assistant rows since the previous user, saturated at 2 for turn classification. */
    @Query(
        "SELECT CASE WHEN COUNT(*) > 1 THEN 2 ELSE COUNT(*) END FROM message " +
            "WHERE threadId = :threadId AND deletedAt IS NULL AND role = 'assistant' AND (" +
            "createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END < :beforeMemberOrdinal OR (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END = :beforeMemberOrdinal AND id < :beforeId)))) " +
            "AND (createdAt > :afterCreatedAt OR (createdAt = :afterCreatedAt AND (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END > :afterMemberOrdinal OR (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END = :afterMemberOrdinal AND id > :afterId))))",
    )
    suspend fun groupUiAssistantCountSinceUser(
        threadId: String,
        afterCreatedAt: Long,
        afterMemberOrdinal: Int,
        afterId: String,
        beforeCreatedAt: Long,
        beforeMemberOrdinal: Int,
        beforeId: String,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
    ): Int

    @Query(
        "SELECT CASE WHEN COUNT(*) > 1 THEN 2 ELSE COUNT(*) END FROM message " +
            "WHERE threadId = :threadId AND deletedAt IS NULL AND role = 'assistant' AND (" +
            "createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END < :beforeMemberOrdinal OR (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END = :beforeMemberOrdinal AND id < :beforeId))))",
    )
    suspend fun groupUiAssistantCountBefore(
        threadId: String,
        beforeCreatedAt: Long,
        beforeMemberOrdinal: Int,
        beforeId: String,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
    ): Int

    /** Most recent assistant in the same turn as a boundary, used only when it is the MAIN row. */
    @Query(
        "SELECT message.* FROM message WHERE threadId = :threadId AND deletedAt IS NULL AND role = 'assistant' AND (" +
            "createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END < :beforeMemberOrdinal OR (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END = :beforeMemberOrdinal AND id < :beforeId)))) " +
            "AND (createdAt > :afterCreatedAt OR (createdAt = :afterCreatedAt AND (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END > :afterMemberOrdinal OR (" +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END = :afterMemberOrdinal AND id > :afterId)))) " +
            "ORDER BY createdAt DESC, " +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END DESC, id DESC LIMIT 1",
    )
    suspend fun groupUiPreviousAssistantSinceUser(
        threadId: String,
        afterCreatedAt: Long,
        afterMemberOrdinal: Int,
        afterId: String,
        beforeCreatedAt: Long,
        beforeMemberOrdinal: Int,
        beforeId: String,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
    ): MessageEntity?

    /**
     * Reconstructs only the per-member consecutive-silence counts when no persisted ledger exists.
     * The CTE identifies the first non-null-speaker assistant after each user row using groupHistory's
     * frozen composite order; the outer query returns one aggregate row per current member.
     * 依据：GroupChatViewModel.rebuildTurnState 的按历史首条 assistant 更新规则。
     */
    @Query(
        GROUP_UI_PRIMARY_TURNS_CTE +
            "SELECT tm.characterId AS characterId, (" +
            "SELECT COUNT(*) FROM primary_turns p " +
            "WHERE p.speakerId != tm.characterId AND NOT EXISTS (" +
            "SELECT 1 FROM primary_turns laterOwn WHERE laterOwn.speakerId = tm.characterId AND (" +
            "laterOwn.createdAt > p.createdAt OR (laterOwn.createdAt = p.createdAt AND (" +
            "laterOwn.groupRank > p.groupRank OR (laterOwn.groupRank = p.groupRank AND laterOwn.id > p.id))))" +
            ")) AS silentTurns FROM thread_member tm WHERE tm.threadId = :threadId " +
            "AND tm.characterId IN (:memberIds) ORDER BY tm.ordinal ASC",
    )
    suspend fun groupUiFallbackSilentTurns(
        threadId: String,
        memberIds: List<String>,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
    ): List<GroupLedgerSilentTurns>

    /** The latest primary turn per distinct speaker, sufficient for the two-turn cooldown queue. */
    @Query(
        GROUP_UI_PRIMARY_TURNS_CTE +
            "SELECT p.speakerId FROM primary_turns p WHERE NOT EXISTS (" +
            "SELECT 1 FROM primary_turns newer WHERE newer.speakerId = p.speakerId AND (" +
            "newer.createdAt > p.createdAt OR (newer.createdAt = p.createdAt AND (" +
            "newer.groupRank > p.groupRank OR (newer.groupRank = p.groupRank AND newer.id > p.id))))" +
            ") ORDER BY p.createdAt DESC, p.groupRank DESC, p.id DESC LIMIT :limit",
    )
    suspend fun groupUiFallbackLastSpeakers(
        threadId: String,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
        limit: Int,
    ): List<String>

    /** Bounded recent AI replies for prompt de-duplication; returned newest-first. */
    @Query(
        "SELECT content FROM message WHERE threadId = :threadId AND deletedAt IS NULL AND role = 'assistant' " +
            "ORDER BY createdAt DESC, " +
            "CASE WHEN speakerCharacterId IS NULL THEN -1 " +
            "WHEN speakerCharacterId = :memberId0 THEN :memberOrdinal0 " +
            "WHEN speakerCharacterId = :memberId1 THEN :memberOrdinal1 " +
            "WHEN speakerCharacterId = :memberId2 THEN :memberOrdinal2 " +
            "WHEN speakerCharacterId = :memberId3 THEN :memberOrdinal3 " +
            "ELSE 2147483647 END DESC, id DESC LIMIT :limit",
    )
    suspend fun groupUiRecentAssistantReplies(
        threadId: String,
        memberId0: String?, memberOrdinal0: Int?,
        memberId1: String?, memberOrdinal1: Int?,
        memberId2: String?, memberOrdinal2: Int?,
        memberId3: String?, memberOrdinal3: Int?,
        limit: Int,
    ): List<String>

    /** Stable keyset pagination matching [chatTailInSegmentOrder]. */
    @Query(
        "SELECT message.*, COALESCE(segment.ordinal, -1) AS segmentOrdinal " +
            "FROM message LEFT JOIN segment ON segment.id = message.segmentId " +
            "WHERE message.threadId = :threadId AND message.deletedAt IS NULL AND (" +
            "COALESCE(segment.ordinal, -1) < :beforeSegmentOrdinal OR " +
            "(COALESCE(segment.ordinal, -1) = :beforeSegmentOrdinal AND " +
            "(message.createdAt < :beforeCreatedAt OR " +
            "(message.createdAt = :beforeCreatedAt AND message.id < :beforeId)))) " +
            "ORDER BY COALESCE(segment.ordinal, -1) DESC, message.createdAt DESC, message.id DESC LIMIT :limit",
    )
    suspend fun chatBeforeInSegmentOrder(
        threadId: String,
        beforeSegmentOrdinal: Int,
        beforeCreatedAt: Long,
        beforeId: String,
        limit: Int,
    ): List<MessageWithSegmentOrdinal>

    // 群聊/单聊共用的正序历史。同一毫秒内的多条消息用主键兜底排序，保证多次查询结果稳定
    //（群聊一轮里多个角色可能落在同一毫秒；真正的发言顺序由 ChatRepository.groupHistory
    // 再按 thread_member.ordinal 兜底，见 步骤 3）。
    @Query("SELECT * FROM message WHERE threadId = :threadId AND deletedAt IS NULL ORDER BY createdAt ASC, id ASC")
    suspend fun allForThread(threadId: String): List<MessageEntity>

    @Query("SELECT * FROM message WHERE threadId = :threadId ORDER BY createdAt ASC, id ASC")
    suspend fun allIncludingDeletedForThread(threadId: String): List<MessageEntity>

    /** 群聊里按发言人过滤（`speakerCharacterId`）；正序、稳定。*/
    @Query(
        "SELECT * FROM message WHERE threadId = :threadId AND speakerCharacterId = :speakerCharacterId " +
            "AND deletedAt IS NULL ORDER BY createdAt ASC, id ASC",
    )
    suspend fun bySpeaker(threadId: String, speakerCharacterId: String): List<MessageEntity>

    @Query("SELECT * FROM message WHERE segmentId = :segmentId AND deletedAt IS NULL AND status = 'OK' ORDER BY createdAt ASC")
    suspend fun bySegment(segmentId: String): List<MessageEntity>

    /** Batch preview read for the home screen; message bodies are returned only for the visible six roles. */
    @Query(
        "SELECT * FROM message WHERE segmentId IN (:segmentIds) AND deletedAt IS NULL AND status = 'OK' " +
            "ORDER BY createdAt DESC, id DESC",
    )
    suspend fun latestForSegments(segmentIds: List<String>): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM message WHERE threadId = :threadId AND deletedAt IS NULL")
    suspend fun liveCountForThread(threadId: String): Int

    @Query("SELECT MAX(createdAt) FROM message WHERE threadId = :threadId AND deletedAt IS NULL")
    suspend fun maxLiveCreatedAt(threadId: String): Long?

    @Query("DELETE FROM message WHERE segmentId = :segmentId")
    suspend fun deleteForSegment(segmentId: String)

    @Query("SELECT * FROM message WHERE threadId = :threadId AND deletedAt IS NULL AND createdAt > :after ORDER BY createdAt ASC")
    suspend fun after(threadId: String, after: Long): List<MessageEntity>

    @Query("UPDATE message SET content = :content, previousContent = COALESCE(previousContent, content), deletedAt = NULL WHERE id = :id")
    suspend fun replaceContent(id: String, content: String)

    @Query("UPDATE message SET deletedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query("DELETE FROM message WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: String)
}

// ---------------------------------------------------------------- 数据库

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(segment: SegmentEntity)

    @Query("SELECT * FROM segment WHERE threadId = :threadId ORDER BY ordinal ASC")
    suspend fun forThread(threadId: String): List<SegmentEntity>

    @Query("SELECT * FROM segment WHERE threadId = :threadId ORDER BY ordinal DESC LIMIT 1")
    suspend fun latest(threadId: String): SegmentEntity?

    @Query("SELECT * FROM segment WHERE id = :id")
    suspend fun byId(id: String): SegmentEntity?

    /**
     * 把当前 OPEN 段的 ordinal 上移（导入事务用 第 3 条）。
     *
     * 带 `state = 'OPEN'` 条件：如果这一瞬间段已被交接/其它路径封存（异常并发），返回 0，
     * 由调用方抛错回滚整个会话事务 —— 绝不把一条已封存的段再改一遍。
     *
     * @return 实际更新的行数（0 或 1）
     */
    @Query("UPDATE segment SET ordinal = :ordinal WHERE id = :id AND state = 'OPEN'")
    suspend fun setOrdinalIfOpen(id: String, ordinal: Int): Int

    @Query("DELETE FROM segment WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM segment WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: String)
}

@Dao
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatch(batch: ImportBatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: ImportBatchGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(groups: List<ImportBatchGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWindows(windows: List<ImportBatchWindowEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: ImportSourceSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<ImportSourceSessionEntity>)

    @Query("SELECT * FROM import_batch WHERE id = :id")
    suspend fun batch(id: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batch ORDER BY createdAt ASC")
    suspend fun allBatches(): List<ImportBatchEntity>

    @Query("SELECT status FROM import_batch WHERE id = :id")
    suspend fun batchStatus(id: String): String?

    @Query("SELECT * FROM import_batch WHERE fingerprint = :fingerprint AND status IN ('RUNNING','PARTIAL','CANCELLED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun resumable(fingerprint: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batch_group WHERE batchId = :batchId ORDER BY id ASC")
    suspend fun groups(batchId: String): List<ImportBatchGroupEntity>

    @Query("SELECT * FROM import_batch_group WHERE id = :id")
    suspend fun group(id: String): ImportBatchGroupEntity?

    @Query("SELECT * FROM import_batch_group WHERE targetCharacterId = :characterId")
    suspend fun groupsForCharacter(characterId: String): List<ImportBatchGroupEntity>

    @Query("SELECT * FROM import_batch_window WHERE batchId = :batchId ORDER BY sourceSessionId ASC")
    suspend fun windows(batchId: String): List<ImportBatchWindowEntity>

    @Query("SELECT * FROM import_batch_window WHERE batchId = :batchId AND groupId = :groupId ORDER BY sourceStartedAt IS NULL ASC, sourceStartedAt ASC, sourceSessionId ASC")
    suspend fun windowsForGroup(batchId: String, groupId: String): List<ImportBatchWindowEntity>

    @Query("SELECT * FROM import_source_session WHERE batchId = :batchId ORDER BY sourceStartedAt IS NULL ASC, sourceStartedAt ASC, sourceSessionId ASC")
    suspend fun sourcesForBatch(batchId: String): List<ImportSourceSessionEntity>

    @Query("SELECT * FROM import_source_session WHERE targetCharacterId = :characterId AND sourceSessionId = :sourceSessionId LIMIT 1")
    suspend fun sourceForTarget(characterId: String, sourceSessionId: String): ImportSourceSessionEntity?

    @Query("SELECT * FROM import_source_session WHERE sourceSessionId IN (:sourceSessionIds) ORDER BY targetCharacterId ASC, id ASC")
    suspend fun sourcesForSessionIds(sourceSessionIds: List<String>): List<ImportSourceSessionEntity>

    @Query("SELECT * FROM import_source_session WHERE targetThreadId = :threadId ORDER BY sourceStartedAt IS NULL ASC, sourceStartedAt ASC, sourceSessionId ASC")
    suspend fun sourcesForThread(threadId: String): List<ImportSourceSessionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM import_batch_group g INNER JOIN import_batch b ON b.id = g.batchId WHERE g.targetThreadId = :threadId AND b.status = 'UNDONE')")
    suspend fun hasUndoneBatchForThread(threadId: String): Boolean

    @Query("UPDATE import_batch_group SET status = :status, errorCode = :errorCode WHERE id = :id")
    suspend fun setGroupStatus(id: String, status: String, errorCode: String?)

    @Query("UPDATE import_batch_window SET status = :status, errorCode = :errorCode WHERE batchId = :batchId AND sourceSessionId = :sourceSessionId")
    suspend fun setWindowStatus(batchId: String, sourceSessionId: String, status: String, errorCode: String?)

    @Query("UPDATE import_batch SET status = :status, postProcessStatus = :postProcessStatus WHERE id = :id")
    suspend fun setBatchStatus(id: String, status: String, postProcessStatus: String)

    @Query("UPDATE import_source_session SET indexStatus = :status WHERE id = :id")
    suspend fun setSourceIndexStatus(id: String, status: String)

    @Query("DELETE FROM import_source_session WHERE id = :id")
    suspend fun deleteSource(id: String)

    @Query("DELETE FROM import_source_session WHERE targetCharacterId = :characterId")
    suspend fun deleteSourcesForCharacter(characterId: String)

    @Query("DELETE FROM import_batch_window WHERE batchId = :batchId AND groupId = :groupId")
    suspend fun deleteWindowsForGroup(batchId: String, groupId: String)

    @Query("DELETE FROM import_batch_group WHERE targetCharacterId = :characterId")
    suspend fun deleteGroupsForCharacter(characterId: String)

    @Query("DELETE FROM import_batch WHERE id = :id")
    suspend fun deleteBatch(id: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_item WHERE threadId = :threadId")
    suspend fun allForThread(threadId: String): List<MemoryItemEntity>

    /**
     * 按 `(threadId, characterId)` 读记忆。
     *
     * `ownerId = null` 表示单聊维度（`characterId IS NULL`），非 null 表示群聊里该角色自己的记忆。
     * 用 SQLite `IS`（null-safe 等值）而不是 `=`，否则 null 维度永远查不到。
     * 依据：https://www.sqlite.org/lang_expr.html#isisnot
     */
    @Query("SELECT * FROM memory_item WHERE threadId = :threadId AND characterId IS :ownerId ORDER BY kind ASC, lastSeenAt DESC")
    suspend fun forOwner(threadId: String, ownerId: String?): List<MemoryItemEntity>

    @Query("SELECT COUNT(*) FROM memory_item WHERE threadId = :threadId AND validTo IS NULL AND status IN ('ACTIVE','PINNED','UNVERIFIED')")
    suspend fun activeCount(threadId: String): Int

    /** Batch current single-chat memory counts for the home caption; no per-role count query. */
    @Query(
        "SELECT memory_item.threadId AS threadId, COUNT(*) AS memoryCount FROM memory_item " +
            "INNER JOIN thread ON thread.id = memory_item.threadId " +
            "INNER JOIN character ON character.id = thread.characterId " +
            "WHERE thread.archived = 0 AND thread.kind = 'SINGLE' AND character.deletedAt IS NULL " +
            "AND memory_item.validTo IS NULL AND memory_item.status IN ('ACTIVE','PINNED','UNVERIFIED') " +
            "GROUP BY memory_item.threadId",
    )
    suspend fun activeCountsForSingleThreads(): List<ThreadMemoryCount>

    /** 同上，按 `(threadId, characterId)` 维度计数。 */
    @Query(
        "SELECT COUNT(*) FROM memory_item WHERE threadId = :threadId AND characterId IS :ownerId " +
            "AND validTo IS NULL AND status IN ('ACTIVE','PINNED','UNVERIFIED')",
    )
    suspend fun activeCountForOwner(threadId: String, ownerId: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MemoryItemEntity>)

    @Query("DELETE FROM memory_item WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: String)

    @Query("DELETE FROM memory_item WHERE threadId = :threadId AND characterId IS :ownerId")
    suspend fun deleteForOwner(threadId: String, ownerId: String?)

    @Query("DELETE FROM memory_item WHERE characterId = :ownerId")
    suspend fun deleteForCharacterOwner(ownerId: String)

    @androidx.room.Transaction
    suspend fun replaceAll(threadId: String, items: List<MemoryItemEntity>) {
        deleteForThread(threadId)
        if (items.isNotEmpty()) insertAll(items)
    }

    /** 只替换某个 owner 维度的行：单聊传 null，群聊传 characterId。*/
    @androidx.room.Transaction
    suspend fun replaceForOwner(threadId: String, ownerId: String?, items: List<MemoryItemEntity>) {
        deleteForOwner(threadId, ownerId)
        if (items.isNotEmpty()) insertAll(items)
    }

    @Query("UPDATE memory_item SET status = 'UNVERIFIED' WHERE threadId = :threadId AND sourceMessageIds LIKE '%' || :messageId || '%'")
    suspend fun markUnverifiedBySource(threadId: String, messageId: String)
}

data class ThreadMemoryCount(val threadId: String, val memoryCount: Int)

@Dao
interface ThreadStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ThreadStateEntity)

    @Query("SELECT * FROM thread_state WHERE threadId = :threadId")
    suspend fun get(threadId: String): ThreadStateEntity?

    @Query("DELETE FROM thread_state WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: String)
}

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunk WHERE threadId = :threadId AND deletedAt IS NULL ORDER BY lastMessageAt DESC LIMIT :limit")
    suspend fun recent(threadId: String, limit: Int): List<ChunkEntity>

    /** 按 id 批量取检索块（群聊召回：关键词先出 id，再取原文，避免全表载入向量）。 */
    @Query("SELECT * FROM chunk WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun byIds(ids: List<String>): List<ChunkEntity>

    @Query("SELECT * FROM chunk WHERE threadId = :threadId AND deletedAt IS NULL AND lastMessageAt BETWEEN :from AND :to ORDER BY lastMessageAt DESC LIMIT :limit")
    suspend fun inRange(threadId: String, from: Long, to: Long, limit: Int): List<ChunkEntity>

    @Query("UPDATE chunk SET deletedAt = :at WHERE threadId = :threadId AND messageIds LIKE '%' || :messageId || '%'")
    suspend fun invalidateByMessage(threadId: String, messageId: String, at: Long)

    @Query("DELETE FROM chunk WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: String)

    @Query("SELECT * FROM chunk WHERE segmentId = :segmentId")
    suspend fun forSegment(segmentId: String): List<ChunkEntity>

    @Query("DELETE FROM chunk WHERE segmentId = :segmentId")
    suspend fun deleteForSegment(segmentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerms(terms: List<ChunkTermEntity>)

    @Query(
        "SELECT chunkId, SUM(weight) AS score FROM chunk_term " +
            "WHERE threadId = :threadId AND term IN (:terms) " +
            "GROUP BY chunkId ORDER BY score DESC LIMIT :limit",
    )
    suspend fun searchTerms(threadId: String, terms: List<String>, limit: Int): List<ChunkScore>

    @Query("DELETE FROM chunk_term WHERE chunkId IN (:chunkIds)")
    suspend fun deleteTerms(chunkIds: List<String>)

    @Query("DELETE FROM chunk_term WHERE threadId = :threadId")
    suspend fun deleteTermsForThread(threadId: String)

    @Query("DELETE FROM chunk_term WHERE chunkId IN (SELECT id FROM chunk WHERE segmentId = :segmentId)")
    suspend fun deleteTermsForSegment(segmentId: String)
}

data class ChunkScore(val chunkId: String, val score: Int)

/**
 * 附件读写（v0.5.0）。
 *
 * 约定：
 * - 所有「对外可见」查询都过滤 `deletedAt IS NULL`；
 * - 软删只改标记，不删文件——文件由调用方 `AttachmentStore.delete(localPath)` 或启动/清空时的
 *   孤儿清理回收（`AttachmentRepository` 文档里写清这层分工）；陈旧草稿/失败态/未绑定 READY 由
 *   `AttachmentRepository.sweepStaleDrafts` 先软删，再由孤儿清理删文件；
 * - `bindToMessage` 带 `deletedAt IS NULL` 条件，绑定失败返回 0，由仓储层抛可读异常并回滚事务。
 */
@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity)

    /**
     * 按 id 取附件行——**仅供仓储内部使用**。
     *
     * 不过滤 `deletedAt`：可能返回**已软删**行（仓储在状态机推进/绑定后复核用）。
     * 不要拿它做 UI 展示数据源；未来 UI 需要按 id 展示时另加 `byIdLive()`（过滤 `deletedAt IS NULL`）。
     * 依据：` 建议 7。
     */
    @Query("SELECT * FROM attachment WHERE id = :id")
    suspend fun byId(id: String): AttachmentEntity?

    /** 某条消息的附件（时间正序；同毫秒用主键兜底，保证多次读取顺序稳定）。 */
    @Query(
        "SELECT * FROM attachment WHERE messageId = :messageId AND deletedAt IS NULL " +
            "ORDER BY createdAt ASC, id ASC",
    )
    suspend fun byMessageId(messageId: String): List<AttachmentEntity>

    /** 草稿箱：尚未绑定消息的 DRAFT（` 草稿可空 messageId）。*/
    @Query(
        "SELECT * FROM attachment WHERE status = 'DRAFT' AND messageId IS NULL AND deletedAt IS NULL " +
            "ORDER BY createdAt ASC, id ASC",
    )
    suspend fun listDraft(): List<AttachmentEntity>

    /**
     * 启动维护用：**陈旧且未绑定**的草稿/失败态/READY 行
     * （v0.5.0 ` 扩面，`）。
     *
     * 为什么需要它：启动孤儿文件清理的 keep 集合是 `deletedAt IS NULL` 的**活行**，而
     * 删草稿/绑定失败/导入中删除等路径留下的行仍是活行，孤儿清理永远不会回收它们 →
     * 永久残留一行 DB + 一个文件。这里先按 24h TTL 把这些行软删，下一次
     * `cleanupOrphans` 才能把文件当孤儿回收（仓储层 `sweepStaleDrafts` 同事务先软删再删文件）。
     *
     * 条件刻意收窄（不误删）：
     * - `messageId IS NULL`：已绑定到消息的附件属于某轮对话，不在清理面；
     * - `status IN ('DRAFT','FAILED','READY')`：SENT/SENDING 与已绑定行不动。
     *   **READY 必须包含在内（依据：`）**：生产代码从不写
     *   [AttachmentStatus.FAILED]（全仓只有读取过滤），`AttachmentRepository.bindToMessage`
     *   整批回滚后行仍是 READY；「选图成功即 READY、没发就离开/进程被杀」也留下 READY
     *   未绑定行。旧条件 `('DRAFT','FAILED')` 永远扫不到它们 → 永久活行 + 永久文件；
     * - `createdAt < :cutoff`：只清 TTL 之外的，新鲜草稿必须保下来（用户可能正在选图/打字）。
     *
     * 扩到 READY 的安全性（依据：`）：本查询只在**启动**时由
     * `PulsezApp` 后台的 `sweepStaleDrafts` 调用；草稿只存在于内存、跨进程不恢复
     * （`ChatViewModel.load()` 不恢复草稿，`listDraft()` 无生产调用），所以启动时刻不存在
     * 「马上就要发的 READY 内存草稿」；24h TTL 远大于正常选图→发送间隔，TTL 内新鲜 READY 保留。
     */
    @Query(
        "SELECT * FROM attachment WHERE deletedAt IS NULL AND messageId IS NULL " +
            "AND status IN ('DRAFT', 'FAILED', 'READY') AND createdAt < :cutoff ORDER BY createdAt ASC",
    )
    suspend fun staleUnboundDrafts(cutoff: Long): List<AttachmentEntity>

    /**
     * 软删一条「陈旧且未绑定」的草稿/失败态/READY 行（与 [staleUnboundDrafts] 同条件）。
     *
     * 用带条件的 UPDATE 而不是先查后删：查询与删除之间可能有别的流程把行绑定到消息
     * （用户恰好这时点发送），条件不满足时更新 0 行，调用方据此**不删文件**。
     *
     * READY 在条件里：绑定失败回滚后行仍是 READY（生产从不写 FAILED），
     * 依据 ` 理由与安全性见 [staleUnboundDrafts]。
     *
     * @return 1 = 本次软删成功；0 = 行已不在条件内（已绑定/已软删/还新鲜）
     */
    @Query(
        "UPDATE attachment SET deletedAt = :at WHERE id = :id AND deletedAt IS NULL " +
            "AND messageId IS NULL AND status IN ('DRAFT', 'FAILED', 'READY') AND createdAt < :cutoff",
    )
    suspend fun softDeleteStaleUnboundDraft(id: String, at: Long, cutoff: Long): Int

    /** 导出用：全部未软删附件（只导元数据，不导 localPath/remoteFileId）。 */
    @Query("SELECT * FROM attachment WHERE deletedAt IS NULL ORDER BY createdAt ASC, id ASC")
    suspend fun allLive(): List<AttachmentEntity>

    /** Attachment metadata for one role's single chats, including previously soft-deleted rows. */
    @Query(
        "SELECT DISTINCT attachment.* FROM attachment LEFT JOIN message ON message.id = attachment.messageId " +
            "LEFT JOIN thread ON thread.id = COALESCE(attachment.ownerThreadId, message.threadId) " +
            "WHERE thread.characterId = :characterId AND thread.kind = 'SINGLE'",
    )
    suspend fun forCharacterSingleChats(characterId: String): List<AttachmentEntity>

    @Query("DELETE FROM attachment WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE attachment SET deletedAt = :at WHERE messageId = :messageId AND deletedAt IS NULL")
    suspend fun softDeleteByMessageId(messageId: String, at: Long): Int

    @Query("UPDATE attachment SET deletedAt = :at WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDeleteById(id: String, at: Long): Int

    /** 「清空全部数据」走仓储层软删：保留可追溯标记，物理清理由 `clearAllTables` 兜底。 */
    @Query("UPDATE attachment SET deletedAt = :at WHERE deletedAt IS NULL")
    suspend fun softDeleteAll(at: Long): Int

    /**
     * 物理清掉已软删行（可选维护操作，按需调用；不自动跑）。
     *
     * **预留（v0.5.1+，`）**：目前生产代码无调用（软删行保留是为了可追溯，
     * 也避免在维护动作里做硬删）。保留给「压缩/重置数据库」类维护动作；不要当死代码删掉。
     */
    @Query("DELETE FROM attachment WHERE deletedAt IS NOT NULL")
    suspend fun clearSoftDeleted(): Int

    @Query("SELECT COUNT(*) FROM attachment WHERE messageId = :messageId AND deletedAt IS NULL")
    suspend fun countByMessage(messageId: String): Int

    /** 绑定到消息；返回 0 表示附件不存在/已删除（调用方抛错并回滚）。 */
    @Query("UPDATE attachment SET messageId = :messageId WHERE id = :id AND deletedAt IS NULL")
    suspend fun bindToMessage(id: String, messageId: String): Int

    /** 状态机推进（READY/SENDING/SENT/FAILED）；返回 0 表示附件不存在/已删除。 */
    @Query("UPDATE attachment SET status = :status, errorKind = :errorKind WHERE id = :id AND deletedAt IS NULL")
    suspend fun updateStatus(id: String, status: String, errorKind: String?): Int

    /** 仍在使用的附件 id（启动孤儿文件清理的 keep 集合）。*/
    @Query("SELECT id FROM attachment WHERE deletedAt IS NULL")
    suspend fun idsInUse(): List<String>
}

@Dao
interface PendingAttachmentDeleteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PendingAttachmentDeleteEntity>)

    @Query("SELECT * FROM pending_attachment_delete ORDER BY requestedAt ASC, attachmentId ASC")
    suspend fun all(): List<PendingAttachmentDeleteEntity>

    @Query("UPDATE pending_attachment_delete SET attempts = attempts + 1, lastAttemptAt = :at WHERE attachmentId = :id")
    suspend fun recordAttempt(id: String, at: Long)

    @Query("DELETE FROM pending_attachment_delete WHERE attachmentId = :id")
    suspend fun delete(id: String)
}


@Database(
    entities = [
        CharacterEntity::class,
        ThreadEntity::class,
        MessageEntity::class,
        SegmentEntity::class,
        MemoryItemEntity::class,
        ThreadStateEntity::class,
        ChunkEntity::class,
        ChunkTermEntity::class,
        ThreadMemberEntity::class,
        AttachmentEntity::class,
        ImportBatchEntity::class,
        ImportBatchGroupEntity::class,
        ImportBatchWindowEntity::class,
        ImportSourceSessionEntity::class,
        DeletedCharacterEntity::class,
        PendingAttachmentDeleteEntity::class,
    ],
    // v6: durable deletion tombstones, retryable attachment cleanup, and role-owned drafts.
    version = 7,
    exportSchema = true,
)
abstract class PulsezDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun segmentDao(): SegmentDao
    abstract fun memoryDao(): MemoryDao
    abstract fun threadStateDao(): ThreadStateDao
    abstract fun chunkDao(): ChunkDao
    abstract fun threadMemberDao(): ThreadMemberDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun deletedCharacterDao(): DeletedCharacterDao
    abstract fun pendingAttachmentDeleteDao(): PendingAttachmentDeleteDao

    companion object {
        /**
         * v1 → v2 迁移。
         *
         * 按 给出的 SQL 执行；唯一必要偏离是 `thread` 表：
         * v2 要求 `characterId` 可空，而 SQLite 无法把已有列从 NOT NULL 改成可空
         * （`ALTER TABLE ... ALTER COLUMN ... DROP NOT NULL` 到 SQLite 3.53.0 才出现，
         * Android 12 / API 31 自带的是 3.32，见 与
         * https://www.sqlite.org/lang_altertable.html 、
         * https://developer.android.com/reference/android/database/sqlite/package-summary）。
         * 因此 `thread` 用官方推荐的「新建表 → 拷数据 → 删旧表 → 改名 → 重建索引」12 步法，
         * 顺带在同一张新表里带上 `kind` 列（默认 'SINGLE'，旧行语义不变）。
         * 其余表严格按 的 ALTER/CREATE 执行。
         *
         * 整个过程由 SQLiteOpenHelper 包在事务里（Room 迁移失败会抛
         * "Migration didn't properly handle"），不会留下半迁移状态。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) message：群聊发言人
                db.execSQL("ALTER TABLE `message` ADD COLUMN `speakerCharacterId` TEXT")

                // 2) memory_item：群聊按角色隔离记忆
                db.execSQL("ALTER TABLE `memory_item` ADD COLUMN `characterId` TEXT")

                // 3) thread_member：新表 + characterId 索引
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `thread_member` (" +
                        "`threadId` TEXT NOT NULL, " +
                        "`characterId` TEXT NOT NULL, " +
                        "`ordinal` INTEGER NOT NULL, " +
                        "`joinedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`threadId`, `characterId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_thread_member_characterId` " +
                        "ON `thread_member` (`characterId`)",
                )

                // 4) thread：重建以放开 characterId 的 NOT NULL，并加 kind 列
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `thread_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`characterId` TEXT, " +
                        "`title` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastMessageAt` INTEGER NOT NULL, " +
                        "`activeSegmentId` TEXT, " +
                        "`segmentOrdinal` INTEGER NOT NULL, " +
                        "`totalMessages` INTEGER NOT NULL, " +
                        "`totalTokens` INTEGER NOT NULL, " +
                        "`lastExtractAt` INTEGER NOT NULL, " +
                        "`archived` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL DEFAULT 'SINGLE', " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`characterId`) REFERENCES `character`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO `thread_new` (" +
                        "`id`, `characterId`, `title`, `createdAt`, `lastMessageAt`, " +
                        "`activeSegmentId`, `segmentOrdinal`, `totalMessages`, `totalTokens`, " +
                        "`lastExtractAt`, `archived`, `kind`) " +
                        "SELECT `id`, `characterId`, `title`, `createdAt`, `lastMessageAt`, " +
                        "`activeSegmentId`, `segmentOrdinal`, `totalMessages`, `totalTokens`, " +
                        "`lastExtractAt`, `archived`, 'SINGLE' FROM `thread`",
                )
                db.execSQL("DROP TABLE `thread`")
                db.execSQL("ALTER TABLE `thread_new` RENAME TO `thread`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_characterId` ON `thread` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_lastMessageAt` ON `thread` (`lastMessageAt`)")
            }
        }

        /**
         * v2 → v3 迁移（v0.5.0）。
         *
         * **只新增 `attachment` 一张表 + 两个索引**，不重建、不修改任何 v2 旧表/旧列——
         * 所以 v2 用户的聊天、记忆、检索块、群成员数据一条都不动。
         *
         * SQL 必须与 Room 编译期从 [AttachmentEntity] 生成的建表语句逐列一致
         * （类型 / NOT NULL / 默认值 / 主键 / 索引），否则 Room 的
         * `onValidateSchema` 会抛 `Migration didn't properly handle: attachment`，迁移测试
         * `MigrationV2ToV3Test` 会直接暴露。字段顺序按实体声明顺序，便于对照 `app/schemas/.../3.json`。
         *
         * 刻意不写 destructive fallback（同 MIGRATION_1_2 的理由）。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachment` (" +
                        "`id` TEXT NOT NULL, " +
                        "`messageId` TEXT, " +
                        "`kind` TEXT NOT NULL, " +
                        "`mime` TEXT NOT NULL, " +
                        "`localPath` TEXT NOT NULL, " +
                        "`fileName` TEXT, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`width` INTEGER, " +
                        "`height` INTEGER, " +
                        "`status` TEXT NOT NULL, " +
                        "`errorKind` TEXT, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`remoteFileId` TEXT, " +
                        "`expiresAt` INTEGER, " +
                        "`deletedAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachment_messageId` " +
                        "ON `attachment` (`messageId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachment_status` ON `attachment` (`status`)",
                )
            }
        }

        /**
         * v3 → v4: persist per-source provenance, assignments and batch status for atomic grouped imports.
         * ` confirms the archive has a source-session ID but no
         * TA identity. Keep the historical per-target unique index so old/backup duplicate rows survive;
         * new imports check provenance and exact deterministic message IDs inside the group transaction.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_batch` (" +
                        "`id` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `zipName` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `postProcessStatus` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_batch_group` (" +
                        "`id` TEXT NOT NULL, `batchId` TEXT NOT NULL, `targetCharacterId` TEXT, " +
                        "`targetThreadId` TEXT, `isNewCharacter` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                        "`persona` TEXT, `status` TEXT NOT NULL, `memoryFingerprint` TEXT, " +
                        "`beforeLastMessageAt` INTEGER, `beforeLastExtractAt` INTEGER, " +
                        "`beforeCharacterUpdatedAt` INTEGER, `importLastMessageAt` INTEGER, " +
                        "`importMessageCount` INTEGER NOT NULL, `errorCode` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_batch_group_batchId` ON `import_batch_group` (`batchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_batch_group_targetCharacterId` ON `import_batch_group` (`targetCharacterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_batch_group_targetThreadId` ON `import_batch_group` (`targetThreadId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_batch_window` (" +
                        "`batchId` TEXT NOT NULL, `sourceSessionId` TEXT NOT NULL, `entryName` TEXT NOT NULL, " +
                        "`title` TEXT, `sourceStartedAt` INTEGER, `messageCount` INTEGER NOT NULL, " +
                        "`damageCode` TEXT, `groupId` TEXT, `status` TEXT NOT NULL, `errorCode` TEXT, " +
                        "PRIMARY KEY(`batchId`, `sourceSessionId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_batch_window_groupId` ON `import_batch_window` (`groupId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_source_session` (" +
                        "`id` TEXT NOT NULL, `batchId` TEXT NOT NULL, `targetCharacterId` TEXT NOT NULL, " +
                        "`targetThreadId` TEXT NOT NULL, `sourceSessionId` TEXT NOT NULL, `title` TEXT, " +
                        "`sourceStartedAt` INTEGER, `messageCount` INTEGER NOT NULL, `segmentId` TEXT NOT NULL, " +
                        "`indexStatus` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_import_source_session_targetCharacterId_sourceSessionId` ON `import_source_session` (`targetCharacterId`, `sourceSessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_source_session_batchId` ON `import_source_session` (`batchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_source_session_targetThreadId` ON `import_source_session` (`targetThreadId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_import_source_session_segmentId` ON `import_source_session` (`segmentId`)")
            }
        }

        /**
         * v4 → v5: only add nullable storage; historical message/import timestamps cannot identify a live event.
         * 依据 方案 B 与本轮任务的“不回填”约束。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `thread` ADD COLUMN `lastSingleChatActivityAt` INTEGER")
            }
        }

        /**
         * v5 → v6: establish durable role-deletion gates and allow new drafts to be scoped to their chat.
         * Existing ownerless drafts stay nullable: their role cannot be proven from stored data.
         * The guards protect late writes after the deletion transaction has committed; legacy backups
         * do not contain tombstones, and restore assigns fresh local role IDs.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `attachment` ADD COLUMN `ownerThreadId` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachment_ownerThreadId` ON `attachment` (`ownerThreadId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_character` (" +
                        "`characterId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`characterId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_attachment_delete` (" +
                        "`attachmentId` TEXT NOT NULL, `localPath` TEXT NOT NULL, `requestedAt` INTEGER NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `lastAttemptAt` INTEGER, PRIMARY KEY(`attachmentId`))",
                )
                installRoleDeletionGuards(db)
            }
        }

        /**
         * v6 → v7: store a non-secret embedding configuration fingerprint on chunks.
         * Legacy vectors have an empty fingerprint and are not reused for resumable import indexing.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `chunk` ADD COLUMN `embeddingConfigFingerprint` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /** Database-level gates also cover existing ViewModels and workers that finish after deletion. */
        internal fun installRoleDeletionGuards(db: SupportSQLiteDatabase) {
            val statements = listOf(
                "CREATE TRIGGER IF NOT EXISTS `guard_deleted_character_insert` BEFORE INSERT ON `character` " +
                    "WHEN EXISTS(SELECT 1 FROM `deleted_character` WHERE `characterId` = NEW.`id`) " +
                    "BEGIN SELECT RAISE(ABORT, 'role permanently deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_deleted_thread_insert` BEFORE INSERT ON `thread` " +
                    "WHEN NEW.`characterId` IS NOT NULL AND EXISTS(SELECT 1 FROM `deleted_character` WHERE `characterId` = NEW.`characterId`) " +
                    "BEGIN SELECT RAISE(ABORT, 'role permanently deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_deleted_member_insert` BEFORE INSERT ON `thread_member` " +
                    "WHEN EXISTS(SELECT 1 FROM `deleted_character` WHERE `characterId` = NEW.`characterId`) " +
                    "BEGIN SELECT RAISE(ABORT, 'role permanently deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_live_segment_insert` BEFORE INSERT ON `segment` " +
                    "WHEN NOT EXISTS(SELECT 1 FROM `thread` WHERE `id` = NEW.`threadId`) " +
                    "BEGIN SELECT RAISE(ABORT, 'chat deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_live_message_insert` BEFORE INSERT ON `message` " +
                    "WHEN NOT EXISTS(SELECT 1 FROM `thread` WHERE `id` = NEW.`threadId`) OR " +
                    "(NEW.`speakerCharacterId` IS NOT NULL AND EXISTS(SELECT 1 FROM `deleted_character` WHERE `characterId` = NEW.`speakerCharacterId`)) " +
                    "BEGIN SELECT RAISE(ABORT, 'chat or speaker deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_live_memory_insert` BEFORE INSERT ON `memory_item` " +
                    "WHEN NOT EXISTS(SELECT 1 FROM `thread` WHERE `id` = NEW.`threadId`) OR " +
                    "(NEW.`characterId` IS NOT NULL AND EXISTS(SELECT 1 FROM `deleted_character` WHERE `characterId` = NEW.`characterId`)) " +
                    "BEGIN SELECT RAISE(ABORT, 'chat or memory owner deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_live_thread_state_insert` BEFORE INSERT ON `thread_state` " +
                    "WHEN NOT EXISTS(SELECT 1 FROM `thread` WHERE `id` = NEW.`threadId`) " +
                    "BEGIN SELECT RAISE(ABORT, 'chat deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_live_chunk_insert` BEFORE INSERT ON `chunk` " +
                    "WHEN NOT EXISTS(SELECT 1 FROM `thread` WHERE `id` = NEW.`threadId`) OR " +
                    "EXISTS(SELECT 1 FROM `thread` JOIN `deleted_character` ON `thread`.`characterId` = `deleted_character`.`characterId` " +
                        "WHERE `thread`.`id` = NEW.`threadId` AND `thread`.`kind` = 'SINGLE') " +
                    "BEGIN SELECT RAISE(ABORT, 'chat deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_live_chunk_term_insert` BEFORE INSERT ON `chunk_term` " +
                    "WHEN NOT EXISTS(SELECT 1 FROM `chunk` WHERE `id` = NEW.`chunkId`) OR " +
                    "NOT EXISTS(SELECT 1 FROM `thread` WHERE `id` = NEW.`threadId`) OR " +
                    "EXISTS(SELECT 1 FROM `thread` JOIN `deleted_character` ON `thread`.`characterId` = `deleted_character`.`characterId` " +
                        "WHERE `thread`.`id` = NEW.`threadId` AND `thread`.`kind` = 'SINGLE') " +
                    "BEGIN SELECT RAISE(ABORT, 'chat deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_live_attachment_insert` BEFORE INSERT ON `attachment` " +
                    "WHEN NEW.`ownerThreadId` IS NOT NULL AND NOT EXISTS(SELECT 1 FROM `thread` WHERE `id` = NEW.`ownerThreadId`) OR " +
                    "EXISTS(SELECT 1 FROM `thread` JOIN `deleted_character` ON `thread`.`characterId` = `deleted_character`.`characterId` " +
                        "WHERE `thread`.`id` = NEW.`ownerThreadId` AND `thread`.`kind` = 'SINGLE') " +
                    "BEGIN SELECT RAISE(ABORT, 'chat deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_live_attachment_bind` BEFORE UPDATE OF `messageId` ON `attachment` " +
                    "WHEN NEW.`messageId` IS NOT NULL AND NOT EXISTS(SELECT 1 FROM `message` WHERE `id` = NEW.`messageId` " +
                        "AND `deletedAt` IS NULL AND (NEW.`ownerThreadId` IS NULL OR `threadId` = NEW.`ownerThreadId`)) OR " +
                    "EXISTS(SELECT 1 FROM `message` JOIN `thread` ON `thread`.`id` = `message`.`threadId` " +
                        "JOIN `deleted_character` ON `deleted_character`.`characterId` = `thread`.`characterId` " +
                        "WHERE `message`.`id` = NEW.`messageId` AND `thread`.`kind` = 'SINGLE') " +
                    "BEGIN SELECT RAISE(ABORT, 'chat deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_deleted_import_group_insert` BEFORE INSERT ON `import_batch_group` " +
                    "WHEN NEW.`targetCharacterId` IS NOT NULL AND EXISTS(SELECT 1 FROM `deleted_character` WHERE `characterId` = NEW.`targetCharacterId`) " +
                    "BEGIN SELECT RAISE(ABORT, 'role permanently deleted'); END",
                "CREATE TRIGGER IF NOT EXISTS `guard_deleted_import_source_insert` BEFORE INSERT ON `import_source_session` " +
                    "WHEN EXISTS(SELECT 1 FROM `deleted_character` WHERE `characterId` = NEW.`targetCharacterId`) " +
                    "BEGIN SELECT RAISE(ABORT, 'role permanently deleted'); END",
            )
            statements.forEach(db::execSQL)
        }

        private val ROLE_DELETION_GUARD_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                installRoleDeletionGuards(db)
            }
        }

        fun create(context: Context): PulsezDatabase =
            Room.databaseBuilder(context, PulsezDatabase::class.java, "pulsez.db")
                // v1→v2→v3→v4→v5→v6→v7 链式迁移必须全部注册；历史数据均由增量迁移保留。
                // 刻意不加 fallbackToDestructiveMigration：宁可迁移失败暴露问题，也不静默清空用户数据。
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .addCallback(ROLE_DELETION_GUARD_CALLBACK)
                .build()
    }
}
