package nethical.digipaws.ui.dialogs

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.databinding.DialogGrayscaleBinding
import nethical.digipaws.services.GeneralFeaturesService
import nethical.digipaws.utils.GrayscaleControl
import nethical.digipaws.utils.SavedPreferencesLoader

class TweakGrayScaleMode(
    savedPreferencesLoader: SavedPreferencesLoader
) : BaseDialog(savedPreferencesLoader) {

    private lateinit var trackerPreferences: SharedPreferences

    @SuppressLint("ApplySharedPref")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogGrayscaleBinding = DialogGrayscaleBinding.inflate(layoutInflater)

        // Load tracker preferences
        trackerPreferences =
            requireContext().getSharedPreferences("grayscale", Context.MODE_PRIVATE)
        val getMode = trackerPreferences.getInt("mode",Constants.GRAYSCALE_MODE_ONLY_SELECTED)



        when(getMode){
            Constants.GRAYSCALE_MODE_ONLY_SELECTED -> {
                dialogGrayscaleBinding.blockSelected.isChecked = true
            }
            Constants.GRAYSCALE_MODE_ALL -> {
                dialogGrayscaleBinding.blockAll.isChecked = true
            }
            Constants.GRAYSCALE_MODE_ALL_EXCEPT_SELECTED -> {
                dialogGrayscaleBinding.blockExceptSelected.isChecked = true
            }
        }

        // Build and display dialog
        return MaterialAlertDialogBuilder(requireContext())
            .setView(dialogGrayscaleBinding.root)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.save)) { dialog, _ ->
                when(dialogGrayscaleBinding.modeType.checkedRadioButtonId){
                    dialogGrayscaleBinding.blockAll.id -> {
                        trackerPreferences.edit().putInt("mode",Constants.GRAYSCALE_MODE_ALL).commit()
                        val grayscaleControl = GrayscaleControl()
                        grayscaleControl.enableGrayscale()
                    }
                    dialogGrayscaleBinding.turnOff.id -> {
                        trackerPreferences.edit().putInt("mode",Constants.GRAYSCALE_MODE_OFF).commit()
                        val grayscaleControl = GrayscaleControl()
                        grayscaleControl.disableGrayscale()
                    }
                    dialogGrayscaleBinding.blockSelected.id -> {
                        trackerPreferences.edit().putInt("mode",Constants.GRAYSCALE_MODE_ONLY_SELECTED).commit()
                    }
                    dialogGrayscaleBinding.blockExceptSelected.id -> {
                        trackerPreferences.edit().putInt("mode",Constants.GRAYSCALE_MODE_ALL_EXCEPT_SELECTED).commit()
                    }

                }
                // Send broadcast to refresh UsageTrackingService
                sendRefreshRequest(GeneralFeaturesService.INTENT_ACTION_REFRESH_GRAYSCALE)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
    }
}
