package neth.iecal.curbox.ui.views

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoPasteTextInputEditTextTest {

    @Test
    fun allowsUpToFiveCodePointsPerCommit() {
        assertTrue(isAllowedImeCommit(""))
        assertTrue(isAllowedImeCommit("\u4F60\u597D"))
        assertTrue(isAllowedImeCommit("\u4E00\u4E8C\u4E09\u56DB\u4E94"))
        assertTrue(isAllowedImeCommit("abcde"))
        assertTrue(isAllowedImeCommit("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"))
    }

    @Test
    fun rejectsMoreThanFiveCodePointsPerCommit() {
        assertFalse(isAllowedImeCommit("\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D"))
        assertFalse(isAllowedImeCommit("abcdef"))
        assertFalse(isAllowedImeCommit("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"))
    }

    @Test
    fun pasteContextMenuActionsAreBlocked() {
        assertTrue(isPasteContextMenuAction(android.R.id.paste))
        assertTrue(isPasteContextMenuAction(android.R.id.pasteAsPlainText))
    }

    @Test
    fun nonPasteContextMenuActionsAreNotBlocked() {
        assertFalse(isPasteContextMenuAction(android.R.id.copy))
    }
}
