package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.databinding.FragmentAutodndTimeSettingsBinding
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.BaseTimeSettingsFragment

class AutoDndTimeSettingsFragment : BaseTimeSettingsFragment() {

    companion object {
        const val FRAGMENT_ID = "autodnd_time_settings"
    }

    private val viewModel: AutoDndViewModel by activityViewModels()

    override val daysOfWeek: List<String>
        get() = resources.getStringArray(R.array.days_of_week_monday_first).toList()

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentAutodndTimeSettingsBinding.inflate(inflater, container, false).root

    override fun getTimeConfig(): AppTimeConfig = viewModel.currentTimeConfig

    override fun saveTimeConfig(config: AppTimeConfig) {
        viewModel.currentTimeConfig = config
    }
}
