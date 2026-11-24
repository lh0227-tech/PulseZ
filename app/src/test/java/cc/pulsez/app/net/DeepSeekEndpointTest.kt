package cc.pulsez.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 统一端点判定（`net/DeepSeekEndpoint.kt`）。
 *
 * 旧实现是 `contains("deepseek", ignoreCase = true)`，会把镜像/伪装域名误判成 DeepSeek 官方：
 * - `deepseek-proxy.example.com`（第三方代理，不认 `thinking` / `deepseek_web_search`）；
 * - `api.deepseek.com.evil.com`（伪装域名）。
 * 新规则只看 host 的域名边界，测试同时锁正例与反例。
 */
class DeepSeekEndpointTest {

    // ------------------------------------------------------------------ 正例

    @Test
    fun officialAndSubdomainsMatch() {
        assertTrue("裸主机名（用户常见填法）", isDeepSeekEndpoint("api.deepseek.com"))
        assertTrue(isDeepSeekEndpoint("https://api.deepseek.com/v1"))
        assertTrue("允许端口与完整 endpoint", isDeepSeekEndpoint("https://api.deepseek.com:443/v1/chat/completions"))
        assertTrue("忽略大小写", isDeepSeekEndpoint("HTTPS://API.DeepSeek.COM/v1"))
        assertTrue("子域名", isDeepSeekEndpoint("xxx.deepseek.com"))
        assertTrue(isDeepSeekEndpoint("https://xxx.deepseek.com/v1"))
        assertTrue("容忍首尾空白", isDeepSeekEndpoint("  https://api.deepseek.com/v1/  "))
    }

    // ------------------------------------------------------------------ 反例

    @Test
    fun mirrorAndLookalikeHostsDoNotMatch() {
        assertFalse("代理域名不是 DeepSeek 官方", isDeepSeekEndpoint("deepseek-proxy.example.com"))
        assertFalse(isDeepSeekEndpoint("https://deepseek-proxy.example.com/v1"))
        assertFalse("伪装域名：host 后缀是 evil.com", isDeepSeekEndpoint("api.deepseek.com.evil.com"))
        assertFalse(isDeepSeekEndpoint("https://api.deepseek.com.evil.com/v1"))
        assertFalse("路径里带 deepseek 不算", isDeepSeekEndpoint("https://example.com/deepseek/v1"))
        assertFalse("裸 deepseek.com 不是 API 域", isDeepSeekEndpoint("deepseek.com"))
        assertFalse(isDeepSeekEndpoint(""))
        assertFalse(isDeepSeekEndpoint("   "))
        assertFalse(isDeepSeekEndpoint("not a url deepseek"))
    }

    // ------------------------------------------------------------------ 内部请求改写

    @Test
    fun internalRequestDisablesThinkingOnlyOnDeepSeek() {
        val base = ChatRequest(
            model = "deepseek-flash",
            messages = listOf(ChatMessage(role = "user", content = "在吗")),
        )

        assertTrue(
            "DeepSeek 端点必须显式 thinking=disabled（不传 ≠ 关）",
            base.asInternalRequest("https://api.deepseek.com/v1").thinking == false,
        )
        assertNull(
            "非 DeepSeek 端点不发送 thinking 字段",
            base.asInternalRequest("https://api.siliconflow.cn/v1").thinking,
        )
        assertNull("空端点（未配置）也不发送", base.asInternalRequest("").thinking)
        assertTrue(
            "内部请求不允许把用户的思考偏好带进去",
            base.copy(thinking = true).asInternalRequest("https://api.deepseek.com").thinking == false,
        )
    }

    @Test
    fun internalThinkingForReturnsNullForBlankEndpoint() {
        assertNull(internalThinkingFor(""))
        assertEquals(false, internalThinkingFor("https://api.deepseek.com"))
    }
}
