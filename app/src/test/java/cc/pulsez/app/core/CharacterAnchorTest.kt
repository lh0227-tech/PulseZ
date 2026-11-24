package cc.pulsez.app.core

import cc.pulsez.app.data.db.CharacterEntity
import cc.pulsez.app.mem.AssembleInput
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.mem.PromptAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺陷 回归：角色必须知道"自己叫什么"。
 *
 * 的角色锚只拼了人设正文，`character.name` 根本没进 prompt：用户在人设里没写
 * "我叫什么"时，模型被问"你叫什么"会自称 AI 助手或编名字——而创建页明明填了名字。
 *
 * 这里断言的是 **1:1 的真实 system 文本**（用 `ChatEngine.buildPersonaLayer` 产出 ① 段，
 * 再过 `PromptAssembler.assemble`），以及空名字的兜底不能出现「你叫。」残句。
 */
class CharacterAnchorTest {

    private fun character(
        name: String,
        persona: String = "温柔、话不多。",
        userName: String? = null,
        systemPromptOverride: String? = null,
    ): CharacterEntity = CharacterEntity(
        id = "c1",
        name = name,
        avatarUri = null,
        persona = persona,
        greeting = null,
        styleSamples = null,
        userName = userName,
        systemPromptOverride = systemPromptOverride,
        protocolEnabled = true,
        sourceType = "MANUAL",
        sourceRef = null,
        rawImported = null,
        emoAssetDir = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** 1:1 prompt 的 ① 段（第一条 system）。 */
    private fun firstSystemLayer(entity: CharacterEntity): String {
        val messages = PromptAssembler.assemble(
            AssembleInput(
                personaLayer = ChatEngine.buildPersonaLayer(entity),
                userMessage = "在吗",
            ),
        )
        val first = messages.first()
        assertEquals(MemMessage.ROLE_SYSTEM, first.role)
        return first.content
    }

    // ------------------------------------------------------------------ 1:1 身份锚

    @Test
    fun singleChatSystemPromptContainsCharacterNameAndUserName() {
        val system = firstSystemLayer(character(name = "阿淮", userName = "小满"))

        assertTrue("① 段必须写死角色名，实际=$system", system.contains("你叫阿淮。"))
        assertFalse("不应包含生硬造句", system.contains("别人问起你叫什么"))
        assertTrue("userName 非空时要写进称呼", system.contains("你称呼对方为小满。"))
        // 人设正文仍然在（身份锚是"加在最前面"，不是替换人设）
        assertTrue(system.contains("温柔、话不多。"))
    }

    @Test
    fun userNameIsOptional() {
        val system = firstSystemLayer(character(name = "阿淮", userName = null))
        assertTrue(system.contains("你叫阿淮。"))
        assertFalse("没有称呼时不能出现空称呼句", system.contains("你称呼对方为"))
    }

    @Test
    fun blankNameFallsBackToPlaceholderWithoutBrokenSentence() {
        val system = firstSystemLayer(character(name = "   "))

        assertTrue("空名字要兜底成「未命名」", system.contains("你叫未命名。"))
        assertFalse("不应包含生硬造句", system.contains("别人问起你叫什么"))
        assertFalse("绝不能出现「你叫。」这种残句", system.contains("你叫。"))
    }

    @Test
    fun systemPromptOverrideKeepsIdentityAnchor() {
        val system = firstSystemLayer(
            character(
                name = "林野",
                persona = "这段人设应该被覆盖掉。",
                userName = "阿七",
                systemPromptOverride = "你是林野，一个爱讲冷笑话的人。",
            ),
        )

        assertTrue("身份锚不受 override 影响", system.contains("你叫林野。"))
        assertTrue(system.contains("你称呼对方为阿七。"))
        assertTrue("override 仍然生效", system.contains("你是林野，一个爱讲冷笑话的人。"))
        assertFalse("用 override 时不该再拼旧 persona", system.contains("这段人设应该被覆盖掉。"))
    }

    @Test
    fun nullCharacterProducesNoFakeIdentity() {
        assertEquals("角色已删时不要凭空注入「未命名」", "", ChatEngine.buildPersonaLayer(null))
    }

    // ------------------------------------------------------------------ 群聊身份锚

    @Test
    fun groupNameLineStatesNameExplicitly() {
        assertEquals("你的名字是 阿乙，别人问起来就说 阿乙。", CharacterAnchor.groupNameLine("阿乙"))
        assertEquals("名字为空时兜底", "你的名字是 未命名，别人问起来就说 未命名。", CharacterAnchor.groupNameLine(""))
    }
}
