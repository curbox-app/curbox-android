package neth.iecal.curbox.ui.views

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.DragEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import com.google.android.material.textfield.TextInputEditText

internal fun isAllowedImeCommit(text: CharSequence): Boolean =
    Character.codePointCount(text, 0, text.length) <= 5

internal fun isPasteContextMenuAction(id: Int): Boolean =
    id == android.R.id.paste || id == android.R.id.pasteAsPlainText

/**
 * Text input for challenges that must be completed by typing.
 *
 * Paste is blocked from the regular and selection action menus and drag
 * and drop. Input method commits are limited to five Unicode code points.
 */
class NoPasteTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    override fun onTextContextMenuItem(id: Int): Boolean {
        return if (isPasteContextMenuAction(id)) {
            false
        } else {
            super.onTextContextMenuItem(id)
        }
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        return if (event.action == DragEvent.ACTION_DROP) false else super.onDragEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33LimitedInputConnection(connection)
        } else {
            LimitedInputConnection(connection)
        }
    }

    private open inner class LimitedInputConnection(target: InputConnection) :
        InputConnectionWrapper(target, false) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            return if (isAllowedImeCommit(text)) {
                super.commitText(text, newCursorPosition)
            } else {
                true
            }
        }

        override fun performContextMenuAction(id: Int): Boolean {
            return if (isPasteContextMenuAction(id)) true else super.performContextMenuAction(id)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private inner class Api33LimitedInputConnection(target: InputConnection) :
        LimitedInputConnection(target) {

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?
        ): Boolean {
            return if (isAllowedImeCommit(text)) {
                super.commitText(text, newCursorPosition, textAttribute)
            } else {
                true
            }
        }
    }

    init {
        // Removes paste from the floating insertion toolbar on supported Android versions.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            customInsertionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(
                    mode: android.view.ActionMode,
                    menu: android.view.Menu
                ) = false

                override fun onPrepareActionMode(
                    mode: android.view.ActionMode,
                    menu: android.view.Menu
                ) = false

                override fun onActionItemClicked(
                    mode: android.view.ActionMode,
                    item: android.view.MenuItem
                ) = false

                override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
            }
        }

        // Prevents autofill services from filling the typing challenge.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }
}
