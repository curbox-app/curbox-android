package neth.iecal.curbox.utils

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R
import java.util.Locale

object LanguageUtils {

    // Kept in sync by hand with the res/values-xx folders that actually ship
    // translated strings.xml. Update this list when a translation is added
    // or removed.
    private val SUPPORTED_LOCALES: List<Locale> = listOf(
        Locale.forLanguageTag("en"),
        Locale.forLanguageTag("ar"),
        Locale.forLanguageTag("es"),
        Locale.forLanguageTag("it"),
        Locale.forLanguageTag("ja"),
        Locale.forLanguageTag("pt-BR"),
        Locale.forLanguageTag("ru"),
        Locale.forLanguageTag("zh-CN"),
        Locale.forLanguageTag("vi")
    )

    fun currentLocale(): Locale {
        val applied = AppCompatDelegate.getApplicationLocales()
        if (!applied.isEmpty) return applied[0] ?: Locale.getDefault()
        return Locale.getDefault()
    }

    fun setAppLocale(locale: Locale) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.toLanguageTag()))
    }

    // Shows the active language on `label` and opens a single choice dialog
    // from `clickTarget` to switch it. Applying a new locale recreates the
    // hosting activity, so the label does not need a manual refresh after pick.
    fun bindLanguageSelector(clickTarget: View, label: TextView) {
        val context = clickTarget.context
        val current = currentLocale()
        val currentLocaleEntry = SUPPORTED_LOCALES.firstOrNull { it.language == current.language }
            ?: SUPPORTED_LOCALES[0]
        label.text = currentLocaleEntry.getDisplayName(currentLocaleEntry)

        clickTarget.setOnClickListener {
            val names = SUPPORTED_LOCALES.map { it.getDisplayName(it) }.toTypedArray()
            val checkedIndex = SUPPORTED_LOCALES.indexOf(currentLocaleEntry)

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.language)
                .setSingleChoiceItems(names, checkedIndex) { dialog, which ->
                    setAppLocale(SUPPORTED_LOCALES[which])
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }
}
