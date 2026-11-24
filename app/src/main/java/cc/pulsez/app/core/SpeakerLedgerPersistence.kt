package cc.pulsez.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 群聊轮换账本的持久化快照（缺陷 step 3、§5.6）。
 *
 * 旧实现里 `lastSpeakers` / `silentTurns` 只活在 `GroupChatViewModel` 的内存里：
 * 杀进程重进后，失败 / `[PASS]` 的轮次从历史里根本看不出来，冷落计数只能按"成功轮"重算，
 * 「2 轮不连任」与「连续 6 轮没人被轮到就强制主回」都会失真。
 *
 * @property lastSpeakers 最近主回角色 id，**新→旧**（最多 [GroupChatEngine.SPEAKER_COOLDOWN_TURNS] 个）
 * @property silentTurns 每个 characterId 的连续冷落轮数
 */
@Serializable
data class SpeakerLedgerSnapshot(
    val lastSpeakers: List<String> = emptyList(),
    val silentTurns: Map<String, Int> = emptyMap(),
)

/**
 * 账本 JSON 的编解码（纯逻辑，可在 JVM 单测里直接跑）。
 *
 * 容错原则：读到坏数据（不是 JSON / 字段类型不对）返回 null，由调用方回退到"从历史重建"，
 * 绝不让一条损坏的偏好文件拖垮群聊页。
 */
object GroupLedgerCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 序列化为 JSON 字符串。 */
    fun encode(snapshot: SpeakerLedgerSnapshot): String =
        json.encodeToString(SpeakerLedgerSnapshot.serializer(), snapshot)

    /** 反序列化；空串 / 坏数据返回 null。 */
    fun decode(raw: String?): SpeakerLedgerSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(SpeakerLedgerSnapshot.serializer(), raw)
        }.getOrNull()
    }
}

/**
 * 用持久化快照恢复账本（纯逻辑）。
 *
 * 规则：
 * - 快照里出现、但当前群里已经没有的 characterId 一律丢弃（成员被删/被移出群）；
 * - 快照里缺席的当前成员从 0 开始（新人入群不该继承别人记录）；
 * - `silentTurns` 负数（损坏数据）钳位到 0；`lastSpeakers` 去重并只保留冷却窗口长度；
 * - `snapshot == null`（从没存过 / 读取失败）时返回传入的成员原样 + 空 `lastSpeakers`，
 *   调用方据此走原来的"从历史重建"分支。
 */
object SpeakerLedgerRestore {

    data class Restored(
        val members: List<GroupMember>,
        val lastSpeakers: List<String>,
    )

    fun restore(members: List<GroupMember>, snapshot: SpeakerLedgerSnapshot?): Restored {
        if (snapshot == null) return Restored(members = members, lastSpeakers = emptyList())

        val knownIds = members.map { it.characterId }.toSet()
        val speakers = snapshot.lastSpeakers
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in knownIds }
            .distinct()
            .take(GroupChatEngine.SPEAKER_COOLDOWN_TURNS)
            .toList()

        val restoredMembers = members.map { member ->
            val saved = snapshot.silentTurns[member.characterId]
            member.copy(silentTurns = saved?.coerceAtLeast(0) ?: 0)
        }
        return Restored(members = restoredMembers, lastSpeakers = speakers)
    }

    /** 从当前成员/最近主回队列生成待持久化的快照。 */
    fun snapshot(members: List<GroupMember>, lastSpeakers: List<String>): SpeakerLedgerSnapshot =
        SpeakerLedgerSnapshot(
            lastSpeakers = lastSpeakers,
            silentTurns = members.associate { it.characterId to it.silentTurns.coerceAtLeast(0) },
        )
}
