package cc.pulsez.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 `BaseUrl.origin` 对语音输入地址的归一化及 ASR 地址拼接。
 *
 * DeepSeek 的 `/v1/tools/asr` 是根路径下的私有端点。聊天 base 可能是
 * `https://api.deepseek.com`、`.../v1`、`.../v1/`，也可能包含完整的
 * `.../v1/chat/completions` 路径。origin 应提取 `scheme://host[:port]`，避免拼出
 * `.../v1/v1/tools/asr`。
 */
class BaseUrlOriginTest {

    @Test
    fun `带 v1 的地址剥成 origin`() {
        assertEquals("https://api.deepseek.com", BaseUrl.origin("https://api.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com", BaseUrl.origin("https://api.deepseek.com/v1/"))
        assertEquals("https://api.deepseek.com", BaseUrl.origin("https://api.deepseek.com"))
        assertEquals("https://api.deepseek.com", BaseUrl.origin("https://api.deepseek.com/"))
    }

    @Test
    fun `省略协议时补 https`() {
        assertEquals("https://api.deepseek.com", BaseUrl.origin("api.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com", BaseUrl.origin("  api.deepseek.com  "))
    }

    @Test
    fun `粘贴完整 endpoint 也能剥干净`() {
        assertEquals(
            "https://api.deepseek.com",
            BaseUrl.origin("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com",
            BaseUrl.origin("https://api.deepseek.com/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com",
            BaseUrl.origin("https://api.deepseek.com/v1/embeddings"),
        )
    }

    @Test
    fun `保留非默认端口`() {
        assertEquals("http://127.0.0.1:8080", BaseUrl.origin("http://127.0.0.1:8080/v1"))
        assertEquals("http://127.0.0.1:8080", BaseUrl.origin("http://127.0.0.1:8080"))
    }

    @Test
    fun `路径前缀按根路径处理`() {
        // ASR 只对 DeepSeek 官方端点开放，自定义路径前缀不是要支持的场景；
        // 这里锁住行为：origin 取 scheme://host[:port]，不保留 path。
        assertEquals(
            "https://example.com",
            BaseUrl.origin("https://example.com/proxy/v1"),
        )
    }

    @Test
    fun `空串和非法地址返回空串`() {
        assertEquals("", BaseUrl.origin(""))
        assertEquals("", BaseUrl.origin("   "))
        assertEquals("", BaseUrl.origin("not a url"))
    }

    @Test
    fun `ASR 地址始终是 origin 加官方路径`() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            AsrClient.endpointUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            AsrClient.endpointUrl("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/asr",
            AsrClient.endpointUrl("api.deepseek.com/v1/"),
        )
        assertEquals("https://api.deepseek.com/v1/tools/asr", AsrClient.endpointUrl("https://api.deepseek.com/v1/chat/completions"))
    }
}
