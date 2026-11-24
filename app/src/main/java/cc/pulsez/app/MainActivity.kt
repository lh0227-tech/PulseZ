package cc.pulsez.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.pulsez.app.ui.AppRoot
import cc.pulsez.app.ui.theme.PulsezTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * 从系统分享 / 选中文字进来的内容，**Activity 级事件流**（v0.5.6）。
     *
     * 为什么不再用普通字段：`onCreate` 的 `setContent` 只跑一次，`onNewIntent` 只改普通字段时
     * Compose 观察不到 → 已在前台的 Activity 再分享**不同**文本会被丢弃。
     * 改成 `StateFlow` 后 `onNewIntent` 的写入会产生可观察变化，`AppRoot` 据此压入导入页并注入；
     * 文本去重仍由 `AppRoot` 的 `ShareConsumption.shouldInject` 负责（同一份文本只注入一次）。
     *
     * 注意 `StateFlow` 的相等去重：同一份文本再次分享**不会**重复发事件（既有取舍，见
     * `ShareConsumption` KDoc）；不同文本一定会发。`null` 表示本次 Intent 不是分享。
     */
    private val shareEvents = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动：先把本次 Intent 的分享文本写进事件流，`AppRoot` 的导航初始化会把导入页压在
        // 首页之上（返回手势回首页），而不是直接进导入页而没有返回栈。
        shareEvents.value = extractSharedText(intent)
        val container = (application as PulsezApp).container
        setContent {
            PulsezTheme {
                AppRoot(container = container, shareEvents = shareEvents)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 温启动再次分享（v0.5.6）：更新事件流 → AppRoot 观察到变化。
        // `setIntent` 是平台要求的配套动作（依据：Activity#setIntent 官方文档——
        // https://developer.android.google.cn/reference/android/app/Activity#setIntent(android.content.Intent)）：
        // `onNewIntent` 之后 `getIntent()` 仍返回旧 Intent，需用 `setIntent` 更新成新 Intent，
        // 否则本 Activity 因字体/语言/密度重建时会从旧 Intent 取出**上一次**分享
        // （旧备份会被重新解析）。文档原文：「Note that in onNewIntent, this method will
        // return the original Intent. You can use setIntent(Intent) to update it to the
        // new Intent.」
        setIntent(intent)
        shareEvents.value = extractSharedText(intent)
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}
