// TODO: Change this and make a layout

package nethical.digipaws.ui.dialogs

import android.annotation.SuppressLint
import android.content.Intent
import android.view.MotionEvent
import android.widget.ScrollView
import androidx.fragment.app.DialogFragment
import nethical.digipaws.utils.SavedPreferencesLoader
import nl.joery.timerangepicker.TimeRangePicker

open class SelectLang(val savedPreferencesLoader: SavedPreferencesLoader? = null) :
    DialogFragment() {
    fun sendRefreshRequest(action: String) {
        val intent = Intent(action)
        context?.sendBroadcast(intent)
    }


    @SuppressLint("ClickableViewAccessibility")
    fun fixPickerInterceptBug(scrollview: ScrollView, picker: TimeRangePicker) {
        picker.setOnTouchListener { v, event ->
            // Disable ScrollView's touch interception when interacting with the picker
            when (event.action) {
                MotionEvent.ACTION_DOWN -> scrollview.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scrollview.requestDisallowInterceptTouchEvent(
                    false
                )
            }
            v.onTouchEvent(event) // Pass the event to the picker
        }
    }
    }
