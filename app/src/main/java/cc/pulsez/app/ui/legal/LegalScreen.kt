package cc.pulsez.app.ui.legal

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 服务协议与隐私说明。
 *
 * 本地 HTML，飞行模式可打开；不联网、不加载远程脚本。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LegalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val html = remember(context) {
        runCatching {
            context.resources.openRawResource(cc.pulsez.app.R.raw.agreement)
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("<p>暂时打不开协议内容，请关掉应用再打开试试。</p>")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "服务协议 · 隐私说明",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
        )
    }
}
