package nethical.digipaws.ui.dialogs

import android.animation.LayoutTransition
import android.app.Dialog
import android.os.Bundle
import android.util.Log
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.R
import nethical.digipaws.databinding.DialogTweakBlockerWarningBinding
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.ui.activity.MainActivity
import nethical.digipaws.utils.AnimTools.Companion.animateVisibility
import nethical.digipaws.utils.SavedPreferencesLoader

class TweakAppBlockerWarning(savedPreferencesLoader: SavedPreferencesLoader) : BaseDialog(
    savedPreferencesLoader
) {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Inflate the custom dialog layout
        val binding = DialogTweakBlockerWarningBinding.inflate(layoutInflater)

        // Set up number picker
        binding.selectMins.minValue = 1
        binding.selectMins.maxValue = 240

        binding.cbProceedBtn.setOnCheckedChangeListener { _, isChecked ->
            val viewsToToggle = listOf(
                binding.cbDynamicWarning,
                binding.selectMins,
                binding.info,
                binding.proceedDelay
            )
            viewsToToggle.forEach { it.animateVisibility(!isChecked) }

        }

        binding.cbBackWithoutWarning.setOnCheckedChangeListener { _, isChecked ->
            val viewsToToggle = listOf(
                binding.cbDynamicWarning,
                binding.selectMins,
                binding.textInputLayout2,
                binding.info,
                binding.cbProceedBtn,
                binding.proceedDelay
            )
            viewsToToggle.forEach { it.animateVisibility(!isChecked) }
        }

        // Load previous data from preferences
        val previousData = savedPreferencesLoader!!.loadAppBlockerWarningInfo()
        var proceedDelay = previousData.proceedDelayInSecs

        when (proceedDelay) {
            3 -> binding.proceedDelayChips.check(R.id.three_sec_chip)
            9 -> binding.proceedDelayChips.check(R.id.nine_sec_chip)
            30 -> binding.proceedDelayChips.check(R.id.thirty_sec_chip)
            15 -> binding.proceedDelayChips.check(R.id.fifteen_sec_chip)
        }

        binding.proceedDelayChips.setOnCheckedStateChangeListener { group, checkedIds ->

            val chip = group.findViewById<Chip>(checkedIds[0])
            proceedDelay = chip.text.toString().slice(IntRange(0, 1)).trim().toInt()
            Log.d("proceedDelay", "onCreateDialog: $proceedDelay")
        }

        previousData.let {
            binding.selectMins.setValue(it.timeInterval / 60000)
            binding.warningMsgEdit.setText(it.message)
            binding.cbProceedBtn.isChecked = it.isProceedDisabled
            binding.cbBackWithoutWarning.isChecked = it.isWarningDialogHidden
        }

        binding.root.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(300) // Set animation duration in ms
        }

        // Build and return the dialog
        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setPositiveButton(getString(R.string.save)) { dialog, _ ->
                val selectedMinInMs = binding.selectMins.getValue() * 60000
                savedPreferencesLoader.saveAppBlockerWarningInfo(
                    MainActivity.WarningData(
                        binding.warningMsgEdit.text.toString(),
                        selectedMinInMs,
                        binding.cbDynamicWarning.isChecked,
                        binding.cbProceedBtn.isChecked,
                        binding.cbBackWithoutWarning.isChecked,
                        proceedDelay
                    )
                )
                sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER)
                dialog.dismiss()
            }
            .setCancelable(false)
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()

    }

}
