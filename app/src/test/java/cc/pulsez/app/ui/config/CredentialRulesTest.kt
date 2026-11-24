package cc.pulsez.app.ui.config

import cc.pulsez.app.net.BaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密钥绑定纯规则（ A/B/C/D/F/G/J/K/M）。
 *
 * 纯 JVM：不碰 Android / DataStore / Keystore / Compose。锁三件事：
 * 1. **保存判定**：换服务商留空密钥必须拒绝（B），同 origin 换 path 必须放行（A/K），
 * 无协议地址必须能识别 origin（J），坏地址必须拒绝（M）；
 * 2. **绑定判定**：先判绑定、再读密钥；host 不符/空 host/无密钥一律不可用；
 * 3. **展示与文案**：`hostForDisplay` 不泄露 userinfo/路径，F 三档状态与文案逐字可测。
 */
class CredentialRulesTest {

    companion object {
        private const val DEEPSEEK = "https://api.deepseek.com"
        private const val SILICONFLOW = "https://api.siliconflow.cn/v1"
        private const val ZHIPU = "https://open.bigmodel.cn/api/paas/v4"

        /** 禁用词（配置页允许的「密钥/地址/模型」不在此列）。 */
        private val TECH_BANNED: List<String> = listOf(
            "token",
            "上下文",
            "窗口",
            "向量",
            "embedding",
            "Embedding",
            "记忆检索",
            "记忆系统",
            "关键词",
            "缓存",
        )

        /** 角色代词只允许 TA。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 本包新增文案（供批量扫描）＋两页原有密钥说明（本轮补过「换服务商要重填」）。 */
        private fun allCopy(): List<Pair<String, String>> =
            CredentialCopy.ALL.mapIndexed { index, text -> "凭据文案 ${index + 1}" to text } +
                listOf(
                    "聊天页密钥说明" to ChatConfigCopy.KEY_NOTE,
                    "找旧事页密钥说明" to EmbedConfigCopy.KEY_NOTE,
                    "聊天页保存成功" to ChatConfigCopy.SAVE_SUCCESS,
                )
    }

    // ---------------------------------------------------------------- A / K：同 origin 保留旧密钥

    @Test
    fun `换模型且密钥留空 同 origin 保留旧密钥`() {
        // A：同地址改模型、密钥留空 → KeepOldKey（只更新地址/模型，密钥与 host 不动）。
        assertEquals(
            KeySaveDecision.KeepOldKey,
            saveDecision(
                hasKey = true,
                keyHost = DEEPSEEK,
                baseUrl = "$DEEPSEEK/v1",
                keyInputBlank = true,
            ),
        )
    }

    @Test
    fun `同 origin 换 path 视为同一服务商 不要求重填`() {
        // K：`/v1` → 不带 `/v1`（以及整条 chat/completions 端点）都是同一服务商。
        listOf(
            "$DEEPSEEK/v1",
            "$DEEPSEEK",
            "$DEEPSEEK/v1/chat/completions",
            "$DEEPSEEK/v1/",
        ).forEach { baseUrl ->
            assertEquals(
                "同 origin 的地址必须保留旧密钥：$baseUrl",
                KeySaveDecision.KeepOldKey,
                saveDecision(
                    hasKey = true,
                    keyHost = "$DEEPSEEK/",
                    baseUrl = baseUrl,
                    keyInputBlank = true,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- B：换服务商留空 → 拒绝

    @Test
    fun `换服务商且密钥留空 必须要求重填 不写任何字段`() {
        val decision = saveDecision(
            hasKey = true,
            keyHost = DEEPSEEK,
            baseUrl = SILICONFLOW,
            keyInputBlank = true,
        )
        assertEquals(KeySaveDecision.RequireNewKey, decision)
    }

    @Test
    fun `升级脏状态 有密钥但 host 为空 也必须要求重填`() {
        // I/′： 迁移后的形状（hasKey=true, keyHost=""）不能回填当前地址。
        assertEquals(
            KeySaveDecision.RequireNewKey,
            saveDecision(
                hasKey = true,
                keyHost = "",
                baseUrl = DEEPSEEK,
                keyInputBlank = true,
            ),
        )
        // 也不能因为「host 恰好长得像当前地址的旧值」就认为可以保留：只有解析后 origin
        // 完全一致才算绑定成立（host 写了路径/端点后缀也算不一致）。
        assertEquals(
            KeySaveDecision.KeepOldKey,
            saveDecision(
                hasKey = true,
                keyHost = "$DEEPSEEK/v1/chat/completions",
                baseUrl = DEEPSEEK,
                keyInputBlank = true,
            ),
        )
        assertEquals(
            KeySaveDecision.RequireNewKey,
            saveDecision(
                hasKey = true,
                keyHost = "api.deepseek.com",
                baseUrl = "https://api.siliconflow.cn",
                keyInputBlank = true,
            ),
        )
    }

    // ---------------------------------------------------------------- C：填了新密钥 → 走三步协议

    @Test
    fun `填了新密钥 无论换没换域 都走 SaveNewKey`() {
        listOf(
            Triple(true, DEEPSEEK, SILICONFLOW),
            Triple(true, DEEPSEEK, "$DEEPSEEK/v1"),
            Triple(false, "", DEEPSEEK),
        ).forEach { (hasKey, keyHost, baseUrl) ->
            assertEquals(
                "填了新密钥就要按新密钥重新绑定：hasKey=$hasKey host=$keyHost base=$baseUrl",
                KeySaveDecision.SaveNewKey,
                saveDecision(hasKey, keyHost, baseUrl, keyInputBlank = false),
            )
        }
    }

    // ---------------------------------------------------------------- D：从未配置

    @Test
    fun `从未配置密钥 只填地址和模型 可以保存`() {
        assertEquals(
            KeySaveDecision.NoKeyYet,
            saveDecision(hasKey = false, keyHost = "", baseUrl = DEEPSEEK, keyInputBlank = true),
        )
    }

    // ---------------------------------------------------------------- J / M：地址解析

    @Test
    fun `无协议地址能识别 origin`() {
        // J：`api.deepseek.com/v1` 省略了 https → BaseUrl.origin 补 https 后同一家。
        assertEquals("https://api.deepseek.com", BaseUrl.origin("api.deepseek.com/v1"))
        assertEquals(
            KeySaveDecision.KeepOldKey,
            saveDecision(
                hasKey = true,
                keyHost = DEEPSEEK,
                baseUrl = "api.deepseek.com/v1",
                keyInputBlank = true,
            ),
        )
        assertEquals(
            KeySaveDecision.NoKeyYet,
            saveDecision(
                hasKey = false,
                keyHost = "",
                baseUrl = "api.deepseek.com/v1",
                keyInputBlank = true,
            ),
        )
    }

    @Test
    fun `坏地址或空地址一律 BadOrigin`() {
        listOf(
            "",
            "   ",
            "https://",
            "https:///",
            "http://",
            "https:",
            "://没有主机",
        ).forEach { bad ->
            assertEquals(
                "坏地址必须拒绝保存：`$bad`",
                KeySaveDecision.BadOrigin,
                saveDecision(hasKey = true, keyHost = DEEPSEEK, baseUrl = bad, keyInputBlank = false),
            )
        }
    }

    @Test
    fun `聊天与找旧事两页同规则`() {
        // B 的「两页同规则」：判定函数只有一份、没有 page 参数，同一组持久状态与输入必须得到
        // 同一结论。这里用两页各自真实示例把关键分叉都跑一遍，防止将来有人给某页单独开口子。
        val scenarios = listOf(
            // 同 origin 换 path → 保留旧密钥（A/K）。
            Triple(true, DEEPSEEK, "$DEEPSEEK/v1") to KeySaveDecision.KeepOldKey,
            Triple(true, ZHIPU, "https://open.bigmodel.cn/api/paas/v4") to KeySaveDecision.KeepOldKey,
            // 换服务商留空 → 拒绝（B）。
            Triple(true, DEEPSEEK, SILICONFLOW) to KeySaveDecision.RequireNewKey,
            Triple(true, ZHIPU, "https://api.openai.com/v1") to KeySaveDecision.RequireNewKey,
            // host 空的升级脏状态 → 拒绝（I）。
            Triple(true, "", DEEPSEEK) to KeySaveDecision.RequireNewKey,
            Triple(true, "", ZHIPU) to KeySaveDecision.RequireNewKey,
            // 从未配置 → 只写地址/模型（D）。
            Triple(false, "", DEEPSEEK) to KeySaveDecision.NoKeyYet,
            Triple(false, "", ZHIPU) to KeySaveDecision.NoKeyYet,
        )
        scenarios.forEach { (args, expected) ->
            val (hasKey, keyHost, baseUrl) = args
            assertEquals(
                "两页同规则：hasKey=$hasKey host=$keyHost base=$baseUrl",
                expected,
                saveDecision(hasKey, keyHost, baseUrl, keyInputBlank = true),
            )
        }
        // 填了新密钥时两页都走 SaveNewKey（C）。
        listOf(DEEPSEEK to SILICONFLOW, ZHIPU to "https://api.openai.com/v1").forEach { (old, new) ->
            assertEquals(
                KeySaveDecision.SaveNewKey,
                saveDecision(hasKey = true, keyHost = old, baseUrl = new, keyInputBlank = false),
            )
        }
    }

    // ---------------------------------------------------------------- 绑定判定（先判绑定，再读密钥）

    @Test
    fun `host 一致且密文存在才 Usable`() {
        val binding = resolveBinding(
            hasKey = true,
            keyHost = DEEPSEEK,
            baseUrl = "$DEEPSEEK/v1",
            hasSecret = true,
        )
        assertTrue("绑定成立且密文存在必须 Usable", binding is KeyBinding.Usable)
        assertEquals(DEEPSEEK, (binding as KeyBinding.Usable).origin)
    }

    @Test
    fun `host 不一致 host 为空 无密钥 一律不可用`() {
        val mismatch = resolveBinding(
            hasKey = true,
            keyHost = DEEPSEEK,
            baseUrl = SILICONFLOW,
            hasSecret = true,
        )
        assertEquals("host 不符不能读密钥出网", KeyBinding.NotBound, mismatch)

        val blankHost = resolveBinding(
            hasKey = true,
            keyHost = "",
            baseUrl = DEEPSEEK,
            hasSecret = true,
        )
        assertEquals("空 host 不能自动绑定当前地址", KeyBinding.NotBound, blankHost)

        val noKeyFlag = resolveBinding(
            hasKey = false,
            keyHost = DEEPSEEK,
            baseUrl = DEEPSEEK,
            hasSecret = true,
        )
        assertEquals("hasKey=false 时不能使用密文", KeyBinding.NotBound, noKeyFlag)

        val noSecret = resolveBinding(
            hasKey = true,
            keyHost = DEEPSEEK,
            baseUrl = DEEPSEEK,
            hasSecret = false,
        )
        assertEquals("绑定成立但密文读不到 → NoKey", KeyBinding.NoKey, noSecret)

        val noBaseUrl = resolveBinding(
            hasKey = true,
            keyHost = DEEPSEEK,
            baseUrl = "",
            hasSecret = true,
        )
        assertEquals("地址为空时无法证明绑定", KeyBinding.NotBound, noBaseUrl)
    }

    @Test
    fun `绑定不成立时一次密钥都不读`() {
        // 点「先说绑定、再读密钥」：用计数器证明 readSecret 没被调用。
        var secretReads = 0
        val readSecret = {
            secretReads += 1
            "test-key-A"
        }
        resolveBinding(hasKey = true, keyHost = DEEPSEEK, baseUrl = SILICONFLOW, readSecret = readSecret)
        resolveBinding(hasKey = true, keyHost = "", baseUrl = DEEPSEEK, readSecret = readSecret)
        resolveBinding(hasKey = false, keyHost = DEEPSEEK, baseUrl = DEEPSEEK, readSecret = readSecret)
        assertEquals("绑定不成立时不应读密钥", 0, secretReads)

        val usable = resolveBinding(hasKey = true, keyHost = DEEPSEEK, baseUrl = DEEPSEEK, readSecret = readSecret)
        assertEquals("绑定成立后才读密钥", 1, secretReads)
        assertTrue(usable is KeyBinding.Usable)
    }

    @Test
    fun `bindingState 两段式 只判关系不碰密钥`() {
        assertEquals(
            KeyBindingState.Expected(DEEPSEEK),
            bindingState(hasKey = true, keyHost = DEEPSEEK, baseUrl = "$DEEPSEEK/v1"),
        )
        assertEquals(
            KeyBindingState.NotBound,
            bindingState(hasKey = true, keyHost = DEEPSEEK, baseUrl = SILICONFLOW),
        )
        assertEquals(
            KeyBindingState.NotBound,
            bindingState(hasKey = true, keyHost = "", baseUrl = DEEPSEEK),
        )
    }

    // ---------------------------------------------------------------- 升级迁移判定

    @Test
    fun `needsLegacyKeyReset 只命中 有密钥但 host 为空`() {
        assertTrue("升级脏状态要迁移", needsLegacyKeyReset(hasKey = true, keyHost = ""))
        assertFalse("正常绑定不能动", needsLegacyKeyReset(hasKey = true, keyHost = DEEPSEEK))
        assertFalse("没有密钥不用迁移", needsLegacyKeyReset(hasKey = false, keyHost = ""))
        assertFalse("没有密钥但 host 脏了也不靠这条迁移（孤儿对账负责）", needsLegacyKeyReset(false, DEEPSEEK))
    }

    // ---------------------------------------------------------------- F：状态派生

    @Test
    fun `密钥状态行三档派生`() {
        assertEquals(
            KeyStatus.Usable(DEEPSEEK),
            keyStatus(hasKey = true, keyHost = DEEPSEEK, baseUrl = "$DEEPSEEK/v1", hasSecret = true),
        )
        // 升级脏状态（有密钥、host 空）→ 要重填一次（不是「还没有密钥」）。
        assertEquals(
            KeyStatus.NeedsReentry,
            keyStatus(hasKey = true, keyHost = "", baseUrl = DEEPSEEK, hasSecret = true),
        )
        // 有密钥、host 有值、地址被清空 → 也要重填。
        assertEquals(
            KeyStatus.NeedsReentry,
            keyStatus(hasKey = true, keyHost = DEEPSEEK, baseUrl = "", hasSecret = true),
        )
        // hasKey 说谎（标记在但密文没了）→ 要重填，不能说「已保存」。
        assertEquals(
            KeyStatus.NeedsReentry,
            keyStatus(hasKey = true, keyHost = DEEPSEEK, baseUrl = DEEPSEEK, hasSecret = false),
        )
        assertEquals(
            KeyStatus.None,
            keyStatus(hasKey = false, keyHost = "", baseUrl = DEEPSEEK, hasSecret = false),
        )
    }

    // ---------------------------------------------------------------- hostForDisplay：不泄露

    @Test
    fun `hostForDisplay 只显示主机名`() {
        assertEquals("api.deepseek.com", hostForDisplay(DEEPSEEK))
        assertEquals("api.deepseek.com", hostForDisplay("$DEEPSEEK/"))
        assertEquals("api.deepseek.com", hostForDisplay("$DEEPSEEK:443"))
    }

    @Test
    fun `hostForDisplay 非默认端口带端口`() {
        assertEquals("api.deepseek.com:8443", hostForDisplay("https://api.deepseek.com:8443"))
        assertEquals("127.0.0.1:8080", hostForDisplay("http://127.0.0.1:8080"))
    }

    @Test
    fun `hostForDisplay 剥掉 userinfo 路径与查询`() {
        val display = hostForDisplay("https://user:secret@api.deepseek.com:8443/v1/chat?q=1#frag")
        assertEquals("api.deepseek.com:8443", display)
        assertFalse("不能泄露 userinfo：$display", display.contains("user"))
        assertFalse("不能泄露密钥占位：$display", display.contains("secret"))
        assertFalse("不能泄露路径：$display", display.contains("/"))
        assertFalse("不能泄露查询：$display", display.contains("?"))
    }

    @Test
    fun `hostForDisplay 取不到时给兜底文案 不泄露原始值`() {
        listOf("", "   ", "://", "https://", "not a url").forEach { invalid ->
            assertEquals("非法 origin 必须给兜底：`$invalid`", HOST_FALLBACK, hostForDisplay(invalid))
        }
        assertEquals("这个地址", HOST_FALLBACK)
    }

    // ---------------------------------------------------------------- 文案：逐字 + 禁用词

    @Test
    fun `保存与拒绝文案逐字`() {
        // B 聊天页逐字（B）。
        assertEquals(
            "换了服务商。密钥是发给服务商的，换了就得重填这一家的；不想用这家可以点「清除密钥」。",
            CredentialCopy.REQUIRE_NEW_KEY_CHAT,
        )
        // B 找旧事页：同规则、同一口径，出路换成本页真实存在的按钮。
        assertEquals(
            "换了服务商。密钥是发给服务商的，换了就得重填这一家的；不想用这家可以点「停止使用找旧事」。",
            CredentialCopy.REQUIRE_NEW_KEY_EMBED,
        )
        // G：不写、不假成功。
        assertEquals("地址和模型要填全才能保存。", CredentialCopy.INCOMPLETE)
        // L：保存失败要能重试。
        assertEquals("没存上，再试一次。", CredentialCopy.SAVE_FAILED)
        // E / H 轻提示逐字。
        assertEquals("密钥清掉了。要用再填一次。", CredentialCopy.KEY_CLEARED_TOAST)
        assertEquals("已经断开。找旧事不会再把聊天片段发出去。", CredentialCopy.DISCONNECTED_TOAST)
        // I 逐字（I）。
        assertEquals("这一版把密钥改成跟着服务商走，要重填一次。", CredentialCopy.UPGRADE_NOTICE)
    }

    @Test
    fun `坏地址文案按页给真实出路`() {
        // 聊天页有「清除密钥」按钮（E）。
        assertEquals(
            "地址要填完整（https://……）。想停用这家，点「清除密钥」。",
            CredentialCopy.badOrigin(forEmbed = false, hasKey = true),
        )
        // 找旧事页没有「清除密钥」，只有「停止使用找旧事」（H）。
        assertEquals(
            "地址要填完整（https://……）。想停用这项，点「停止使用找旧事」。",
            CredentialCopy.badOrigin(forEmbed = true, hasKey = true),
        )
        // 本来就没有密钥的用户不指向不存在的按钮。
        assertEquals(
            "地址要填完整（https://……）。",
            CredentialCopy.badOrigin(forEmbed = false, hasKey = false),
        )
        assertEquals(
            "地址要填完整（https://……）。",
            CredentialCopy.badOrigin(forEmbed = true, hasKey = false),
        )
    }

    @Test
    fun `状态行文案只显示主机名且不出现完整地址`() {
        assertEquals(
            "密钥已保存（只发给 api.deepseek.com）",
            CredentialCopy.keyStatusText(KeyStatus.Usable(DEEPSEEK)),
        )
        assertEquals(
            "密钥已保存（只发给 api.deepseek.com:8443）",
            CredentialCopy.keyStatusText(KeyStatus.Usable("https://api.deepseek.com:8443")),
        )
        assertEquals("密钥要重填一次", CredentialCopy.keyStatusText(KeyStatus.NeedsReentry))
        assertEquals("还没有密钥", CredentialCopy.keyStatusText(KeyStatus.None))
        CredentialCopy.ALL
            .filter { it.startsWith("密钥已保存") }
            .forEach { text ->
                assertFalse("状态行不能出现完整 URL：$text", text.contains("https://"))
                assertFalse("状态行不能出现路径：$text", text.contains("/v1"))
            }
    }

    @Test
    fun `两页密钥说明都补了换服务商要重填`() {
        listOf("聊天页" to ChatConfigCopy.KEY_NOTE, "找旧事页" to EmbedConfigCopy.KEY_NOTE)
            .forEach { (page, note) ->
                assertTrue("$page 保留原有承诺：$note", note.contains("只存在你的手机里"))
                assertTrue("$page 保留原有承诺：$note", note.contains("不会发给任何人"))
                assertTrue("$page 要补「换服务商要重填」：$note", note.contains("换了服务商要重填这一家的密钥"))
            }
    }

    @Test
    fun `新增文案无禁用词 无错误代词`() {
        allCopy().forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            assertFalse("「$name」出现客服腔「我们」：$text", text.contains("我们"))
        }
    }
}
