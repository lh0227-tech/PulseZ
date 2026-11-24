package cc.pulsez.app.ui.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterDirectoryInteractionTest {

    @Test
    fun longPressAndTapBuildMultiSelectionAndSelectAllAndClearWork() {
        val state = CharacterDirectoryInteractionState()
            .longPress("a")
            .tap("b")
        assertEquals(setOf("a", "b"), state.selection.selectedIds)

        val all = state.selectAll(listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), all.selection.selectedIds)
        assertTrue(all.selection.active)

        val cleared = all.clearSelection()
        assertTrue(cleared.selection.active)
        assertTrue(cleared.selection.selectedIds.isEmpty())
    }

    @Test
    fun cancelKeepsSelectionAndConfirmReturnsIdsThenExitsSelection() {
        val selected = CharacterDirectoryInteractionState()
            .longPress("a")
            .tap("b")
            .requestDelete()

        val cancelled = selected.cancelDelete()
        assertFalse(cancelled.confirmationVisible)
        assertEquals(setOf("a", "b"), cancelled.selection.selectedIds)

        val confirmed = selected.confirmDelete()
        assertEquals(setOf("a", "b"), confirmed.ids.toSet())
        assertEquals(CharacterDirectoryInteractionState(), confirmed.nextState)
    }

    @Test
    fun systemBackExitsSelectionBeforeRouteBack() {
        val selected = CharacterDirectoryInteractionState().longPress("a")
        assertEquals(CharacterDirectoryInteractionState(), selected.back())
        assertEquals(CharacterDirectoryInteractionState(), CharacterDirectoryInteractionState().back())
    }

    @Test
    fun refreshPreservesDirectoryQueryAndSortAcrossRouteReuse() {
        val beforeRefresh = ThreadsUiState(
            directoryQuery = "  白月 ",
            directorySort = CharacterDirectorySort.NAME,
        )
        val refreshed = ThreadsUiState(
            directoryItems = listOf(CharacterDirectoryItem("id", "白月", false, null)),
            totalCharacterCount = 1,
        )

        val result = preserveDirectoryControls(beforeRefresh, refreshed)
        assertEquals("  白月 ", result.directoryQuery)
        assertEquals(CharacterDirectorySort.NAME, result.directorySort)
        assertEquals(listOf("id"), result.directoryItems.map { it.id })
    }

    @Test
    fun confirmationExplainsBackupAndUnattributedLegacyDraftLimit() {
        assertTrue(CharacterDeletionCopy.CONFIRMATION.contains("以前导出的备份"))
        assertTrue(CharacterDeletionCopy.CONFIRMATION.contains("恢复旧备份"))
        assertTrue(CharacterDeletionCopy.CONFIRMATION.contains("无法归属到角色的未发送附件可能保留"))
    }

    @Test
    fun homeDirectoryEntryIsVisibleForOneSixAndSevenRolesAndKeepsTheDirectoryAction() {
        assertEquals("管理角色", homeCharacterDirectoryEntryLabel(1))
        assertEquals("管理角色", homeCharacterDirectoryEntryLabel(6))
        assertEquals("全部角色（7）", homeCharacterDirectoryEntryLabel(7))
        assertEquals("管理角色", homeCharacterDirectoryEntryLabel(0))

        val opened = mutableListOf<Int>()
        val entries = listOf(1, 6, 7).map { count ->
            homeCharacterDirectoryEntry(count) { opened += count }
        }
        assertEquals(listOf("管理角色", "管理角色", "全部角色（7）"), entries.map { it.label })
        entries.forEach { it.onOpen() }
        assertEquals(listOf(1, 6, 7), opened)
    }
}
