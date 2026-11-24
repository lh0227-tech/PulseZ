package cc.pulsez.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream

/**
 * 「取最新版本清单」的可注入接口：生产实现是 [VersionChecker]，测试用假实现验证节流/比对。
 *
 * 返回 null = 所有来源都失败（调用方静默处理，或手动检查时给人话）。
 */
fun interface ManifestFetcher {
    suspend fun fetchLatest(): VersionManifest?
}

/**
 * 版本清单检查器：按 [sources] 顺序降级，任意一个成功即用；全部失败返回 null。
 *
 * 实现要点：
 * - 独立 OkHttp 客户端（[UpdateConfig.newClient]）：短超时、不自动重试、不装拦截器
 *   （不复用 `net/HttpFactory` 的流式全局客户端）；
 * - 只 GET 静态 JSON：不带 `Authorization`、不带 cookie、不带 query、不设自定义 UA
 *   （OkHttp 默认 UA 只含库名/版本，不含 App/设备标识；测试里断言）；
 * - 响应体限长（[UpdateConfig.MAX_MANIFEST_BYTES]）：超长直接当这一源失败；
 * - 不打印 URL 内容/清单内容（release 也不打印）；
 * - 每个源最多请求 1 次，不重试；两个源都失败 → null（静默）。
 *
 * 依据「降级逻辑」、§4.1「请求约束」、§6.4「更新源宕机」。
 */
class VersionChecker(
    private val sources: List<UpdateSource>,
    private val client: OkHttpClient = UpdateConfig.newClient(),
) : ManifestFetcher {

    override suspend fun fetchLatest(): VersionManifest? =
        withContext<VersionManifest?>(Dispatchers.IO) {
            for (source in sources) {
                // 单个源异常（IO/解析/超时）只算这一源失败，继续降级；异常一律不外抛。
                val manifest = runCatching { fetchOne(source) }.getOrNull()
                if (manifest != null) return@withContext manifest
            }
            null
        }

    /** 请求一个源；任何失败返回 null。 */
    private fun fetchOne(source: UpdateSource): VersionManifest? {
        val url: HttpUrl = source.url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // 让中间缓存别给旧清单（源站自己的 Cache-Control 仍生效；不加随机 query，
            // 避免破坏 CDN 缓存）。已带 ETag 的设备可继续用 304 逻辑，本轮不做。
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            // 快速路径：服务端声明了长度且超限，直接拒绝，不读正文。
            if (body.contentLength() > UpdateConfig.MAX_MANIFEST_BYTES) return null
            val text = readBodyCapped(body) ?: return null
            return VersionManifest.parse(text)
        }
    }

    /**
     * 限长读取响应体（UTF-8 文本）；超过 [UpdateConfig.MAX_MANIFEST_BYTES] 返回 null。
     *
     * 不直接用 `body.string()`：那会先把整段读进内存。这里分块读、超限立刻停。
     * 不用 `InputStream.readNBytes`：它是 Java 9 API，Android 上要 API 33 才有（minSdk 31 会崩）。
     */
    private fun readBodyCapped(body: ResponseBody): String? {
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > UpdateConfig.MAX_MANIFEST_BYTES) return null
                output.write(chunk, 0, read)
            }
            return output.toString("UTF-8")
        }
    }
}
