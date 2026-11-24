package cc.pulsez.app.ui.config

import cc.pulsez.app.data.settings.Settings
import cc.pulsez.app.net.BaseUrl
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密钥保存三步协议。
 *
 * 纯 JVM：用函数类型注入的 slot + 假设置/假密文，在**每一步**注入失败点，断言：
 * - 失败一律返回 `FAILED`（不会 `saved=true`）；
 * - 失败后的持久态**读路径不可用**（不存在「新密钥配旧 host」/「hasKey 说谎」）；
 * - `BAD_ORIGIN` / `REQUIRE_NEW_KEY` 一个字段都不写；
 * - `SAVED` 只可能在 ④ 回读校验通过后出现。
 */
class CredentialSaveWorkflowTest {

    private companion object {
        const val DEEPSEEK = "https://api.deepseek.com"
        const val SILICONFLOW = "https://api.siliconflow.cn/v1"
        const val SECRET_NAME = "test_api_key"
        const val NEW_KEY = "test-key-B"
        const val OLD_KEY = "test-key-A"
    }

    private class Fixture {
        var secret: String? = null
        var hasKey = false
        var keyHost = ""
        var endpointBase = ""
        var endpointModel = ""
        var failReadSettings = false
        var failInvalidate = false
        var failWriteEndpoint = false
        var failWriteBound = false
        var failSecretWrite = false
        var invalidateCalls = 0
        var boundCalls = 0
        var endpointCalls = 0

        /** 回读时篡改 host：模拟第 ④ 步校验不一致（例如并发写入/持久化异常）。 */
        var tamperReadbackHost: String? = null

        /** 回读时篡改地址：模拟 D/A 分支写后回读不一致。 */
        var tamperReadbackEndpoint = false
        private var boundWritten = false

        val slot = CredentialSlot(
            secretName = SECRET_NAME,
            // 注意：必须从传进来的持久快照读（`it.hasChatKey` / `it.chatKeyHost`），
            // 不能直接读本 fixture 的字段——否则第 ④ 步回读校验会绕过快照、测不出问题。
            keyPresent = { it.hasChatKey },
            keyHost = { it.chatKeyHost },
            endpointBase = { it.chatBaseUrl },
            endpointModel = { it.chatModel },
            invalidateBinding = {
                invalidateCalls += 1
                if (failInvalidate) error("invalidate boom")
                hasKey = false
                keyHost = ""
            },
            writeEndpoint = { base, model ->
                endpointCalls += 1
                if (failWriteEndpoint) error("endpoint boom")
                endpointBase = base
                endpointModel = model
            },
            writeBoundEndpoint = { base, model, origin ->
                boundCalls += 1
                if (failWriteBound) error("bound boom")
                endpointBase = base
                endpointModel = model
                keyHost = origin
                hasKey = true
                boundWritten = true
            },
        )

        private fun snapshot(): Settings {
            val host = if (boundWritten && tamperReadbackHost != null) tamperReadbackHost!! else keyHost
            val base = if (tamperReadbackEndpoint) "$endpointBase-tampered" else endpointBase
            return Settings(
                chatBaseUrl = base,
                chatModel = endpointModel,
                hasChatKey = hasKey,
                chatKeyHost = host,
                embedBaseUrl = base,
                embedModel = endpointModel,
                hasEmbedKey = hasKey,
                embedKeyHost = host,
            )
        }

        val workflow = CredentialSaveWorkflow(
            readSettings = {
                if (failReadSettings) error("settings boom")
                snapshot()
            },
            writeSecret = { _, value ->
                if (failSecretWrite) error("secret boom")
                secret = value
            },
            removeSecret = { secret = null },
        )

        /** 生产读路径的等价判断：绑定成立 + 密文存在才算能用。 */
        fun usableByReadPath(): Boolean = resolveBinding(
            hasKey = hasKey,
            keyHost = keyHost,
            baseUrl = endpointBase,
            hasSecret = !secret.isNullOrBlank(),
        ) is KeyBinding.Usable

        fun save(baseUrl: String, keyInput: String): CredentialSaveResult = runBlocking {
            workflow.save(slot, baseUrl = baseUrl, model = "some-model", keyInput = keyInput)
        }
    }

    // ---------------------------------------------------------------- C：成功路径

    @Test
    fun `填新密钥成功 host 跟新地址 旧密文被替换`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            secret = OLD_KEY
        }
        assertEquals(CredentialSaveResult.SAVED, f.save(SILICONFLOW, NEW_KEY))
        assertTrue("保存后 hasKey=true", f.hasKey)
        assertEquals("host 必须跟新地址", "https://api.siliconflow.cn", f.keyHost)
        assertEquals("新密文写入", NEW_KEY, f.secret)
        assertEquals("地址写入", SILICONFLOW, f.endpointBase)
        assertEquals("失效动作恰好一次", 1, f.invalidateCalls)
        assertEquals("绑定写入恰好一次", 1, f.boundCalls)
        assertTrue("读路径可用", f.usableByReadPath())
    }

    @Test
    fun `从未配置密钥 填地址模型和密钥 一次成功`() {
        val f = Fixture()
        assertEquals(CredentialSaveResult.SAVED, f.save(SILICONFLOW, NEW_KEY))
        assertTrue(f.hasKey)
        assertEquals("https://api.siliconflow.cn", f.keyHost)
        assertEquals(NEW_KEY, f.secret)
        assertTrue(f.usableByReadPath())
    }

    // ---------------------------------------------------------------- 失败点：任一步失败 → 不可用

    @Test
    fun `第①步失效失败 不写新密钥 读路径不可用`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            secret = OLD_KEY
            failInvalidate = true
        }
        assertEquals(CredentialSaveResult.FAILED, f.save(SILICONFLOW, NEW_KEY))
        assertEquals("绑定写入不能发生", 0, f.boundCalls)
        assertNull("①失败时宁可删掉旧密文，也不让可能配错的密钥出网", f.secret)
        assertFalse("读路径必须不可用", f.usableByReadPath())
        assertEquals("地址不能被改", DEEPSEEK, f.endpointBase)
    }

    @Test
    fun `第②步写密文失败 绑定已失效 读路径不可用`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            secret = OLD_KEY
            failSecretWrite = true
        }
        assertEquals(CredentialSaveResult.FAILED, f.save(SILICONFLOW, NEW_KEY))
        assertFalse("①已把 hasKey 置 false", f.hasKey)
        assertEquals("host 已清空", "", f.keyHost)
        assertEquals("绑定写入不能发生", 0, f.boundCalls)
        assertFalse("读路径必须不可用", f.usableByReadPath())
    }

    @Test
    fun `第③步写绑定失败 回滚为不可用 不出现新密钥配旧 host`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            secret = OLD_KEY
            failWriteBound = true
        }
        assertEquals(CredentialSaveResult.FAILED, f.save(SILICONFLOW, NEW_KEY))
        assertNull("失败要删掉新密文", f.secret)
        assertFalse("回滚后 hasKey 必须 false", f.hasKey)
        assertEquals("回滚后 host 必须清空", "", f.keyHost)
        assertFalse("读路径必须不可用", f.usableByReadPath())
    }

    @Test
    fun `第④步回读校验不一致 回滚为不可用 不返回 saved`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            secret = OLD_KEY
            // ③ 写完后回读发现 host 不是刚写的 origin：必须当失败处理。
            tamperReadbackHost = "https://somebody-else.example.com"
        }
        assertEquals(CredentialSaveResult.FAILED, f.save(SILICONFLOW, NEW_KEY))
        assertNull("回读不一致要删密文", f.secret)
        assertFalse("回读不一致要置 hasKey=false", f.hasKey)
        assertEquals("回读不一致要清 host", "", f.keyHost)
        assertFalse("读路径必须不可用", f.usableByReadPath())
    }

    @Test
    fun `读设置本身失败 直接失败且不写`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            secret = OLD_KEY
            failReadSettings = true
        }
        assertEquals(CredentialSaveResult.FAILED, f.save(SILICONFLOW, NEW_KEY))
        assertEquals("一次写都不能发生", 0, f.invalidateCalls)
        assertEquals(OLD_KEY, f.secret)
    }

    // ---------------------------------------------------------------- B / M：拒绝时一个字段都不写

    @Test
    fun `换服务商留空密钥 拒绝且不写`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            endpointModel = "m"
            secret = OLD_KEY
        }
        assertEquals(CredentialSaveResult.REQUIRE_NEW_KEY, f.save(SILICONFLOW, ""))
        assertEquals("地址不能被改", DEEPSEEK, f.endpointBase)
        assertEquals("模型不能被改", "m", f.endpointModel)
        assertEquals("旧密钥保持不动", OLD_KEY, f.secret)
        assertTrue("旧绑定保持可用（只是没换成功）", f.hasKey)
        assertEquals(DEEPSEEK, f.keyHost)
        assertEquals("拒绝时不能有任何写入", 0, f.invalidateCalls)
        assertEquals(0, f.boundCalls)
        assertEquals(0, f.endpointCalls)
    }

    @Test
    fun `坏地址 拒绝且不写`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            endpointModel = "m"
            secret = OLD_KEY
        }
        listOf("", "   ", "https://").forEach { bad ->
            assertEquals("坏地址必须拒绝：`$bad`", CredentialSaveResult.BAD_ORIGIN, f.save(bad, NEW_KEY))
        }
        assertEquals(0, f.invalidateCalls)
        assertEquals(0, f.boundCalls)
        assertEquals(0, f.endpointCalls)
        assertEquals("旧密钥保持不动", OLD_KEY, f.secret)
    }

    // ---------------------------------------------------------------- A / D / K：合法的轻量写入

    @Test
    fun `同 origin 换 path 且密钥留空 只写地址模型`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            endpointModel = "old-model"
            secret = OLD_KEY
        }
        assertEquals(CredentialSaveResult.SAVED, f.save("$DEEPSEEK/v1", ""))
        assertEquals("$DEEPSEEK/v1", f.endpointBase)
        assertEquals("some-model", f.endpointModel)
        assertEquals("旧密文不能动", OLD_KEY, f.secret)
        assertEquals("旧 host 不能动", DEEPSEEK, f.keyHost)
        assertTrue("绑定仍可用", f.usableByReadPath())
        assertEquals("KeepOldKey 不能走三步协议", 0, f.invalidateCalls)
        assertEquals(0, f.boundCalls)
    }

    @Test
    fun `从未配置密钥 只写地址模型 不生成密文`() {
        val f = Fixture()
        assertEquals(CredentialSaveResult.SAVED, f.save(DEEPSEEK, ""))
        assertEquals(DEEPSEEK, f.endpointBase)
        assertFalse(f.hasKey)
        assertEquals("", f.keyHost)
        assertNull(f.secret)
        assertEquals(0, f.invalidateCalls)
        assertEquals(0, f.boundCalls)
    }

    @Test
    fun `只写地址模型失败 不假成功`() {
        val f = Fixture().apply { failWriteEndpoint = true }
        assertEquals(CredentialSaveResult.FAILED, f.save(DEEPSEEK, ""))
        assertFalse(f.hasKey)
        assertNull(f.secret)
    }

    @Test
    fun `只写地址模型 写后回读不一致也算失败`() {
        // D/A 分支没有密文可校验，但仍要回读确认「写下去的就是读回来的」，不能假成功。
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            secret = OLD_KEY
            tamperReadbackEndpoint = true
        }
        assertEquals(
            "回读地址不一致不能返回 saved",
            CredentialSaveResult.FAILED,
            f.save("$DEEPSEEK/v1", ""),
        )
    }

    // ---------------------------------------------------------------- ：保存时统一补 https
    //
    // 用户口径：保存时统一补 `https://`，`http://` 原样保留；
    // 场景 J「无协议地址可保存」不变。 的问题是保存成功但落盘值没 scheme，请求层拼 URL
    // 时 OkHttp 抛 IllegalArgumentException（`invalid base url`），聊天/找旧事全部失败。

    @Test
    fun `无协议地址 填新密钥路径 落盘补 https 且能构造请求 URL`() {
        val f = Fixture()
        assertEquals(
            "无协议地址必须仍能保存（场景 J 不变）",
            CredentialSaveResult.SAVED,
            f.save("api.deepseek.com/v1", NEW_KEY),
        )
        assertEquals("落盘值必须补 https", "https://api.deepseek.com/v1", f.endpointBase)
        // keyHost 本来就由 BaseUrl.origin 补 https 算出来，这里一并锁住绑定仍可用。
        assertEquals("https://api.deepseek.com", f.keyHost)
        assertTrue("读路径可用", f.usableByReadPath())
        assertEquals(NEW_KEY, f.secret)
        // 保存值 → 请求 URL：必须能被 OkHttp 解析（的验收点）。
        assertNotNull(
            "落盘值 + 路径必须产出可解析的绝对 URL",
            BaseUrl.endpoint(f.endpointBase, "chat/completions").toHttpUrlOrNull(),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            BaseUrl.endpoint(f.endpointBase, "chat/completions"),
        )
    }

    @Test
    fun `无协议地址 从未配置密钥路径 落盘补 https`() {
        val f = Fixture() // hasKey=false、密钥框空 → NoKeyYet，只写地址 + 模型
        assertEquals(
            CredentialSaveResult.SAVED,
            f.save("api.deepseek.com/v1", ""),
        )
        assertEquals("NoKeyYet 落盘值也必须补 https", "https://api.deepseek.com/v1", f.endpointBase)
        assertFalse("不能凭空生成密钥", f.hasKey)
        assertNull(f.secret)
        assertNotNull(
            BaseUrl.endpoint(f.endpointBase, "embeddings").toHttpUrlOrNull(),
        )
    }

    @Test
    fun `无协议地址 KeepOldKey 路径 落盘补 https 绑定仍可用`() {
        val f = Fixture().apply {
            hasKey = true
            keyHost = DEEPSEEK
            endpointBase = DEEPSEEK
            endpointModel = "old-model"
            secret = OLD_KEY
        }
        assertEquals(
            "无协议地址 + 同 origin + 密钥留空 = KeepOldKey，必须仍可保存",
            CredentialSaveResult.SAVED,
            f.save("api.deepseek.com/v1", ""),
        )
        assertEquals("落盘值必须补 https", "https://api.deepseek.com/v1", f.endpointBase)
        assertEquals("旧密钥不能动", OLD_KEY, f.secret)
        assertTrue("绑定仍可用", f.usableByReadPath())
        assertEquals("KeepOldKey 不能走三步协议", 0, f.invalidateCalls)
    }

    @Test
    fun `http 地址保存后原样保留 不强制升级 https`() {
        val f = Fixture()
        assertEquals(
            CredentialSaveResult.SAVED,
            f.save("http://127.0.0.1:8080/v1", NEW_KEY),
        )
        assertEquals("http:// 必须原样保留，不能升级成 https", "http://127.0.0.1:8080/v1", f.endpointBase)
        assertEquals("http://127.0.0.1:8080", f.keyHost)
        val url = BaseUrl.endpoint(f.endpointBase, "chat/completions")
        assertEquals("http", url.toHttpUrlOrNull()?.scheme)
    }

    @Test
    fun `最短的 http 地址也原样落盘`() {
        // 口径原文示例 `http://x`：方案判定只看是否含 `://`，不能再自作主张补 https。
        val f = Fixture()
        assertEquals(CredentialSaveResult.SAVED, f.save("http://x", ""))
        assertEquals("http://x", f.endpointBase)
        assertFalse("NoKeyYet 不写密钥绑定", f.hasKey)
        assertEquals("", f.keyHost)
    }
}
