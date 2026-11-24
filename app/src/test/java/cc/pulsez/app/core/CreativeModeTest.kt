package cc.pulsez.app.core

import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.mem.PromptAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ：「创作模式」内核文案（ –；设计见 ）。
 *
 * 纯 JVM：不启动 Robolectric / AppContainer。`CreativeMode` 是纯 Kotlin 对象，
 * `ChatEngine.buildPersonaLayer` 是 companion 纯函数（`CharacterEntity` 是普通 data class，
 * Room 注解只在编译期给 KSP 用，不改运行时语义）。
 *
 * 覆盖：
 * 1. 创作层只在开启时出现在 ① 层**末尾**；关闭时 ① 层与 完全一致；
 * 2. 创作层四条边界齐全（不美化自伤 / 不露骨性内容 / 不诱导分不清故事与现实 / 被问真人要诚实），
 * 且不出现"成人 / 无限制 / 擦边"这类会被理解成"内容放开"的词；
 * 3. 危机兜底两版都给 12356 / 120 / "身边信得过的人"，创作版含"戏里的台词"，
 * 都不写"24 小时"，也**不提未实现的「紧急联系人」**（该功能全仓无实现，
 * 危机场景不能给不可兑现的求助路径。
 */
class CreativeModeTest {

    private fun character(
        name: String = "阿淮",
        persona: String = "温柔、话不多。",
        userName: String? = "小满",
    ): CharacterEntity = CharacterEntity(
        id = "c1",
        name = name,
        avatarUri = null,
        persona = persona,
        greeting = null,
        styleSamples = null,
        userName = userName,
        systemPromptOverride = null,
        protocolEnabled = true,
        sourceType = "DEEPSEEK",
        sourceRef = null,
        rawImported = null,
        emoAssetDir = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    // ------------------------------------------------------------ 创作层

    @Test
    fun `创作层确立虚构演绎语境且不放开红线`() {
        val layer = CreativeMode.layer()

        assertTrue("创作层要带醒目的模式开头：$layer", layer.startsWith("【创作模式】"))
        assertTrue("要明确虚构故事或角色扮演语境：$layer", layer.contains("虚构故事创作或角色扮演"))
        assertTrue("要明确允许强烈性格等戏剧冲突：$layer", layer.contains("强烈性格"))
        assertTrue("要允许更长/更有画面感与动作神态：$layer", layer.contains("更有画面感"))
        assertTrue("要授权自然描写陪伴与拥抱等肢体互动：$layer", layer.contains("允许自然描写陪伴与拥抱等肢体互动"))
        assertTrue("要明确不以没有实体为由跳出角色或说教：$layer", layer.contains("不要以没有实体为由"))
        assertTrue("要强调不跳出角色说教：$layer", layer.contains("跳出角色或说教"))
        assertFalse("创作层不再重复自指'不是真人'：$layer", layer.contains("不是真人"))
        assertFalse("创作层不再重复'诚实'（统一收口在输出协议层）：$layer", layer.contains("诚实"))

        // 禁用词：不能有任何"内容放开"的暗示（「创作模式＝不受限制」是错误暗示）
        listOf("成人", "无限制", "擦边", "R18", "18+").forEach { word ->
            assertFalse("创作层不能出现「$word」（会被理解成内容放开）：$layer", layer.contains(word))
        }
        // 角色代词只用 TA；这是发给模型的文本，同样不该出现她/他
        listOf("她", "他").forEach { pronoun ->
            assertFalse("创作层不能出现角色代词「$pronoun」：$layer", layer.contains(pronoun))
        }
    }

    @Test
    fun `创作模式与输出协议层联合后诚实底线只出现一次`() {
        val combined = PromptAssembler.buildProtocolLayer(true, creativeMode = true) +
            "\n" + CreativeMode.layer()
        val honestCount = combined.split("诚实").size - 1
        assertEquals("「诚实」底线应仅在输出协议层出现 1 次，避免重复激活自我认知：$combined", 1, honestCount)
    }

    // ------------------------------------------------------------ 危机兜底

    @Test
    fun `普通危机兜底给现实资源`() {
        val line = CreativeMode.crisisLine(creativeMode = false)
        // 逐字断言：。 修复前那句"你设的紧急联系人"必须消失，
        // 换成可兑现的"身边信得过的人"（全仓没有紧急联系人功能）。
        assertEquals(
            "如果这几天一直这样，还是找个人聊聊比较好，真的。" +
                "心理援助热线 12356；有马上危险就打 120。也可以联系你身边信得过的人。",
            line,
        )
        assertTrue("必须给 12356：$line", line.contains("12356"))
        assertTrue("必须给 120：$line", line.contains("120"))
        assertTrue("要给可兑现的求助路径：身边信得过的人：$line", line.contains("身边信得过的人"))
        assertFalse("不许提未实现的「紧急联系人」（B4）：$line", line.contains("紧急联系人"))
        assertTrue("保留原来那句'找个人聊聊'：$line", line.contains("找个人"))
        assertFalse("12356 不是 24 小时热线：$line", line.contains("24 小时"))
        assertFalse("不做永久/绝对承诺：$line", line.contains("永远") || line.contains("绝对"))
    }

    @Test
    fun `创作模式危机兜底先承认戏里的台词`() {
        val line = CreativeMode.crisisLine(creativeMode = true)
        // 逐字断言：（同样不提未实现的紧急联系人）。
        assertEquals(
            "如果这是戏里的台词，就当我没打断。" +
                "如果你真的很难受：心理援助热线 12356；有马上危险就打 120。" +
                "也可以联系你身边信得过的人。",
            line,
        )
        assertTrue("先给沉浸一个台阶：$line", line.contains("戏里的台词"))
        assertTrue("创作版同样给 12356：$line", line.contains("12356"))
        assertTrue("创作版同样给 120：$line", line.contains("120"))
        assertTrue("创作版同样给可兑现的求助路径：$line", line.contains("身边信得过的人"))
        assertFalse("创作版也不许提未实现的「紧急联系人」（B4）：$line", line.contains("紧急联系人"))
        assertFalse("不能写\"24 小时\"：$line", line.contains("24 小时"))
        assertFalse("不做永久/绝对承诺：$line", line.contains("永远") || line.contains("绝对"))
        assertNotEquals(
            "两版必须是不同文案",
            CreativeMode.crisisLine(creativeMode = false),
            line,
        )
    }

    @Test
    fun `内核文案不出现技术词与角色代词`() {
        // 与 `mem/CopyPolicyTest` 的禁用词表保持同一口径：
        // 这些文本会进 prompt/用户在危机时看到，不能出现技术词、客服腔、"我们"或她/他。
        val banned = listOf(
            "token", "上下文", "窗口", "向量", "embedding", "缓存", "关键词", "记忆检索",
            "亲", "小可爱", "您好", "我们", "她", "他",
        )
        val texts = listOf(
            "创作层" to CreativeMode.layer(),
            "普通危机文案" to CreativeMode.crisisLine(creativeMode = false),
            "创作模式危机文案" to CreativeMode.crisisLine(creativeMode = true),
        )
        texts.forEach { (name, text) ->
            banned.forEach { word ->
                assertFalse("「$name」不能出现「$word」：$text", text.contains(word))
            }
        }
    }

    // ------------------------------------------------------------ ① 层接线

    @Test
    fun `默认关闭时人设层与 v0_3 完全一致`() {
        val v03 = ChatEngine.buildPersonaLayer(character())
        val defaults = ChatEngine.buildPersonaLayer(character(), creativeMode = false)

        assertEquals("不传/传 false 必须是同一份文本（缓存前缀不变）", v03, defaults)
        assertFalse("默认关不能出现创作层", defaults.contains("【创作模式】"))
        assertFalse("默认关不能出现创作层的任何句子", defaults.contains("回复可以更长"))
    }

    @Test
    fun `开启时创作层追加在人设层末尾且原有内容不变`() {
        val base = ChatEngine.buildPersonaLayer(character(), creativeMode = false)
        val creative = ChatEngine.buildPersonaLayer(character(), creativeMode = true)

        assertEquals("只允许在末尾追加（单换行衔接），不许改写原文本", base + "\n" + CreativeMode.layer(), creative)
        assertTrue("创作层必须在最末尾（缓存前缀稳定）：$creative", creative.endsWith(CreativeMode.layer()))
        assertTrue("身份锚不能丢", creative.contains("你叫阿淮。"))
        assertTrue("称呼不能丢", creative.contains("你称呼对方为小满。"))
        assertTrue("人设正文不能丢", creative.contains("温柔、话不多。"))
        assertTrue("创作层要在人设正文之后", creative.indexOf("【创作模式】") > creative.indexOf("温柔、话不多。"))
    }

    @Test
    fun `有导入往事时创作层排在接着聊之后`() {
        val withImport = ChatEngine.buildPersonaLayer(character(), hasImportedHistory = true, creativeMode = true)

        assertTrue("导入往事仍要补'接着聊'", withImport.contains("接着聊"))
        assertTrue("创作层仍在最末尾", withImport.endsWith(CreativeMode.layer()))
        assertTrue(
            "顺序固定：接着聊 → 创作层",
            withImport.indexOf("接着聊") < withImport.indexOf("【创作模式】"),
        )
    }

    @Test
    fun `没有角色时不追加创作层`() {
        // 角色已删（character=null）：① 层本来就是空串，不能为一个开关凭空造出创作层。
        assertEquals("", ChatEngine.buildPersonaLayer(null, creativeMode = true))
        assertEquals("", ChatEngine.buildPersonaLayer(null, hasImportedHistory = true, creativeMode = true))
    }
}
