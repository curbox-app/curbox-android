package nethical.digipaws.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.blockers.FocusModeBlocker
import nethical.digipaws.databinding.DialogFocusModeBinding
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.utils.NotificationTimerManager
import nethical.digipaws.utils.SavedPreferencesLoader

class StartFocusMode(savedPreferencesLoader: SavedPreferencesLoader,private val onPositiveButtonPressed: () -> Unit) : BaseDialog(
    savedPreferencesLoader
) {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Inflate the custom dialog layout
        val dialogFocusModeBinding = DialogFocusModeBinding.inflate(layoutInflater)
        val previousData = savedPreferencesLoader?.getFocusModeData()

        // Initialize hours and minutes pickers
        dialogFocusModeBinding.focusModeHoursPicker.minValue = 0
        dialogFocusModeBinding.focusModeHoursPicker.maxValue = 99
        dialogFocusModeBinding.focusModeHoursPicker.setValue(0)
        dialogFocusModeBinding.focusModeHoursPicker.setUnit("hours")

        dialogFocusModeBinding.focusModeMinsPicker.minValue = 0
        dialogFocusModeBinding.focusModeMinsPicker.maxValue = 59
        dialogFocusModeBinding.focusModeMinsPicker.setValue(25)
        dialogFocusModeBinding.focusModeMinsPicker.setUnit("mins")

        var selectedMode = previousData?.modeType
        if (previousData != null) {
            when (previousData.modeType) {
                Constants.FOCUS_MODE_BLOCK_SELECTED -> dialogFocusModeBinding.blockSelected.isChecked =
                    true

                Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED -> dialogFocusModeBinding.blockAll.isChecked =
                    true
            }
        }

        dialogFocusModeBinding.modeType.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                dialogFocusModeBinding.blockAll.id -> selectedMode =
                    Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED

                dialogFocusModeBinding.blockSelected.id -> selectedMode =
                    Constants.FOCUS_MODE_BLOCK_SELECTED
            }
        }
        return MaterialAlertDialogBuilder(requireContext())
            .setView(dialogFocusModeBinding.root)
            .setPositiveButton(getString(R.string.start)) { _, _ ->
                val totalMinsMillis = dialogFocusModeBinding.focusModeMinsPicker.getValue() * 60000
                val totalHoursMillis = dialogFocusModeBinding.focusModeHoursPicker.getValue() * 3600000
                val totalMillis = totalMinsMillis + totalHoursMillis
                println("The minute millis is: $totalMinsMillis")
                println("The hours millis is: $totalHoursMillis")
                savedPreferencesLoader?.saveFocusModeData(
                    FocusModeBlocker.FocusModeData(
                        true,
                        System.currentTimeMillis() + totalMillis,
                        selectedMode!!
                    )
                )
                sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_FOCUS_MODE)
                val timer = NotificationTimerManager(requireContext())
                // TODO: add notification permission check
                timer.startTimer(totalMillis.toLong())
                onPositiveButtonPressed()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

}
