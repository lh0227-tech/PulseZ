package cc.pulsez.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.pulsez.app.core.ConsolidateResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 导入偏好专用的 Preferences DataStore。
 *
 * **独立文件**（`pulsez_import`，不与 `pulsez_settings` 共用）：导入偏好是「上一次导入在哪、
 * 整理到第几片」这类过程状态，和用户设置的生命周期/清理时机不同；混在一个文件里以后任何一边
 * 清空/迁移都可能误伤另一边。依据（`ImportPrefs` 独立 DataStore）。
 */
private val Context.importPrefsStore by preferencesDataStore(name = "pulsez_import")

/**
 * 上一次（或正在进行）的导入指纹（「续导」）。
 *
 * 指纹口径 = ZIP 文件名 + 文件字节数（§6：轻量、可用）：同一文件再选一次即可匹配，
 * 确认屏据此提示「上次导入过同一个文件」→ [继续补完]（复用 TA）/ [重新导入一份]（新 TA）。
 *
 * @property zipName ZIP 文件名（不含内容，隐私安全）
 * @property zipSize ZIP 字节数
 * @property characterId 上次新建的 TA；续导靠它复用（配合 `data.repo.ImportRepository` 的
 *   `ImportTarget.NewCharacter.resumeCharacterId`）
 * @property importedAt 上次导入的时间戳（毫秒）
 */
data class LastImport(
    val zipName: String,
    val zipSize: Long,
    val characterId: String,
    val importedAt: Long,
)

/**
 * 记忆整理的续跑进度（「整理续跑：重跑只处理未完成分片，不重复记账」）。
 *
 * ## 为什么是「分片索引集合」而不是「已尝试片数」
 * 旧口径按「已尝试」推进：失败片也计入 done，于是「全部片都尝试过」就清空进度；但失败片让
 * 「已整理」标记写不下去，下次点「整理往事」只能从第 0 片重跑——**已经成功的片会再次调用模型、
 * 再次记账**，违反 ` 「不重复收费」。改成索引集合后：
 * - [doneShards] 中的分片**绝不会**再调用模型（续跑直接跳过）；
 * - [failedShards] 中的分片保留在断点里，下次续跑优先重试；
 * - 只有全部分片都进 [doneShards] 才算整段完成（由调用方清进度 + 写「已整理」标记）。
 *
 * 只在 `(threadId)` 一层记一份：同一时刻只整理一个 TA（面板/导入各一条路径，状态机互斥）。
 *
 * @property threadId 目标会话
 * @property doneShards 已**成功**完成的分片索引（0 起；含续跑前就完成的片）
 * @property failedShards 尝试过但没拿到模型结果的分片索引（可重试；与 [doneShards] 不相交）
 * @property totalShards 算这份进度时的总分片数（= 当时范围内消息算出的片数）
 */
data class Consolidation(
    val threadId: String,
    val doneShards: Set<Int>,
    val failedShards: Set<Int>,
    val totalShards: Int,
) {

    /** 成功完成的分片数（进度显示口径：`doneCount / totalShards`；越界脏数据在 decode 阶段已过滤）。 */
    val doneCount: Int
        get() = doneShards.size

    /** 是否还有没完成的分片（有进度才需要续跑；全部完成/无进度时 [ImportPrefs.consolidation] 为 null）。 */
    val hasRemaining: Boolean
        get() = totalShards > 0 && doneShards.size < totalShards
}

/**
 * 导入偏好的读写。全部离线，Robolectric 可测；不涉及 API Key / 正文。
 *
 * 只存「过程状态」，不存任何聊天正文、标题、ZIP 原文（隐私收敛）。
 * 依据（`lastImport` / `consolidation`）、§6（续导指纹、整理续跑）。
 *
 * 键名是**稳定契约**（导入 UI）与 （记忆整理）跨版本读同一份文件，不要改名。
 */
class ImportPrefs(context: Context) {

    private val store = context.importPrefsStore

    /**
     * 上一次导入指纹；没有导入过（或已被 [clearLastImport] 清掉）时为 null。
     *
     * 以 `character_id` 作为「一整条记录存在」的锚：其它键缺省时给空/0 兜底，
     * 避免半个键组合被当成一条有效记录。
     */
    val lastImport: Flow<LastImport?> = store.data.map { prefs ->
        val characterId = prefs[Keys.LAST_IMPORT_CHARACTER_ID] ?: return@map null
        LastImport(
            zipName = prefs[Keys.LAST_IMPORT_ZIP_NAME].orEmpty(),
            zipSize = prefs[Keys.LAST_IMPORT_ZIP_SIZE] ?: 0L,
            characterId = characterId,
            importedAt = prefs[Keys.LAST_IMPORT_IMPORTED_AT] ?: 0L,
        )
    }

    /**
     * 记下本次导入指纹（写入是原子的）。
     *
     * 与「撤销」的关系：撤销清指纹由调用方用 [clearLastImport] 显式做（只清掉属于该 TA 的那条）。
     */
    suspend fun setLastImport(last: LastImport) {
        store.edit {
            it[Keys.LAST_IMPORT_ZIP_NAME] = last.zipName
            it[Keys.LAST_IMPORT_ZIP_SIZE] = last.zipSize
            it[Keys.LAST_IMPORT_CHARACTER_ID] = last.characterId
            it[Keys.LAST_IMPORT_IMPORTED_AT] = last.importedAt
        }
    }

    /** [setLastImport] 的便捷重载（调用方拿到的是散字段时不用先拼 data class）。 */
    suspend fun setLastImport(
        zipName: String,
        zipSize: Long,
        characterId: String,
        importedAt: Long = System.currentTimeMillis(),
    ) {
        setLastImport(LastImport(zipName, zipSize, characterId, importedAt))
    }

    /**
     * 清掉上次导入指纹——**只在该指纹属于 [characterId] 时清**。
     *
     * 为什么带条件：撤销的是「这次新建的 TA」；如果期间又导入了另一份 ZIP，指纹已经换成新 TA，
     * 撤销旧 TA 不能误清新 TA 的续导线索（不动用户已有数据）。
     */
    suspend fun clearLastImport(characterId: String) {
        store.edit { prefs ->
            if (prefs[Keys.LAST_IMPORT_CHARACTER_ID] == characterId) {
                prefs.remove(Keys.LAST_IMPORT_ZIP_NAME)
                prefs.remove(Keys.LAST_IMPORT_ZIP_SIZE)
                prefs.remove(Keys.LAST_IMPORT_CHARACTER_ID)
                prefs.remove(Keys.LAST_IMPORT_IMPORTED_AT)
            }
        }
    }

    /**
     * 记忆整理续跑进度；**只有「还有没完成的分片」时才非空**。
     *
     * 语义：
     * - 没在整理 / 已全部完成（被 [clearConsolidation] 清掉）→ null；
     * - 解不出/脏数据（[decodeConsolidation] 返回 null）→ null（当没有断点，从头整理）；
     * - 有未完成片（含失败片）→ 非空，[Consolidation.doneCount] 是进度分子。
     */
    val consolidation: Flow<Consolidation?> = store.data.map { prefs ->
        decodeConsolidation(prefs[Keys.CONSOLIDATION_STATE])?.takeIf { it.hasRemaining }
    }

    /**
     * 记下整理进度（每片后写一次；分片数由调用方按 40k token 预算算好）。
     *
     * 写入是**整体覆盖**：进度是本机过程状态，没有合并语义；写失败（DataStore 抖动）由调用方
     * `runCatching` 兜住，不能中断已经花钱的整理。
     *
     * `totalShards <= 0` 等价于「没有可续跑的进度」，直接清掉对应 thread 的旧记录，
     * 避免写一条永远不会被消费的脏数据。
     */
    suspend fun setConsolidation(consolidation: Consolidation) {
        if (consolidation.threadId.isBlank()) return
        if (consolidation.totalShards <= 0) {
            clearConsolidation(consolidation.threadId)
            return
        }
        store.edit { it[Keys.CONSOLIDATION_STATE] = encodeConsolidation(consolidation) }
    }

    /**
     * 清掉整理进度——**只在该进度属于 [threadId] 时清**。
     *
     * 同 [clearLastImport]：另一个 TA 的整理进度不能被误清。解不出的脏数据不在此清
     * （无法确认归属；[consolidation] 已把它当 null，不影响正确性）。
     */
    suspend fun clearConsolidation(threadId: String) {
        if (threadId.isBlank()) return
        store.edit { prefs ->
            val current = decodeConsolidation(prefs[Keys.CONSOLIDATION_STATE])
            if (current?.threadId == threadId) {
                prefs.remove(Keys.CONSOLIDATION_STATE)
            }
        }
    }

    /**
     * 已经「整段整理完」的 thread 集合（` 「不重复收费」、§6「整理续跑」）。
     *
     * 为什么需要这个标记：[clearConsolidation] 只在整段跑完时清分片进度；而导入往事
     * （`batonReason="IMPORT"` 的 SEALED 段）永不删除，光看「有没有导入段」会让记忆面板
     * 一直显示「整理往事」入口——用户再点一次会**从 0 重跑全部分片**（重复花钱、可能重复
     * 抽取记忆）。标记后入口收起；只有全部分片成功且未取消才写（见 [isFullyConsolidated]）。
     *
     * 键名 `consolidated_threads` 是稳定契约：UI / 数据层跨版本读同一份文件，不要改名。
     * 按 threadId 记：撤销导入只是软删 TA，标记随 TA 不可见，可以接受（无需清理）。
     */
    val consolidatedThreads: Flow<Set<String>> = store.data.map { prefs ->
        prefs[Keys.CONSOLIDATED_THREADS].orEmpty()
    }

    /**
     * 记下「这个 thread 的导入往事已经整段整理完成」。
     *
     * **只在 [isFullyConsolidated] 为 true 时调用**；失败片/取消不标记——进度已在
     * [consolidation] 里，下次从断点续跑（§6）。
     */
    suspend fun markConsolidated(threadId: String) {
        if (threadId.isBlank()) return
        store.edit { prefs ->
            prefs[Keys.CONSOLIDATED_THREADS] =
                prefs[Keys.CONSOLIDATED_THREADS].orEmpty() + threadId
        }
    }

    /** 撤销「已整理」标记（按 threadId；只删这一个，别的 TA 不受影响）。 */
    suspend fun clearConsolidated(threadId: String) {
        if (threadId.isBlank()) return
        store.edit { prefs ->
            val remaining = prefs[Keys.CONSOLIDATED_THREADS].orEmpty() - threadId
            if (remaining.isEmpty()) {
                prefs.remove(Keys.CONSOLIDATED_THREADS)
            } else {
                prefs[Keys.CONSOLIDATED_THREADS] = remaining
            }
        }
    }

    /**
     * 清空本文件里的全部导入偏好（含「已整理」标记：`clear()` 清全部键）。
     *
     * 正常 App 流程不要用（会丢「继续上次导入」线索）；**唯一例外是「清空所有数据」**——
     * 用户在设置里主动清空全部数据时，导入偏好必须一起清掉（v0.5.2，
     * `上次导入的线索、整理进度、「已整理」标记都属于用户的
     * 「会删」清单）。这是该方法的**唯一允许的生产调用**。
     *
     * 另外给单元测试在 `@Before` 里复位用（Robolectric 同一进程内 DataStore 按文件名是单例，
     * 用例间共享同一份文件，参照 `SettingsStoreTest` 用 `SettingsStore.update { it.clear() }` 复位）。
     */
    suspend fun clearAll() {
        store.edit { it.clear() }
    }

    /** 稳定键名（跨版本契约 都读这一份）。*/
    private object Keys {
        val LAST_IMPORT_ZIP_NAME = stringPreferencesKey("last_import_zip_name")
        val LAST_IMPORT_ZIP_SIZE = longPreferencesKey("last_import_zip_size")
        val LAST_IMPORT_CHARACTER_ID = stringPreferencesKey("last_import_character_id")
        val LAST_IMPORT_IMPORTED_AT = longPreferencesKey("last_import_imported_at")

        /**
         * 整理续跑进度的稳定编码（单键；v0.4 尚未发布，无历史键迁移负担）。
         *
         * 旧实现是 3 个键（thread_id / shard_done / total_shards），口径是「已尝试片数」，
         * 无法表达「哪些片成功、哪些片失败」→ 失败片无法精确续跑（review）。本键替换旧三键。
         */
        val CONSOLIDATION_STATE = stringPreferencesKey("consolidation_state")
        val CONSOLIDATED_THREADS = stringSetPreferencesKey("consolidated_threads")
    }
}

// ---------------------------------------------------------------------- 进度编码（纯函数）

/**
 * 整理进度编码的版本前缀。
 *
 * 稳定契约：将来若改格式，改成 `v2` 并让 [decodeConsolidation] 兼容旧版；**不要**改 `v1` 的字段顺序。
 */
internal const val CONSOLIDATION_ENCODING_VERSION: String = "v1"

/**
 * 分片索引的合理上限（防御脏数据/溢出）。
 *
 * 40000 token/片 × 100000 片 = 40 亿 token，远超「ZIP ≤300 MiB」能容纳的对话量
 * （` 限额）；超过这个数的编码一律当成脏数据丢弃。
 */
internal const val MAX_CONSOLIDATION_SHARDS: Int = 100_000

/**
 * 把整理进度编码成**稳定字符串**（Preferences DataStore 里只存这一个值）。
 *
 * 格式：`v1|<total>|<done 逗号分隔>|<failed 逗号分隔>|<threadId>`
 * - 分片索引按升序输出（同一份进度编码结果确定，便于断言/排查）；
 * - 越界索引（`<0` 或 `>=total`）不写出；[Consolidation.failedShards] 与 done 重叠的也不写出；
 * - `threadId` 放在**最后**并用 `split(limit = 5)` 解析，即使将来 id 里出现 `|` 也不会截断。
 *
 * 依据 「进度不要只存已尝试片数，改成已成功片索引集合」；
 * 「整理续跑：重跑只处理未完成分片，不重复记账」。
 */
internal fun encodeConsolidation(state: Consolidation): String {
    val done = state.doneShards
        .filter { it in 0 until state.totalShards }
        .sorted()
        .joinToString(",")
    val failed = state.failedShards
        .filter { it in 0 until state.totalShards && it !in state.doneShards }
        .sorted()
        .joinToString(",")
    return "${CONSOLIDATION_ENCODING_VERSION}|${state.totalShards}|$done|$failed|${state.threadId}"
}

/**
 * [encodeConsolidation] 的逆函数；**解不出/脏数据一律返回 null**（当没有断点）。
 *
 * 容错口径（review 要求「含脏数据/空集/total 边界」）：
 * - null / 空串 / 版本不符 / 字段数不对 → null；
 * - total 非整数、`<=0`、`> [MAX_CONSOLIDATION_SHARDS]` → null；
 * - threadId 为空 → null；
 * - done/failed 里的非法项（非整数、负数、越界）**逐项丢弃**，不整条作废；
 * - done 与 failed 重叠时以 done 为准（成功过的片不能被当成失败重跑）。
 *
 * 注意：全部完成（`done.size == total`）仍然能解出来，由 [ImportPrefs.consolidation] 的
 * `hasRemaining` 过滤成「无进度」——编码层只负责如实还原。
 */
internal fun decodeConsolidation(raw: String?): Consolidation? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split('|', limit = 5)
    if (parts.size != 5) return null
    if (parts[0] != CONSOLIDATION_ENCODING_VERSION) return null
    val total = parts[1].trim().toIntOrNull() ?: return null
    if (total <= 0 || total > MAX_CONSOLIDATION_SHARDS) return null
    val threadId = parts[4]
    if (threadId.isBlank()) return null
    val done = parseShardIndexes(parts[2], total)
    val failed = parseShardIndexes(parts[3], total) - done
    return Consolidation(
        threadId = threadId,
        doneShards = done,
        failedShards = failed,
        totalShards = total,
    )
}

/** 逗号分隔的分片索引 → 集合；非法/越界项逐项丢弃（脏数据不整条作废）。 */
private fun parseShardIndexes(csv: String, total: Int): Set<Int> {
    if (csv.isBlank()) return emptySet()
    return csv.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 0 until total }
        .toSet()
}

/**
 * 一次整理是否「整段跑完」——只有这种情况才写「已整理」标记。
 *
 * - `cancelled`：用户点停，保留断点，下次续跑（§6「整理续跑」）；
 * - `failed > 0`：本次有分片没拿到模型结果，断点已保留（失败片在 [Consolidation.failedShards]）；
 * - `shardsTotal == 0`：范围内没有可整理的内容 → **不标记**（建议）。
 *   标成「已整理」会藏掉记忆面板的入口，而面板路径只支持 90 天；全部早于 90 天的档案
 *   就再也没有入口整理全部范围。调用方会回退到 ALL 再试一次，仍 0 时给「没有可整理的旧事」提示；
 * - `shardsDone < shardsTotal`：没跑完整段（取消之外理论上不发生，兜底不给标记）。
 *
 * 依据 要求「只有 `!cancelled && failed == 0 && done == total` 才 mark」。
 */
internal fun ConsolidateResult.isFullyConsolidated(): Boolean =
    !cancelled && failed == 0 && shardsTotal > 0 && shardsDone >= shardsTotal
