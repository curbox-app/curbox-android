package nethical.digipaws.ui.dialogs

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.R
import nethical.digipaws.databinding.DialogTweakBlockerWarningBinding
import nethical.digipaws.services.ViewBlockerService
import nethical.digipaws.ui.activity.MainActivity
import nethical.digipaws.utils.AnimTools.Companion.animateVisibility
import nethical.digipaws.utils.SavedPreferencesLoader

class TweakViewBlockerWarning(
    savedPreferencesLoader: SavedPreferencesLoader
) : BaseDialog(savedPreferencesLoader) {


    @SuppressLint("ApplySharedPref")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding =
            DialogTweakBlockerWarningBinding.inflate(layoutInflater)

        // Configure NumberPicker
        binding.selectMins.minValue = 1
        binding.selectMins.maxValue = 240

        // Show additional checkbox options
        binding.cbBackWithoutWarning.visibility = View.VISIBLE
        binding.cbReelInbox.visibility = View.VISIBLE

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
        val previousData = savedPreferencesLoader!!.loadViewBlockerWarningInfo()
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

        // Load saved preferences
        binding.selectMins.setValue(previousData.timeInterval / 60000)
        binding.warningMsgEdit.setText(previousData.message)
        binding.cbDynamicWarning.isChecked =
            previousData.isDynamicIntervalSettingAllowed
        binding.cbProceedBtn.isChecked = previousData.isProceedDisabled
        binding.cbBackWithoutWarning.isChecked = previousData.isWarningDialogHidden

        // Load additional Reel data
        val addReelData: SharedPreferences =
            requireContext().getSharedPreferences("config_reels", Context.MODE_PRIVATE)
        binding.cbReelInbox.isChecked =
            addReelData.getBoolean("is_reel_inbox", false)

        binding.root.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(300) // Set animation duration in ms
        }

        // Build and show the dialog
        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.save)) { dialog, _ ->
                val selectedMinInMs = binding.selectMins.getValue() * 60000

                // Save data using SavedPreferencesLoader
                savedPreferencesLoader.saveViewBlockerWarningInfo(
                    MainActivity.WarningData(
                        binding.warningMsgEdit.text.toString(),
                        selectedMinInMs,
                        binding.cbDynamicWarning.isChecked,
                        binding.cbProceedBtn.isChecked,
                        binding.cbBackWithoutWarning.isChecked,
                        proceedDelay
                    )
                )

                // Save Reel data to SharedPreferences
                with(addReelData.edit()) {
                    putBoolean(
                        "is_reel_inbox",
                        binding.cbReelInbox.isChecked
                    )
                    commit() // Apply changes immediately
                }

                // Send broadcast to refresh ViewBlockerService
                sendRefreshRequest(ViewBlockerService.INTENT_ACTION_REFRESH_VIEW_BLOCKER)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
    }

}
