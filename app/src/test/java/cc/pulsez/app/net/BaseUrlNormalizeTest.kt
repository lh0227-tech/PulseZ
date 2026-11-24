package cc.pulsez.app.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 `BaseUrl.normalize` 的地址归一化行为，尤其是 query、fragment 与接口路径同时存在时，
 * 请求路径仍按 URL 结构追加，而不是把接口后缀拼入 query 值。
 */
class BaseUrlNormalizeTest {

    // ------------------------------------------------------------ 五组反例

    @Test
    fun `E07 一 query 末尾斜线保留 端点后缀进路径`() {
        val input = "https://example.invalid/v1?route=foo/"
        assertEquals(
            "只操作 path，query 原样保留（旧实现会删掉 query 末尾的 /）",
            input,
            BaseUrl.normalize(input),
        )

        val url = requireUrl(BaseUrl.endpoint(input, "chat/completions"))
        assertEquals("/v1/chat/completions", url.encodedPath)
        assertEquals("route=foo/", url.encodedQuery)
    }

    @Test
    fun `E07 二 query 里的 models 不被当端点后缀剥掉`() {
        val input = "https://example.invalid/v1?route=/models"
        assertEquals(
            "端点后缀判断只看 path（旧实现会把 query 里的 /models 剥掉，变成 route=）",
            input,
            BaseUrl.normalize(input),
        )

        val url = requireUrl(BaseUrl.endpoint(input, "chat/completions"))
        assertEquals("/v1/chat/completions", url.encodedPath)
        assertEquals("route=/models", url.encodedQuery)
    }

    @Test
    fun `E07 三 粘贴完整 endpoint 带 query 只拼一次`() {
        val input = "https://example.invalid/v1/chat/completions?api-version=1"
        assertEquals(
            "归一化必须剥掉端点后缀但保留 query",
            "https://example.invalid/v1?api-version=1",
            BaseUrl.normalize(input),
        )

        val url = requireUrl(BaseUrl.endpoint(input, "chat/completions"))
        assertEquals("https://example.invalid/v1/chat/completions?api-version=1", url.toString())
        assertEquals("/v1/chat/completions", url.encodedPath)
        assertEquals("api-version=1", url.encodedQuery)
        val suffixOccurrences = Regex("/chat/completions").findAll(url.toString()).count()
        assertTrue(
            "接口后缀只能出现一次（旧实现会拼成 .../chat/completions/chat/completions）",
            suffixOccurrences == 1,
        )
    }

    @Test
    fun `E07 四 带 fragment 的完整 endpoint`() {
        val input = "https://example.invalid/v1/chat/completions#section"
        assertEquals(
            "fragment 丢弃，端点后缀按 path 剥掉",
            "https://example.invalid/v1",
            BaseUrl.normalize(input),
        )

        val url = requireUrl(BaseUrl.endpoint(input, "chat/completions"))
        assertEquals("https://example.invalid/v1/chat/completions", url.toString())
        assertEquals("/v1/chat/completions", url.encodedPath)
        assertNull("fragment 本来就不会随请求发送", url.fragment)
    }

    @Test
    fun `E07 五 已带 v1 和 query`() {
        val input = "https://example.invalid/v1?api-version=1"
        assertEquals(input, BaseUrl.normalize(input))
        assertEquals(
            "https://example.invalid/v1/chat/completions?api-version=1",
            BaseUrl.endpoint(input, "chat/completions"),
        )
    }

    @Test
    fun `E07 查询和片段同时存在 查询保留片段丢弃`() {
        assertEquals(
            "https://example.invalid/v1?api-version=1",
            BaseUrl.normalize("https://example.invalid/v1?api-version=1#frag"),
        )
        assertEquals(
            "https://example.invalid/v1",
            BaseUrl.normalize("https://example.invalid/v1#frag"),
        )
    }

    // ------------------------------------------------------------ 无协议历史值

    @Test
    fun `无协议输入归一化后仍无协议且保留 query`() {
        val normalized = BaseUrl.normalize("api.x.com/v1?api-version=1")
        assertEquals(
            "无协议输入的输出必须仍无协议（initial 缓存键语义不变）",
            "api.x.com/v1?api-version=1",
            normalized,
        )
        assertNull("归一化出口本身不补协议", normalized.toHttpUrlOrNull())

        val url = requireUrl(BaseUrl.endpoint(normalized, "chat/completions"))
        assertEquals("https://api.x.com/v1/chat/completions?api-version=1", url.toString())
        assertEquals("/v1/chat/completions", url.encodedPath)
        assertEquals("api-version=1", url.encodedQuery)
    }

    @Test
    fun `无协议粘贴完整 endpoint 带 query 也能归一化`() {
        assertEquals(
            "api.x.com/v1?api-version=1",
            BaseUrl.normalize("api.x.com/v1/chat/completions?api-version=1"),
        )
        assertEquals(
            "https://api.x.com/v1/chat/completions?api-version=1",
            BaseUrl.endpoint("api.x.com/v1/chat/completions?api-version=1", "chat/completions"),
        )
    }

    @Test
    fun `无协议历史值 404 补 v1 不把 v1 拼进 query`() {
        // 旧 withV1 对无协议串走字符串回退：`api.x.com?api-version=1` → `...?api-version=1/v1`。
        val base = BaseUrl.normalize("api.x.com?api-version=1")
        assertEquals("api.x.com?api-version=1", base)
        assertFalse(BaseUrl.isV1(base))

        val retried = BaseUrl.withV1(base)
        assertEquals("api.x.com/v1?api-version=1", retried)
        val url = requireUrl(BaseUrl.endpoint(retried, "chat/completions"))
        assertEquals("/v1/chat/completions", url.encodedPath)
        assertEquals("api-version=1", url.encodedQuery)
    }

    // ------------------------------------------------------------ isV1

    @Test
    fun `isV1 按 path 尾判 带 query 成立`() {
        assertTrue("带 query 的 /v1 必须判 true", BaseUrl.isV1("https://example.invalid/v1?x=1"))
        assertTrue(BaseUrl.isV1("https://example.invalid/v1"))
        assertTrue("path 尾部斜线不影响", BaseUrl.isV1("https://example.invalid/v1/"))
        assertTrue("fragment 不影响", BaseUrl.isV1("https://example.invalid/v1#frag"))
        assertTrue("无协议历史值同样按 path 判", BaseUrl.isV1("api.x.com/v1?api-version=1"))
    }

    @Test
    fun `isV1 完整 endpoint 归一化后成立`() {
        assertEquals(
            "https://example.invalid/v1?x=1",
            BaseUrl.normalize("https://example.invalid/v1/chat/completions?x=1"),
        )
        assertTrue(BaseUrl.isV1(BaseUrl.normalize("https://example.invalid/v1/chat/completions?x=1")))
    }

    @Test
    fun `isV1 不把 query 里的 v1 当 path`() {
        assertFalse("query 里出现 /v1 不是 path 尾判", BaseUrl.isV1("https://example.invalid?next=/v1"))
        assertFalse(BaseUrl.isV1("https://example.invalid"))
        assertFalse(BaseUrl.isV1(""))
    }

    // ------------------------------------------------------------ 404 重试链

    @Test
    fun `带 query 且已 v1 的地址 404 重试链不再产生错误路径`() {
        // 真实链路：normalize → initial → endpoint（首发）→ 404 → isV1 判 true → 不再 withV1。
        // 修前 isV1 字符串尾判为 false，withV1 对可解析地址虽正确（已修），但无协议
        // 历史值仍会把 /v1 拼进 query；修后 isV1 直接为 true → 同一 URL 的重复 POST 不再发生。
        val normalized = BaseUrl.normalize("https://example.invalid/v1?api-version=1")
        assertTrue("404 补 /v1 分支（LlmClient.kt:209/442）靠它短路", BaseUrl.isV1(normalized))
        assertEquals("已 /v1 时 withV1 必须原样返回", normalized, BaseUrl.withV1(normalized))
        assertEquals(
            "https://example.invalid/v1/chat/completions?api-version=1",
            BaseUrl.endpoint(normalized, "chat/completions"),
        )

        val legacy = BaseUrl.normalize("api.x.com/v1?api-version=1")
        assertTrue("无协议历史值同样短路", BaseUrl.isV1(legacy))
        assertEquals(legacy, BaseUrl.withV1(legacy))
        assertEquals(
            "https://api.x.com/v1/chat/completions?api-version=1",
            BaseUrl.endpoint(legacy, "chat/completions"),
        )
    }

    // ------------------------------------------------------------ 标准地址：逐字符一致（防回归）

    @Test
    fun `标准地址归一化输出与 v0_5_5 逐字符一致`() {
        // 这些断言值就是 （修复前）的实际输出，锁住「只修 query/fragment 输入」。
        assertEquals("https://api.x.com/v1", BaseUrl.normalize("https://api.x.com/v1"))
        assertEquals("https://api.x.com/v1", BaseUrl.normalize("https://api.x.com/v1/"))
        assertEquals("https://api.x.com", BaseUrl.normalize("https://api.x.com"))
        assertEquals("https://api.x.com", BaseUrl.normalize("https://api.x.com/"))
        assertEquals("https://api.x.com/v1", BaseUrl.normalize("https://api.x.com/v1/chat/completions"))
        assertEquals("https://api.x.com", BaseUrl.normalize("https://api.x.com/models"))
        assertEquals("https://api.x.com/v1", BaseUrl.normalize("https://api.x.com/v1/embeddings"))
        assertEquals("api.x.com/v1", BaseUrl.normalize("api.x.com/v1"))
        assertEquals("api.deepseek.com", BaseUrl.normalize("api.deepseek.com"))
        assertEquals("http://127.0.0.1:8080/v1", BaseUrl.normalize("http://127.0.0.1:8080/v1"))
        assertEquals("api.x.com/v1", BaseUrl.normalize("  api.x.com/v1  "))
        assertEquals("", BaseUrl.normalize(""))
        assertEquals("", BaseUrl.normalize("   "))
    }

    @Test
    fun `标准地址 endpoint 输出与 v0_5_5 逐字符一致`() {
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
        assertEquals(
            "https://api.x.com/chat/completions",
            BaseUrl.endpoint("https://api.x.com", "chat/completions"),
        )
    }

    // ------------------------------------------------------------ 解析失败回退旧行为

    @Test
    fun `解析失败回退旧字符串行为`() {
        // 只有协议头：旧 normalize 会削成 `https:` / `http:`（CredentialRules.originOf 依赖
        // 这种形状挡坏地址）。
        assertEquals("https:", BaseUrl.normalize("https://"))
        assertEquals("http:", BaseUrl.normalize("http://"))
        assertEquals("https:", BaseUrl.normalize("https:"))
        // 非 http(s) scheme：OkHttp 解析不了 → 保持旧行为。
        assertEquals("ws://api.example.com", BaseUrl.normalize("ws://api.example.com"))
        assertEquals("not a url", BaseUrl.normalize("not a url"))
    }

    @Test
    fun `host 名恰好以端点后缀结尾时不再被整串剥掉`() {
        // 旧实现整串 endsWith("/models")：`https://models`（host= models，无 path）被剥成 `https:`。
        // 只操作 path 后 host 不参与后缀判断（有意修复，见 BaseUrl.normalize KDoc）。
        assertEquals("https://models", BaseUrl.normalize("https://models"))
        assertEquals("https://chat/completions", BaseUrl.normalize("https://chat/completions"))
    }

    @Test
    fun `归一化幂等`() {
        val inputs = listOf(
            "https://example.invalid/v1?route=foo/",
            "https://example.invalid/v1?route=/models",
            "https://example.invalid/v1/chat/completions?api-version=1",
            "https://example.invalid/v1/chat/completions#section",
            "https://example.invalid/v1?api-version=1",
            "api.x.com/v1?api-version=1",
            "https://api.x.com/v1",
            "https://api.x.com",
        )
        for (input in inputs) {
            val once = BaseUrl.normalize(input)
            assertEquals("归一化必须幂等：$input", once, BaseUrl.normalize(once))
        }
    }

    /** 断言产物是可解析的绝对 URL，并把非空结果交给后续 path/query/fragment 断言。 */
    private fun requireUrl(url: String): HttpUrl =
        requireNotNull(url.toHttpUrlOrNull()) { "不是可解析的绝对 URL：$url" }
}
