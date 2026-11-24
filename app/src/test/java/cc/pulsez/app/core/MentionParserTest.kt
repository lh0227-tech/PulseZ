package cc.pulsez.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MentionParser] 的行为锁死（ 第 7 条）。
 *
 * 覆盖：中文最长匹配、全角 `＠`、标点/无空格后缀、重复 @、`@所有人` 不广播、
 * 未知名字不阻断、名字互为前缀、最多 2 个且按正文出现顺序。
 */
class MentionParserTest {

    private fun member(id: String, name: String, ordinal: Int = 0): GroupMember =
        GroupMember(characterId = id, name = name, persona = "", ordinal = ordinal)

    private val huai = member("a", "阿淮", ordinal = 1)
    private val ye = member("b", "林野", ordinal = 2)
    private val man = member("c", "小满", ordinal = 3)
    private val members = listOf(huai, ye, man)

    // ---------------------------------------------------------------- 基础

    @Test
    fun plainMentionMatchesMemberInTextOrder() {
        val result = MentionParser.parse("阿淮，你觉得呢？@林野 也说说", members)
        assertEquals(listOf("b"), result.addressedIds)
        assertEquals("正文原样返回给模型", "阿淮，你觉得呢？@林野 也说说", result.cleanedText)
        assertFalse(result.broadcast)
        assertTrue(result.unknownTokens.isEmpty())
    }

    @Test
    fun mentionAtStartWithoutLeadingAtIsNotAMention() {
        // 没有 @ 的名字是普通文本，不能当点名
        assertEquals(emptyList<String>(), MentionParser.parse("阿淮，你觉得呢？", members).addressedIds)
    }

    @Test
    fun fullWidthAtIsAccepted() {
        val result = MentionParser.parse("＠林野 你在吗", members)
        assertEquals(listOf("b"), result.addressedIds)
    }

    @Test
    fun punctuationAfterNameStillMatches() {
        for (text in listOf("@林野，在吗", "@林野。", "@林野！", "@林野？", "@林野、还有你", "@林野：说句话")) {
            assertEquals("「$text」应命中林野", listOf("b"), MentionParser.parse(text, members).addressedIds)
        }
    }

    @Test
    fun noSpaceChineseSuffixStillMatches() {
        // 中文没有空格：@林野你觉得呢 必须命中（规则 3）
        assertEquals(listOf("b"), MentionParser.parse("@林野你觉得呢", members).addressedIds)
    }

    @Test
    fun englishNameIsCaseInsensitive() {
        val alice = member("e", "Alice", ordinal = 4)
        assertEquals(listOf("e"), MentionParser.parse("@alice 在吗", listOf(alice)).addressedIds)
        assertEquals(listOf("e"), MentionParser.parse("@ALICE 在吗", listOf(alice)).addressedIds)
    }

    // ---------------------------------------------------------------- 最长匹配

    @Test
    fun longestNameWinsWhenOneNameIsPrefixOfAnother() {
        val short = member("s", "小美", ordinal = 5)
        val long = member("l", "小美美", ordinal = 6)
        val group = listOf(short, long)

        assertEquals("存在更长名字时选更长的", listOf("l"), MentionParser.parse("@小美美你好", group).addressedIds)
        assertEquals("较短名字单独出现时选较短的", listOf("s"), MentionParser.parse("@小美你好", group).addressedIds)
    }

    @Test
    fun singleCharacterNameAndLongerNameCoexist() {
        val short = member("s", "阿", ordinal = 7)
        val long = member("l", "阿淮", ordinal = 8)
        val group = listOf(short, long)

        assertEquals(listOf("l"), MentionParser.parse("@阿淮你觉得呢", group).addressedIds)
        assertEquals(listOf("s"), MentionParser.parse("@阿，你觉得呢", group).addressedIds)
    }

    @Test
    fun duplicateMentionOfSameMemberCountsOnce() {
        val result = MentionParser.parse("@林野 @林野 你说说", members)
        assertEquals(listOf("b"), result.addressedIds)
    }

    @Test
    fun maxTwoTargetsInTextOrderAndThirdIgnored() {
        // 正文顺序：小满 → 阿淮 → 林野；只认前两个
        val result = MentionParser.parse("@小满 @阿淮 @林野 都来说说", members)
        assertEquals(listOf("c", "a"), result.addressedIds)
        assertTrue("第 3 个被静默忽略，不算 unknown", result.unknownTokens.isEmpty())
    }

    // ---------------------------------------------------------------- 广播

    @Test
    fun everyoneBroadcastIsNotAddressedAndNotUnknown() {
        for (text in listOf("@所有人 大家晚上好", "@全体 大家晚上好", "@全体成员 大家晚上好", "＠所有人 在吗")) {
            val result = MentionParser.parse(text, members)
            assertEquals("「$text」不能广播成 addressedIds", emptyList<String>(), result.addressedIds)
            assertTrue("「$text」应标记 broadcast", result.broadcast)
            assertTrue("广播词不算「没找到」", result.unknownTokens.isEmpty())
        }
    }

    @Test
    fun broadcastWithAPersonKeepsThePersonOnly() {
        val result = MentionParser.parse("@林野 @所有人 你们都在吗", members)
        assertEquals(listOf("b"), result.addressedIds)
        assertTrue(result.broadcast)
    }

    // ---------------------------------------------------------------- 未知名字

    @Test
    fun unknownNameGoesToUnknownTokensAndDoesNotBlock() {
        val result = MentionParser.parse("@不存在 你好呀", members)
        assertEquals(emptyList<String>(), result.addressedIds)
        assertEquals(listOf("不存在"), result.unknownTokens)
        assertFalse(result.broadcast)
        assertEquals("发送不拦截：正文原样保留", "@不存在 你好呀", result.cleanedText)
    }

    @Test
    fun bareAtOrUnknownAtInsideTextIsHarmless() {
        assertEquals(emptyList<String>(), MentionParser.parse("@ 你好", members).unknownTokens)
        assertEquals(emptyList<String>(), MentionParser.parse("邮箱 a@b.com", members).addressedIds)
    }

    @Test
    fun unknownTokensAreDeduplicatedInOrder() {
        val result = MentionParser.parse("@不存在 @不存在 你好 @也不存在", members)
        assertEquals(emptyList<String>(), result.addressedIds)
        assertEquals(listOf("不存在", "也不存在"), result.unknownTokens)
    }

    // ---------------------------------------------------------------- 高亮区间

    @Test
    fun highlightRangesCoverAtAndNameOnly() {
        val text = "你好 @林野 ，还有＠小满。"
        val ranges = MentionParser.highlightRanges(text, members)
        assertEquals(2, ranges.size)
        assertEquals("@林野", text.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals("＠小满", text.substring(ranges[1].first, ranges[1].last + 1))
    }

    @Test
    fun highlightIgnoresBroadcastAndUnknown() {
        assertTrue(MentionParser.highlightRanges("@所有人 @不存在 你好", members).isEmpty())
    }

    // ---------------------------------------------------------------- 同名兜底

    @Test
    fun duplicateNamesFallBackToSmallestOrdinal() {
        // 建群时禁止同名；历史脏数据里若真有同名，取 ordinal 最小的，结果稳定（规则 5）
        val first = member("x", "同名", ordinal = 1)
        val second = member("y", "同名", ordinal = 2)
        val result = MentionParser.parse("@同名 你好", listOf(second, first))
        assertEquals(listOf("x"), result.addressedIds)
    }
}
