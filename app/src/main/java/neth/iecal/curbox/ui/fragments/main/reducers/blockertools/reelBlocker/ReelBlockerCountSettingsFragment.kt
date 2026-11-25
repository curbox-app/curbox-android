package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.ReelCountConfig
import neth.iecal.curbox.databinding.FragmentReelBlockerCountSettingsBinding
import neth.iecal.curbox.databinding.ItemDayCountLimitBinding

class ReelBlockerCountSettingsFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentReelBlockerCountSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReelBlockerViewModel by activityViewModels()

    private val daysOfWeek: List<String>
        get() = resources.getStringArray(R.array.days_of_week_monday_first).toList()

    private val dayBindings = mutableMapOf<Int, ItemDayCountLimitBinding>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReelBlockerCountSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupDayViews()

        binding.switchEveryDay.setOnCheckedChangeListener { _, isChecked ->
            binding.daysListContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
            binding.everydayContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
            dismiss()
        }

        loadExistingSettings()
    }

    private fun setupDayViews() {
        daysOfWeek.forEachIndexed { index, dayName ->
            val dayBinding = ItemDayCountLimitBinding.inflate(layoutInflater, binding.daysListContainer, true)
            dayBinding.dayLabel.text = dayName
            dayBindings[index + 1] = dayBinding // 1 to 7

            dayBinding.switchDayActive.setOnCheckedChangeListener { _, isChecked ->
                dayBinding.limitContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            
            dayBinding.limitContainer.visibility = View.GONE
        }
    }

    private fun loadExistingSettings() {
        val config = viewModel.getReelCountConfig()
        binding.switchEveryDay.isChecked = config.isDailyUniform

        if (config.uniformLimit > 0) {
            binding.etEverydayLimit.setText(config.uniformLimit.toString())
        }

        dayBindings.forEach { (uiDayIndex, dayBinding) ->
            // UI Day Index: 1=Mon... 6=Sat, 7=Sun
            // Model Index: 0=Sun, 1=Mon... 6=Sat
            val modelIndex = if (uiDayIndex == 7) 0 else uiDayIndex
            val limit = config.dailyLimits[modelIndex]
            
            if (limit > 0) {
                dayBinding.switchDayActive.isChecked = true
                dayBinding.etLimit.setText(limit.toString())
            } else {
                dayBinding.switchDayActive.isChecked = false
            }
        }
    }

    private fun saveSettings() {
        val isEveryday = binding.switchEveryDay.isChecked
        val everydayLimitStr = binding.etEverydayLimit.text.toString()
        val everydayLimit = if (everydayLimitStr.isNotEmpty()) everydayLimitStr.toInt() else 0

        val newConfig = ReelCountConfig(
            isDailyUniform = isEveryday,
            uniformLimit = everydayLimit,
            dailyLimits = IntArray(7) { 0 }
        )

        dayBindings.forEach { (uiDayIndex, dayBinding) ->
            val modelIndex = if (uiDayIndex == 7) 0 else uiDayIndex
            if (dayBinding.switchDayActive.isChecked) {
                val limitStr = dayBinding.etLimit.text.toString()
                newConfig.dailyLimits[modelIndex] = if (limitStr.isNotEmpty()) limitStr.toInt() else 0
            }
        }

        viewModel.saveReelCountConfig(newConfig)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "ReelBlockerCountSettingsFragment"
    }
}
