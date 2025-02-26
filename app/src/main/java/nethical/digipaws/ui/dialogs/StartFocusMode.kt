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
        dialogFocusModeBinding.focusModeMinsPicker.setValue(3)
        dialogFocusModeBinding.focusModeMinsPicker.minValue = 2

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
                val totalMillis = dialogFocusModeBinding.focusModeMinsPicker.getValue() * 60000
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
