package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.content.DialogInterface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.data.models.fixOvernightInterval
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.DayAdapter
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.DayItem
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.TimeIntervalAdapter
import neth.iecal.curbox.utils.hasOverlappingTimeRanges
import neth.iecal.curbox.utils.isAllDaySchedule

abstract class BaseTimeSettingsFragment : BottomSheetDialogFragment() {

    private enum class ScheduleMode {
        AllDay,
        Daily,
        Custom
    }

    protected open val daysOfWeek: List<String>
        get() = resources.getStringArray(R.array.days_of_week_sunday_first).toList()

    private lateinit var switchEveryDay: CompoundButton
    private lateinit var everydayContainer: View
    private lateinit var btnAddEverydayInterval: View
    private lateinit var everydayIntervalsContainer: RecyclerView
    private lateinit var daysListContainer: RecyclerView
    private var scheduleModeToggle: MaterialButtonToggleGroup? = null
    private var scheduleMode = ScheduleMode.AllDay
    private var isLoadingSettings = false

    private val everydayIntervals = mutableListOf<TimeInterval>()
    private lateinit var everydayAdapter: TimeIntervalAdapter

    private val dayItems = mutableListOf<DayItem>()
    private lateinit var daysAdapter: DayAdapter

    protected abstract fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View
    protected abstract fun getTimeConfig(): AppTimeConfig
    protected abstract fun saveTimeConfig(config: AppTimeConfig)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflateView(inflater, container)
        switchEveryDay = root.findViewById(R.id.switch_every_day)
        everydayContainer = root.findViewById(R.id.everydayContainer)
        btnAddEverydayInterval = root.findViewById(R.id.btn_add_everyday_interval)
        everydayIntervalsContainer = root.findViewById(R.id.everydayIntervalsContainer)
        daysListContainer = root.findViewById(R.id.daysListContainer)
        scheduleModeToggle = root.findViewById(R.id.schedule_mode_toggle)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()

        if (scheduleModeToggle == null) {
            switchEveryDay.setOnCheckedChangeListener { _, isChecked ->
                everydayContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                daysAdapter.isInteractionEnabled = !isChecked
            }
        } else {
            scheduleModeToggle?.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val selectedMode = when (checkedId) {
                    R.id.btn_schedule_daily -> ScheduleMode.Daily
                    R.id.btn_schedule_custom -> ScheduleMode.Custom
                    else -> ScheduleMode.AllDay
                }
                selectScheduleMode(selectedMode, initialize = !isLoadingSettings)
            }
        }

        btnAddEverydayInterval.setOnClickListener {
            addInterval(everydayIntervals) {
                everydayAdapter.notifyItemInserted(everydayIntervals.size - 1)
            }
        }

        loadExistingSettings()
    }

    override fun onDismiss(dialog: DialogInterface) {
        persistSettings()
        super.onDismiss(dialog)
    }

    private fun setupRecyclerViews() {
        everydayAdapter = TimeIntervalAdapter(
            everydayIntervals,
            onTimeClick = { interval, isStart, _ ->
                showTimePicker(interval, isStart, everydayIntervals) {
                    everydayAdapter.notifyDataSetChanged()
                }
            },
            onRemove = { position ->
                everydayIntervals.removeAt(position)
                everydayAdapter.notifyItemRemoved(position)
            }
        )
        everydayIntervalsContainer.layoutManager = LinearLayoutManager(requireContext())
        everydayIntervalsContainer.adapter = everydayAdapter

        dayItems.clear()
        daysOfWeek.forEachIndexed { index, day ->
            dayItems.add(DayItem(day, index, false, mutableListOf()))
        }

        daysAdapter = DayAdapter(
            dayItems,
            onAddTimeInterval = { dayItem, dayPosition ->
                addInterval(dayItem.intervals) {
                    daysAdapter.notifyItemChanged(dayPosition)
                }
            },
            onTimeClick = { interval, isStart, dayPosition, _ ->
                showTimePicker(interval, isStart, dayItems[dayPosition].intervals) { daysAdapter.notifyItemChanged(dayPosition) }
            },
            onRemoveInterval = { dayPosition, intervalPosition ->
                dayItems[dayPosition].intervals.removeAt(intervalPosition)
                daysAdapter.notifyItemChanged(dayPosition)
            },
            onDisabledClick = {
                Toast.makeText(requireContext(), R.string.time_disable_everyday_granular, Toast.LENGTH_SHORT).show()
            }
        )
        daysListContainer.layoutManager = LinearLayoutManager(requireContext())
        daysListContainer.adapter = daysAdapter
    }

    private fun loadExistingSettings() {
        val config = getTimeConfig()

        everydayIntervals.clear()
        everydayIntervals.addAll(config.everydayIntervals.map { it.copy() })
        everydayAdapter.notifyDataSetChanged()

        dayItems.forEach { dayItem ->
            val intervals = config.dailyIntervals[dayItem.dayIndex] ?: mutableListOf()
            dayItem.isActive = intervals.isNotEmpty()
            dayItem.intervals.clear()
            dayItem.intervals.addAll(intervals.map { it.copy() })
        }
        daysAdapter.notifyDataSetChanged()

        if (scheduleModeToggle == null) {
            switchEveryDay.isChecked = config.isEveryday
            daysAdapter.isInteractionEnabled = !config.isEveryday
            everydayContainer.visibility = if (config.isEveryday) View.VISIBLE else View.GONE
            daysListContainer.visibility = View.VISIBLE
            return
        }

        scheduleMode = when {
            config.isAllDaySchedule() -> ScheduleMode.AllDay
            config.isEveryday -> ScheduleMode.Daily
            else -> ScheduleMode.Custom
        }
        isLoadingSettings = true
        scheduleModeToggle?.check(
            when (scheduleMode) {
                ScheduleMode.AllDay -> R.id.btn_schedule_all_day
                ScheduleMode.Daily -> R.id.btn_schedule_daily
                ScheduleMode.Custom -> R.id.btn_schedule_custom
            }
        )
        isLoadingSettings = false
        selectScheduleMode(scheduleMode, initialize = false)
    }

    private fun persistSettings() {
        val dailyIntervals = dayItems
            .filter { it.isActive }
            .associateTo(mutableMapOf()) { it.dayIndex to it.intervals.map { i -> i.copy() }.toMutableList() }
        val config = if (scheduleModeToggle != null && scheduleMode == ScheduleMode.AllDay) {
            AppTimeConfig.allDay()
        } else {
            AppTimeConfig(
                isEveryday = switchEveryDay.isChecked,
                everydayIntervals = everydayIntervals.map { it.copy() }.toMutableList(),
                dailyIntervals = dailyIntervals
            )
        }
        if (config.hasOverlappingTimeRanges()) {
            Toast.makeText(
                requireContext(),
                R.string.schedule_overlap_not_saved,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        saveTimeConfig(config)
    }

    private fun showTimePicker(interval: TimeInterval, isStart: Boolean, list: MutableList<TimeInterval>, onComplete: () -> Unit) {
        val clockFormat = if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
        val hour = (if (isStart) interval.startHour else interval.endHour) % 24
        val minute = if (isStart) interval.startMinute else interval.endMinute
        val title = getString(
            if (isStart) R.string.select_start_time else R.string.select_end_time
        )
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(clockFormat)
            .setHour(hour)
            .setMinute(minute)
            .setTitleText(title)
            .build()

        picker.addOnPositiveButtonClickListener {
            val previousIntervals = list.map(TimeInterval::copy)
            if (isStart) {
                interval.startHour = picker.hour
                interval.startMinute = picker.minute
            } else {
                interval.endHour =
                    if (picker.hour == 0 && picker.minute == 0) 24 else picker.hour
                interval.endMinute = picker.minute
            }
            val splitOvernight = list.fixOvernightInterval(interval)
            val hasOverlap = AppTimeConfig(
                isEveryday = true,
                everydayIntervals = list
            ).hasOverlappingTimeRanges()
            if (hasOverlap) {
                list.clear()
                list.addAll(previousIntervals)
                Toast.makeText(
                    requireContext(),
                    R.string.schedule_overlap_not_saved,
                    Toast.LENGTH_LONG
                ).show()
            } else if (splitOvernight) {
                Toast.makeText(requireContext(), R.string.time_overnight_split, Toast.LENGTH_LONG).show()
            }
            onComplete()
        }

        picker.show(childFragmentManager, "time_picker")
    }

    private fun selectScheduleMode(mode: ScheduleMode, initialize: Boolean) {
        scheduleMode = mode
        if (initialize && mode == ScheduleMode.Daily) {
            val current = AppTimeConfig(
                isEveryday = true,
                everydayIntervals = everydayIntervals
            )
            if (everydayIntervals.isEmpty() || current.isAllDaySchedule()) {
                everydayIntervals.clear()
                everydayIntervals.add(TimeInterval(9, 0, 17, 0))
                everydayAdapter.notifyDataSetChanged()
            }
        }
        switchEveryDay.isChecked = mode != ScheduleMode.Custom
        everydayContainer.visibility = if (mode == ScheduleMode.Daily) View.VISIBLE else View.GONE
        daysListContainer.visibility = if (mode == ScheduleMode.Custom) View.VISIBLE else View.GONE
        daysAdapter.isInteractionEnabled = mode == ScheduleMode.Custom
    }

    private fun addInterval(intervals: MutableList<TimeInterval>, onAdded: () -> Unit) {
        val interval = nextAvailableInterval(intervals)
        if (interval == null) {
            Toast.makeText(
                requireContext(),
                R.string.schedule_no_room_for_range,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        intervals.add(interval)
        onAdded()
    }

    private fun nextAvailableInterval(intervals: List<TimeInterval>): TimeInterval? {
        if (intervals.isEmpty()) return TimeInterval(9, 0, 17, 0)

        val candidateHours = (17..23) + (0..16)
        return candidateHours
            .map { hour -> TimeInterval(hour, 0, hour + 1, 0) }
            .firstOrNull { candidate ->
                !AppTimeConfig(
                    isEveryday = true,
                    everydayIntervals = (intervals + candidate).toMutableList()
                ).hasOverlappingTimeRanges()
            }
    }
}
