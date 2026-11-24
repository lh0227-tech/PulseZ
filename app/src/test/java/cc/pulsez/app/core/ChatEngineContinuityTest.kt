package cc.pulsez.app.core

import cc.pulsez.app.data.db.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：DeepSeek 旧聊天导入之后要"接着聊"，索引块要能参与删除传播。
 *
 * 三件事：
 * 1. 没有导入往事（thread 里没有 `batonReason=IMPORT` 的段）时，① 层**不能**出现「接着聊」句；
 * 2. 有导入往事时，该句追加在原有 ① 层**末尾**（稳定前缀不被破坏），角色名/人设一个不少；
 * 3. `chunkMessageIds` 把一轮的两条消息 id 拼成 `ChunkDao.invalidateByMessage`
 * （`LIKE '%id%'`）能匹配到的格式。
 *
 * **纯 JVM**：不启动 Robolectric / AppContainer。`CharacterEntity` 是普通 data class
 * （Room 注解只在编译期给 KSP 用，不改运行时语义），`buildPersonaLayer` / `chunkMessageIds`
 * 都是 companion 纯函数、不碰 Android。
 */
class ChatEngineContinuityTest {

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

    // ------------------------------------------------------------ ① 段「接着聊」

    @Test
    fun withoutImportedHistoryPersonaLayerHasNoContinuityNote() {
        val layer = ChatEngine.buildPersonaLayer(character(), hasImportedHistory = false)

        assertTrue("身份锚仍在，实际=$layer", layer.contains("你叫阿淮。"))
        assertTrue("人设正文仍在", layer.contains("温柔、话不多。"))
        assertFalse("没有导入往事时不能出现「接着聊」", layer.contains("接着聊"))
        assertFalse("也不能出现约定句的任何片段", layer.contains("不要重新自我介绍"))
        assertFalse("不能出现「问你是谁」的抑制句", layer.contains("也不要问「你是谁」"))
    }

    @Test
    fun importedHistoryAppendsContinuityNoteAfterExistingLayer() {
        val base = ChatEngine.buildPersonaLayer(character(), hasImportedHistory = false)
        val layer = ChatEngine.buildPersonaLayer(character(), hasImportedHistory = true)

        // 追加语义：原有 ① 层文本**原封不动**，只在其后补一句（缓存前缀稳定）。
        assertEquals("只允许在末尾追加，不许改写原文本", base + ChatEngine.IMPORT_CONTINUITY_NOTE, layer)
        assertTrue("末尾必须是约定的接着聊句", layer.endsWith(ChatEngine.IMPORT_CONTINUITY_NOTE))
        assertTrue(layer.contains("你们以前聊过很多，这次是接着聊：不要重新自我介绍，也不要问「你是谁」。"))

        // 角色名、称呼、人设都还在（身份锚是"加在最前面"，不能被「接着聊」挤掉）。
        assertTrue("角色名不能丢", layer.contains("你叫阿淮。"))
        assertTrue("称呼不能丢", layer.contains("你称呼对方为小满。"))
        assertTrue("人设正文不能丢", layer.contains("温柔、话不多。"))
        assertTrue(
            "「接着聊」句必须在原有内容之后",
            layer.indexOf("接着聊") > layer.indexOf("你叫阿淮"),
        )
    }

    @Test
    fun deletedCharacterGetsNoFakeContinuityLine() {
        // 角色已删（character=null）时 ① 层本来就是空串；不能为了一句提示凭空造出人设。
        assertEquals("", ChatEngine.buildPersonaLayer(null, hasImportedHistory = true))
    }

    // ------------------------------------------------------------ chunk.messageIds

    @Test
    fun chunkMessageIdsJoinsBothIdsWithComma() {
        assertEquals("u1,a1", ChatEngine.chunkMessageIds("u1", "a1"))
    }

    @Test
    fun chunkMessageIdsIsMatchableByInvalidateByMessageLikeQuery() {
        // 仓库侧 `invalidateByMessage` 用 LIKE '%' || id || '%' 匹配，逐条包含即可命中；
        // 这里用 UUID 形态断言（真实 id 由 Ids.new 生成），避免只测玩具值。
        val userId = "3f2b9d8e-1c4a-4e77-9a10-5d6e7f801234"
        val assistantId = "9c1d2e3f-4a5b-4c6d-8e7f-0a1b2c3d4e5f"
        val ids = ChatEngine.chunkMessageIds(userId, assistantId)

        assertEquals("$userId,$assistantId", ids)
        assertTrue("用户消息 id 必须能被 LIKE 命中", ids.contains(userId))
        assertTrue("TA 消息 id 必须能被 LIKE 命中", ids.contains(assistantId))
    }

    // ------------------------------------------------------------ 与导入数据层的契约

    @Test
    fun importReasonConstantMatchesImporterContract() {
        // 导入落库时段的 `batonReason` 写 IMPORT；这里只读这一个常量判断"有导入往事"，
        // 两边取值必须一致。
        assertEquals("IMPORT", ChatEngine.IMPORT_REASON)
    }
}
