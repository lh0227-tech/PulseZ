package cc.pulsez.app.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 地址协议归一化。
 *
 * 规则如下：
 * - 保存时统一补 `https://`（`http://` 原样保留，不强制升级）；
 * - 请求侧对**历史遗留**的无协议值做防御性兼容（ 及更早会原样落盘）。
 *
 * 这里锁三件事：
 * 1. [BaseUrl.withScheme] 的纯函数行为（含 `://` 原样、否则补 https、trim 空串）；
 * 2. [BaseUrl.endpoint] 对无协议 base 拼出的请求 URL 能被 OkHttp 解析
 * （`toHttpUrlOrNull` 非空；OkHttp `Request.Builder.url(String)` 要求绝对 URL，
 * 无 scheme 抛 `IllegalArgumentException`）；
 * 3. [BaseUrl.normalize] / [BaseUrl.initial] 的**既有语义不变**（缓存键仍是用户填的归一化值，
 * 防御只加在出口；避免引入第二次数据迁移）。
 */
class BaseUrlSchemeTest {

    // ---------------------------------------------------------------- withScheme

    @Test
    fun `无协议地址补 https`() {
        assertEquals("https://api.deepseek.com/v1", BaseUrl.withScheme("api.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com", BaseUrl.withScheme("api.deepseek.com"))
    }

    @Test
    fun `已带协议的地址原样保留 http 不升级`() {
        assertEquals("https://api.deepseek.com/v1", BaseUrl.withScheme("https://api.deepseek.com/v1"))
        // `http://` 原样保留，不强制升级为 https。
        assertEquals("http://127.0.0.1:8080/v1", BaseUrl.withScheme("http://127.0.0.1:8080/v1"))
        assertEquals("http://api.example.com", BaseUrl.withScheme("http://api.example.com"))
        // 自定义 scheme 也按「已有协议」处理，不硬塞 https。
        assertEquals("ws://api.example.com", BaseUrl.withScheme("ws://api.example.com"))
    }

    @Test
    fun `两端空白被去掉 空地址返回空串`() {
        assertEquals("https://api.deepseek.com/v1", BaseUrl.withScheme("  api.deepseek.com/v1  "))
        assertEquals("https://api.deepseek.com", BaseUrl.withScheme("https://api.deepseek.com\n"))
        assertEquals("", BaseUrl.withScheme(""))
        assertEquals("", BaseUrl.withScheme("   "))
    }

    @Test
    fun `重复调用幂等`() {
        val once = BaseUrl.withScheme("api.x.com/v1")
        assertEquals(once, BaseUrl.withScheme(once))
    }

    // ---------------------------------------------------------------- endpoint：请求侧防御

    @Test
    fun `无协议 base 拼出的请求 URL 可被 OkHttp 解析`() {
        val url = BaseUrl.endpoint("api.x.com/v1", "chat/completions")
        assertEquals("https://api.x.com/v1/chat/completions", url)
        assertNotNull("无协议 base 必须产出可解析的绝对 URL", url.toHttpUrlOrNull())
    }

    @Test
    fun `历史遗留无协议值经 initial 后仍能产出可解析 URL`() {
        // 及更早保存的历史值就长这样：normalize/initial 保持原样（缓存键语义不变），
        // 但真正出网的 endpoint 必须补 scheme。找旧事（embeddings）与探测（models）同一条路。
        // 用不会与其它用例撞车的域名：`BaseUrl` 的 /v1 解析缓存是进程级（`resolvedBases`）。
        val legacy = BaseUrl.initial("legacy-scheme.example.com/v1/")
        assertEquals("legacy-scheme.example.com/v1", legacy)
        assertNull("归一化出口本身不补协议（避免改缓存键语义）", legacy.toHttpUrlOrNull())

        val chat = BaseUrl.endpoint(legacy, "chat/completions")
        assertEquals("https://legacy-scheme.example.com/v1/chat/completions", chat)
        assertNotNull(chat.toHttpUrlOrNull())

        val embeds = BaseUrl.endpoint(legacy, "embeddings")
        assertEquals("https://legacy-scheme.example.com/v1/embeddings", embeds)
        assertNotNull(embeds.toHttpUrlOrNull())

        val models = BaseUrl.endpoint(legacy, "models")
        assertEquals("https://legacy-scheme.example.com/v1/models", models)
        assertNotNull(models.toHttpUrlOrNull())
    }

    @Test
    fun `带协议的 base 拼接结果与旧行为一致`() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            BaseUrl.endpoint("https://api.x.com/v1", "chat/completions"),
        )
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            BaseUrl.endpoint("https://api.x.com/v1/", "chat/completions"),
        )
        // 粘贴整条 endpoint 仍会被剥掉后缀（既有行为，不能因补协议而退化）。
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            BaseUrl.endpoint("https://api.x.com/v1/chat/completions", "chat/completions"),
        )
    }

    @Test
    fun `http base 拼接保持 http 不升级`() {
        val url = BaseUrl.endpoint("http://127.0.0.1:8080/v1", "chat/completions")
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", url)
        assertEquals("http", url.toHttpUrlOrNull()?.scheme)
    }

    @Test
    fun `空 base 仍产出不可解析串 交给调用方按地址无效处理`() {
        // 既有行为：空地址在 client 构造时已短路成 `base url is blank`；这里只锁
        // 「不要凭空造出一个看起来合法的 URL」，避免把空配置伪装成可请求。
        assertEquals("/chat/completions", BaseUrl.endpoint("", "chat/completions"))
        assertNull(BaseUrl.endpoint("", "chat/completions").toHttpUrlOrNull())
    }
}
