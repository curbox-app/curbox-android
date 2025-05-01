package nethical.digipaws.ui.dialogs

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.databinding.DialogSelectLangBinding
// import nethical.digipaws.services.GeneralFeaturesService
// import nethical.digipaws.utils.GrayscaleControl
import nethical.digipaws.utils.SavedPreferencesLoader

class SelectLang(
    savedPreferencesLoader: SavedPreferencesLoader
) : BaseDialog(savedPreferencesLoader) {

    private lateinit var trackerPreferences: SharedPreferences

    @SuppressLint("ApplySharedPref")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogSelectLangBinding = DialogSelectLangBinding.inflate(layoutInflater)

        // Load tracker preferences
        trackerPreferences =
            requireContext().getSharedPreferences("lang", Context.MODE_PRIVATE)
        val getMode = trackerPreferences.getInt("language",Constants.GRAYSCALE_MODE_ONLY_SELECTED)



        when(getMode){
            Constants.FR_SELECTED -> {
                dialogSelectLangBinding.fr_lang.isChecked = true
            }
            Constants.EN_SELECTED -> {
                dialogSelectLangBinding.en_lang.isChecked = true
            }
            Constants.HELLO -> {
                dialogSelectLangBinding.hello.isChecked = true
            }
        }

        // Build and display dialog
        return MaterialAlertDialogBuilder(requireContext())
            .setView(dialogGrayscaleBinding.root)
            .setCancelable(trye)
            .setPositiveButton(getString(R.string.apply)) { dialog, _ ->
                when(dialogSelectLangBinding.modeType.checkedRadioButtonId){
                    dialogSelectLangBinding.fr_lanh.id -> {
                        trackerPreferences.edit().putInt("language",Constants.FR_SELECTED).commit()
                        // val grayscaleControl = GrayscaleControl()
                        // grayscaleControl.enableGrayscale()
                    }
                    dialogSelectLangBinding.en_lang.id -> {
                        trackerPreferences.edit().putInt("language",Constants.EN_SELECTED).commit()
                        val grayscaleControl = GrayscaleControl()
                        grayscaleControl.disableGrayscale()
                    }
                    // dialogSelectLangBinding.blockSelected.id -> {
                    //     trackerPreferences.edit().putInt("mode",Constants.GRAYSCALE_MODE_ONLY_SELECTED).commit()
                    // }
                    // dialogGrayscaleBinding.blockExceptSelected.id -> {
                    //     trackerPreferences.edit().putInt("mode",Constants.GRAYSCALE_MODE_ALL_EXCEPT_SELECTED).commit()
                    // }

                }
                // Send broadcast to refresh UsageTrackingService
                // sendRefreshRequest(GeneralFeaturesService.INTENT_ACTION_REFRESH_GRAYSCALE)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
    }
}
