package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import neth.iecal.curbox.R

internal class WarningUnlockTimingDialog(
    private val fragment: Fragment,
    private val isOnEachOpenEnabled: () -> Boolean
) {
    fun show(onConfigured: (Long) -> Unit) {
        if (isOnEachOpenEnabled()) {
            onConfigured(-1L)
            return
        }
        val context = fragment.requireContext()
        val pickerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val switchDynamic = SwitchMaterial(context).apply {
            text = fragment.getString(R.string.warning_option_choose_time)
            isChecked = true
        }
        val pickerInnerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
        }
        val timeLabel = TextView(context).apply {
            text = fragment.getString(R.string.warning_fixed_unlock_duration_lower, 5)
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val slider = Slider(context).apply {
            valueFrom = 1f
            valueTo = 120f
            stepSize = 1f
            value = 5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addOnChangeListener { _, value, _ ->
                timeLabel.text = fragment.getString(
                    R.string.warning_fixed_unlock_duration_lower,
                    value.toInt()
                )
            }
        }
        pickerInnerContainer.addView(timeLabel)
        pickerInnerContainer.addView(slider)
        switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            pickerInnerContainer.isVisible = !isChecked
        }
        pickerContainer.addView(switchDynamic)
        pickerContainer.addView(pickerInnerContainer)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.warning_qr_timing_title)
            .setMessage(R.string.warning_qr_timing_message)
            .setView(pickerContainer)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                val duration =
                    if (switchDynamic.isChecked) -1L else slider.value.toLong() * 60_000L
                onConfigured(duration)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
