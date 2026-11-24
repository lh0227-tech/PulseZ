package cc.pulsez.app.ui.group

import org.junit.Assert.assertEquals
import org.junit.Test

class DeletedGroupSpeakerLabelTest {
    @Test
    fun missingRoleUsesDeletedLabelAndLiveNameStaysVisible() {
        assertEquals("已删除角色", groupSpeakerDisplayName(null))
        assertEquals("已删除角色", groupSpeakerDisplayName("  "))
        assertEquals("小满", groupSpeakerDisplayName("小满"))
    }
}
