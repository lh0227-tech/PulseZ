package cc.pulsez.app.data.group

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.pulsez.app.core.GroupLedgerCodec
import cc.pulsez.app.core.SpeakerLedgerSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val Context.groupLedgerStore by preferencesDataStore(name = "pulsez_group_ledger")

/**
 * 群聊轮换账本的 DataStore 持久化（缺陷）。
 *
 * 一个 threadId 一个 key，值是 [SpeakerLedgerSnapshot] 的 JSON（`lastSpeakers` + `silentTurns`）。
 * 选择 Preferences DataStore 而不是 Room：
 * - 账本是"体验修正参数"，不是用户数据，丢了最多冷落一轮，不需要迁移/事务；
 * - 工程内已有 `UsageStore` / `SettingsStore` 两处 DataStore 用法，不引入新依赖。
 *
 * **读写失败一律静默**（返回 null / 忽略异常）：账本坏了不能让群聊发不出消息；
 * 加载失败时 `GroupChatViewModel` 会回退到"从历史重建"（v0.2.0 的旧行为）。
 * 但协程取消必须继续向上传播，不能被"静默"吞掉。
 *
 * 依据Android 官方 DataStore 文档
 * https://developer.android.com/topic/libraries/architecture/datastore
 */
class GroupLedgerStore(context: Context) {

    private val store = context.applicationContext.groupLedgerStore

    /** 读取某个群的账本；没有记录 / 数据损坏 / IO 失败都返回 null。 */
    suspend fun load(threadId: String): SpeakerLedgerSnapshot? = try {
        store.data.first()[key(threadId)]?.let(GroupLedgerCodec::decode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** 写入账本；失败静默（下次成功写入会覆盖，最多损失一轮体验修正）。 */
    suspend fun save(threadId: String, snapshot: SpeakerLedgerSnapshot) {
        try {
            val encoded = GroupLedgerCodec.encode(snapshot)
            store.edit { preferences -> preferences[key(threadId)] = encoded }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 静默：账本写失败不影响聊天
        }
    }

    /**
     * 清空全部群聊账本（v0.5.2「清空所有数据」，`）。
     *
     * 与 [load]/[save] 的「静默」语义**故意不同**：清空所有数据是用户隐私承诺的一部分，
     * 写失败必须让上层看到（否则「清空」会谎报成功）。所以这里**不吞异常**，由
     * `ExportService.clearAll` 按「任一步异常向上抛」处理。
     *
     * 单次 `edit { clear() }`：一次清掉本文件全部键（按 threadId 的账本 + 将来新增的键）。
     */
    suspend fun clearAll() {
        store.edit { preferences -> preferences.clear() }
    }

    private fun key(threadId: String) = stringPreferencesKey("ledger_$threadId")
}
