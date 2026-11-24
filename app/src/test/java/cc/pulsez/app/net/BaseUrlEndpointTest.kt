package cc.pulsez.app.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 带查询 / 片段的地址构造接口 URL。
 *
 * 缺陷：保存流程接受带 query / fragment 的地址（如
 * `https://example.invalid/v1?api-version=1`），但 `BaseUrl.endpoint` 旧实现是
 * `base + "/" + path` 的**字符串直接追加**，接口后缀会拼进 query 值：
 * `.../v1?api-version=1/chat/completions`，实际请求 path 仍是 `/v1`。
 *
 * 修后行为（`net/BaseUrl.kt`）：
 * 1. [BaseUrl.endpoint]：可解析时用 `HttpUrl.newBuilder.addPathSegments` 把接口后缀
 * 追加到 **path 末尾（query 之前）**，保留 query，丢弃 fragment；解析失败回退旧字符串拼接。
 * 2. [BaseUrl.withV1]：同理把 `v1` 作为路径段追加（path 已以 `/v1` 结尾则原样），保留 query。
 * 3. 标准地址（无 query / fragment）输出与 逐字符一致（本文件用断言锁住，防回归）。
 *
 * OkHttp 4.12.0 `HttpUrl.Builder` 的
 * `addPathSegments` / `fragment(null)` 语义（本机用缓存里的 okhttp-4.12.0 实测：
 * path 追加在 query 之前、query 原样保留、fragment 丢弃）。
 */
class BaseUrlEndpointTest {

    // ------------------------------------------------------------ endpoint：带 query / fragment

    @Test
    fun `带查询的 base 接口后缀进路径 query 原样保留`() {
        val parsed = requireUrl(BaseUrl.endpoint("https://example.invalid/v1?api-version=1", "chat/completions"))

        assertEquals(
            "接口后缀必须成为 path（query 之前），不能被拼进 query 值",
            "https://example.invalid/v1/chat/completions?api-version=1",
            parsed.toString(),
        )
        assertEquals("/v1/chat/completions", parsed.encodedPath)
        assertEquals("api-version=1", parsed.query)
        assertNull(parsed.fragment)
    }

    @Test
    fun `片段被丢弃 接口后缀仍进路径`() {
        val parsed = requireUrl(BaseUrl.endpoint("https://example.invalid/v1#section-2", "embeddings"))

        assertEquals("fragment 本来就不会发送，不拼进请求地址", "https://example.invalid/v1/embeddings", parsed.toString())
        assertEquals("/v1/embeddings", parsed.encodedPath)
        assertNull("fragment 必须被丢弃", parsed.fragment)
    }

    @Test
    fun `查询和片段同时存在 查询保留片段丢弃`() {
        val parsed = requireUrl(BaseUrl.endpoint("https://example.invalid/v1?api-version=1#frag", "models"))

        assertEquals("https://example.invalid/v1/models?api-version=1", parsed.toString())
        assertEquals("/v1/models", parsed.encodedPath)
        assertEquals("api-version=1", parsed.query)
        assertNull(parsed.fragment)
    }

    @Test
    fun `无协议历史值带查询也按 URL 语义拼接`() {
        // 及更早可能原样落盘这类值；请求侧先补 https 再拼路径（防御不退化）。
        assertEquals(
            "https://example.invalid/v1/chat/completions?api-version=1",
            BaseUrl.endpoint("example.invalid/v1?api-version=1", "chat/completions"),
        )
    }

    @Test
    fun `initial 后拼 chat 保留租户 models 路径`() {
        val cases = listOf(
            "https://example.invalid/tenant/models/chat/completions" to
                "https://example.invalid/tenant/models/chat/completions",
            "https://example.invalid/tenant/models/chat/completions?api-version=1" to
                "https://example.invalid/tenant/models/chat/completions?api-version=1",
            "https://example.invalid/tenant/models/chat/completions/" to
                "https://example.invalid/tenant/models/chat/completions",
        )

        for ((raw, expected) in cases) {
            assertEquals(expected, BaseUrl.endpoint(BaseUrl.initial(raw), "chat/completions"))
        }
    }

    @Test
    fun `initial 后拼 embeddings 保留租户 models 路径`() {
        val raw = "https://example.invalid/tenant/models/embeddings?api-version=1"

        assertEquals(
            "https://example.invalid/tenant/models/embeddings?api-version=1",
            BaseUrl.endpoint(BaseUrl.initial(raw), "embeddings"),
        )
    }

    @Test
    fun `initial 后 v1 models 地址仍拼到 v1 下`() {
        val initial = BaseUrl.initial("https://example.invalid/v1/models")

        assertEquals("https://example.invalid/v1", initial)
        assertEquals(
            "https://example.invalid/v1/chat/completions",
            BaseUrl.endpoint(initial, "chat/completions"),
        )
    }

    @Test
    fun `http 本机地址带查询 保留 http 与 query`() {
        val parsed = requireUrl(BaseUrl.endpoint("http://127.0.0.1:8080/v1?api-version=1", "chat/completions"))

        assertEquals("http", parsed.scheme)
        assertEquals("/v1/chat/completions", parsed.encodedPath)
        assertEquals("api-version=1", parsed.query)
    }

    // ------------------------------------------------------------ withV1：query / fragment

    @Test
    fun `withV1 对带查询且 path 已以 v1 结尾的 base 原样返回`() {
        // 旧实现按字符串尾判：`...?api-version=1` 不以 `/v1` 结尾，会追加成
        // `...?api-version=1/v1`（把 /v1 拼进 query）。URL 感知后按 path 判断，原样返回。
        assertEquals(
            "https://example.invalid/v1?api-version=1",
            BaseUrl.withV1("https://example.invalid/v1?api-version=1"),
        )
    }

    @Test
    fun `withV1 把 v1 追加为路径段并保留查询`() {
        val parsed = requireUrl(BaseUrl.withV1("https://example.invalid?api-version=1"))

        assertEquals("https://example.invalid/v1?api-version=1", parsed.toString())
        assertEquals("/v1", parsed.encodedPath)
        assertEquals("api-version=1", parsed.query)
    }

    @Test
    fun `withV1 丢片段不丢查询`() {
        assertEquals(
            "https://example.invalid/v1?api-version=1",
            BaseUrl.withV1("https://example.invalid?api-version=1#frag"),
        )
        assertEquals("https://example.invalid/v1", BaseUrl.withV1("https://example.invalid#frag"))
    }

    @Test
    fun `404 补 v1 后的地址再拼接口 仍是路径在前查询在后`() {
        // 真实调用链（LlmClient / EmbeddingClient / ModelProbe）：base 404 → withV1 → endpoint。
        // 修前 withV1 会把 /v1 追加进 query，导致随后的 endpoint 拼出错误路径。
        val retriedBase = BaseUrl.withV1("https://example.invalid?api-version=1")
        val parsed = requireUrl(BaseUrl.endpoint(retriedBase, "chat/completions"))

        assertEquals("/v1/chat/completions", parsed.encodedPath)
        assertEquals("api-version=1", parsed.query)
        assertNull(parsed.fragment)
    }

    // ------------------------------------------------------------ 标准地址：逐字符一致（防回归）

    @Test
    fun `endpoint 标准地址（无查询片段）输出与 v0_5_4 逐字符一致`() {
        // 既有 BaseUrlSchemeTest 用例原样锁一遍： 只能是「带 query/fragment 时」的修正，
        // 不能顺带改写标准地址的输出。
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            BaseUrl.endpoint("https://api.x.com/v1", "chat/completions"),
        )
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            BaseUrl.endpoint("https://api.x.com/v1/", "chat/completions"),
        )
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            BaseUrl.endpoint("https://api.x.com/v1/chat/completions", "chat/completions"),
        )
        assertEquals("https://api.x.com/models", BaseUrl.endpoint("https://api.x.com", "models"))
        assertEquals(
            "https://api.x.com/v1/embeddings",
            BaseUrl.endpoint("api.x.com/v1", "embeddings"),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            BaseUrl.endpoint("http://127.0.0.1:8080/v1", "chat/completions"),
        )
    }

    @Test
    fun `withV1 标准地址输出与 v0_5_4 逐字符一致`() {
        // 生产调用点传进来的 base 都经过 BaseUrl.initial（= normalize，已去尾部 /），
        // 覆盖正是这些形态。
        assertEquals("https://api.x.com/v1", BaseUrl.withV1("https://api.x.com"))
        assertEquals("https://api.x.com/v1", BaseUrl.withV1("https://api.x.com/v1"))
        assertEquals("https://api.x.com/proxy/v1", BaseUrl.withV1("https://api.x.com/proxy"))
        assertEquals("http://127.0.0.1:8080/v1", BaseUrl.withV1("http://127.0.0.1:8080"))
        assertEquals("", BaseUrl.withV1(""))
    }

    @Test
    fun `withV1 无协议与非法串保持旧输出`() {
        // 后：无协议串会补 https 再解析并按 path 判断，但输出仍是原无协议写法
        // （缓存键语义不变），与本用例的 断言逐个一致；非法串仍走字符串回退。
        assertEquals("api.deepseek.com/v1", BaseUrl.withV1("api.deepseek.com"))
        assertEquals("api.deepseek.com/v1", BaseUrl.withV1("api.deepseek.com/v1"))
        assertEquals("not a url/v1", BaseUrl.withV1("not a url"))
    }

    @Test
    fun `endpoint 解析失败保持旧字符串拼接`() {
        // 自定义 scheme 解析不了（HttpUrl 只认 http/https）→ 回退旧的字符串拼接。
        assertEquals(
            "ws://api.example.com/chat/completions",
            BaseUrl.endpoint("ws://api.example.com", "chat/completions"),
        )
        assertEquals(
            "ws://api.example.com/chat/completions",
            BaseUrl.endpoint("ws://api.example.com/models", "chat/completions"),
        )
        // 空地址：旧行为是 `/path`（不可解析），不能凭空造出合法 URL。
        assertEquals("/chat/completions", BaseUrl.endpoint("", "chat/completions"))
        assertNull(BaseUrl.endpoint("", "chat/completions").toHttpUrlOrNull())
    }

    /** 断言产物是可解析的绝对 URL，并把非空结果交给后续 path/query/fragment 断言。 */
    private fun requireUrl(url: String): HttpUrl =
        requireNotNull(url.toHttpUrlOrNull()) { "不是可解析的绝对 URL：$url" }
}
