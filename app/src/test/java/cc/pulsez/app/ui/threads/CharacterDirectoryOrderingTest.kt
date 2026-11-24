package cc.pulsez.app.ui.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterDirectoryOrderingTest {

    @Test
    fun homeShowsAtMostSixAndUsesChineseNameOrderToFillWithUnchattedRoles() {
        val six = (1..6).map { item("id-$it", "角色$it", null) }
        assertTrue(homeCharacterItems(emptyList()).isEmpty())
        assertEquals(6, homeCharacterItems(six).size)
        assertEquals(six.map { it.id }, homeCharacterItems(six).map { it.id })

        val seven = listOf(
            item("a", "没聊过甲", null),
            item("b", "最近一位", 900L),
            item("c", "没聊过乙", null),
            item("d", "最近二位", 700L),
            item("e", "最近三位", 500L),
            item("f", "没聊过丙", null),
            item("g", "最近四位", 300L),
        )
        val home = homeCharacterItems(seven)
        assertEquals(6, home.size)
        // pinyin: 丙 (bing) precedes 甲 (jia), which precedes 乙 (yi).
        assertEquals(listOf("b", "d", "e", "g", "f", "a"), home.map { it.id })
        assertEquals("目录总数仍独立于首页展示上限", 7, seven.size)
    }

    @Test
    fun nameSortUsesChinesePinyinAndIdBreaksCollationTies() {
        val items = listOf(
            item("song", "松", null),
            item("z-bai", "白", null),
            item("hong", "红", null),
            item("a-bai", "白", null),
        )

        assertEquals(
            listOf("a-bai", "z-bai", "hong", "song"),
            filterAndSortCharacterItems(items, "", CharacterDirectorySort.NAME).map { it.id },
        )
    }

    @Test
    fun activityTiesAndNullsHaveStableNameAndIdOrder() {
        val items = listOf(
            item("z", "松", null),
            item("b", "白", 10L),
            item("a", "白", 10L),
            item("m", "红", null),
        )
        assertEquals(listOf("a", "b", "m", "z"), homeCharacterItems(items).map { it.id })
    }

    @Test
    fun directorySearchIsLocalNameOnlyAndSupportsBothSorts() {
        val items = listOf(
            item("b", "白月", 10L),
            item("a", "白月", 10L),
            item("c", "红叶", 30L),
        )
        assertEquals(
            listOf("a", "b"),
            filterAndSortCharacterItems(items, " 白月 ", CharacterDirectorySort.RECENT_CHAT).map { it.id },
        )
        assertEquals(
            listOf("a", "b", "c"),
            filterAndSortCharacterItems(items, "", CharacterDirectorySort.NAME).map { it.id },
        )
        assertTrue(filterAndSortCharacterItems(items, "不存在", CharacterDirectorySort.NAME).isEmpty())
    }

    private fun item(id: String, name: String, activityAt: Long?) =
        CharacterDirectoryItem(id = id, name = name, imported = false, lastSingleChatActivityAt = activityAt)
}
