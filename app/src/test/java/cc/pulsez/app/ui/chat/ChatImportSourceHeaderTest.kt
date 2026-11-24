package cc.pulsez.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImportSourceHeaderTest {
    @Test
    fun `source header is shown at each imported source boundary`() {
        val messages = listOf(
            message("a1", source = "source-a"),
            message("a2", source = "source-a"),
            message("plain"),
            message("b1", source = "source-b"),
            message("b2", source = "source-b"),
        )

        assertTrue(shouldShowImportSourceHeader(messages, 0))
        assertFalse(shouldShowImportSourceHeader(messages, 1))
        assertFalse(shouldShowImportSourceHeader(messages, 2))
        assertTrue(shouldShowImportSourceHeader(messages, 3))
        assertFalse(shouldShowImportSourceHeader(messages, 4))
    }

    @Test
    fun `first loaded imported row keeps its source header`() {
        val page = listOf(
            message("a2", source = "source-a"),
            message("a3", source = "source-a"),
        )

        assertTrue(shouldShowImportSourceHeader(page, 0))
        assertFalse(shouldShowImportSourceHeader(page, 1))
        assertFalse(shouldShowImportSourceHeader(page, -1))
        assertFalse(shouldShowImportSourceHeader(page, page.size))
    }

    private fun message(id: String, source: String? = null) = ChatUiMessage(
        id = id,
        role = "user",
        text = "synthetic message",
        sourceSessionId = source,
        sourceTitle = source?.let { "synthetic title" },
        sourceStartedAt = 1_700_000_000_000L,
    )
}
