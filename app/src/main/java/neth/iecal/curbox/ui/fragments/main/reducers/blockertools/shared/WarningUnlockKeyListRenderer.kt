package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import neth.iecal.curbox.R

internal class WarningUnlockKeyListRenderer(
    private val fragment: Fragment,
    private val isOnEachOpenEnabled: () -> Boolean
) {
    fun render(
        container: LinearLayout,
        keys: MutableMap<String, Long>,
        label: String,
        refresh: () -> Unit
    ) {
        container.removeAllViews()
        keys.forEach { (key, duration) ->
            val itemView = LinearLayout(fragment.requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 16, 0, 16)
                weightSum = 1f
            }
            val infoText = TextView(fragment.requireContext()).apply {
                val durationText = when {
                    isOnEachOpenEnabled() ->
                        fragment.getString(R.string.warning_current_app_session)
                    duration == -1L -> fragment.getString(R.string.warning_unlock_dynamic_time)
                    else -> fragment.getString(R.string.warning_unlock_minutes, duration / 60_000)
                }
                text = "$label · $durationText"
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setPadding(4, 4, 4, 4)
            }
            val removeButton = MaterialButton(
                fragment.requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = fragment.getString(R.string.warning_unlock_remove)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    keys.remove(key)
                    refresh()
                }
            }
            itemView.addView(infoText)
            itemView.addView(removeButton)
            container.addView(itemView)
        }
    }
}
