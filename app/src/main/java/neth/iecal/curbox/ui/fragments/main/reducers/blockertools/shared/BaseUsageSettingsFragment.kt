package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.UsageDayItem
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.UsageSettingsAdapter

abstract class BaseUsageSettingsFragment : BottomSheetDialogFragment() {

    protected open val daysOfWeek: List<String>
        get() = resources.getStringArray(R.array.usage_days_of_week).toList()

    private val dayItems = mutableListOf<UsageDayItem>()
    private lateinit var adapter: UsageSettingsAdapter
    private lateinit var daysListContainer: RecyclerView

    protected abstract fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View
    protected abstract fun loadUsageConfig(): AppUsageConfig
    protected abstract fun saveUsageConfig(config: AppUsageConfig)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflateView(inflater, container)
        daysListContainer = root.findViewById(R.id.daysListContainer)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        populateFromConfig()
    }

    override fun onDismiss(dialog: DialogInterface) {
        persistConfig()
        super.onDismiss(dialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also {
            it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun setupRecyclerView() {
        dayItems.clear()
        daysOfWeek.forEach { dayItems.add(UsageDayItem(it, false, 0, 0)) }

        adapter = UsageSettingsAdapter(
            dayItems,
            onUniformToggle = { isUniform -> handleUniformLimitToggle(isUniform) },
            onDisabledClick = {
                Toast.makeText(requireContext(), R.string.usage_disable_everyday_granular, Toast.LENGTH_SHORT).show()
            }
        )
        daysListContainer.layoutManager = LinearLayoutManager(requireContext())
        daysListContainer.adapter = adapter
    }

    private fun populateFromConfig() {
        val config = loadUsageConfig()

        dayItems[0].isEnabled = config.isDailyUniform
        dayItems[0].hours = (config.uniformLimit / 60).toInt()
        dayItems[0].minutes = (config.uniformLimit % 60).toInt()

        // dayItems 1-7 = Mon-Sun; dailyLimits[1-6] = Mon-Sat, dailyLimits[0] = Sun
        for (i in 1..6) setDayItem(i, config.dailyLimits[i])
        setDayItem(7, config.dailyLimits[0])

        handleUniformLimitToggle(config.isDailyUniform)
        adapter.notifyDataSetChanged()
    }

    private fun setDayItem(itemIndex: Int, minutesLimit: Long) {
        val item = dayItems[itemIndex]
        item.isEnabled = minutesLimit > 0
        item.hours = (minutesLimit / 60).toInt()
        item.minutes = (minutesLimit % 60).toInt()
    }

    private fun handleUniformLimitToggle(isUniform: Boolean) {
        dayItems.drop(1).forEach { it.isInteractionEnabled = !isUniform }
        if (isUniform) dayItems.drop(1).forEach { it.isEnabled = false }
        adapter.notifyItemRangeChanged(1, dayItems.size - 1)
    }

    private fun persistConfig() {
        val isDailyUniform = dayItems[0].isEnabled
        val config = AppUsageConfig(
            isDailyUniform = isDailyUniform,
            uniformLimit = if (isDailyUniform) (dayItems[0].hours * 60 + dayItems[0].minutes).toLong() else 0L
        )

        if (!isDailyUniform) {
            for (i in 1..6) config.dailyLimits[i] = (dayItems[i].hours * 60 + dayItems[i].minutes).toLong()
            config.dailyLimits[0] = (dayItems[7].hours * 60 + dayItems[7].minutes).toLong()
        }

        saveUsageConfig(config)
    }
}
