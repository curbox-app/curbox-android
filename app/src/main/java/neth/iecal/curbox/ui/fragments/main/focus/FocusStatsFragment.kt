package neth.iecal.curbox.ui.fragments.main.focus

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.FocusStatsEntity
import neth.iecal.curbox.databinding.FragmentFocusStatsBinding
import neth.iecal.curbox.databinding.ItemAnalyticsRowBinding
import neth.iecal.curbox.databinding.ItemFocusStatBinding
import neth.iecal.curbox.ui.views.WeeklyBarGraphView
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

class FocusStatsFragment : Fragment() {

    private var _binding: FragmentFocusStatsBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel with FocusFragment to get stats
    private val viewModel: FocusViewModel by activityViewModels()

    private var selectedGroupId: String? = null // null means "All Groups"
    private var rangeDays: Int? = 7 // null means all time
    private var latestSessions: List<FocusStatsEntity>? = null
    private var weekFocusByDay: Map<Long, Long> = emptyMap()
    private var renderJob: Job? = null

    private val rangeChips by lazy {
        mapOf(
            R.id.chip7Days to 7,
            R.id.chip30Days to 30,
            R.id.chipAllTime to null
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFocusStatsBinding.inflate(inflater, container, false)

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setLabel(binding.statTotalTime, R.string.focus_total_time_label)
        setLabel(binding.statDailyAverage, R.string.focus_stats_daily_average)
        setLabel(binding.statSessions, R.string.focus_sessions_label)
        setLabel(binding.statCompletion, R.string.focus_completion_rate)
        setLabel(binding.statAvgSession, R.string.focus_avg_duration)
        setLabel(binding.statLongest, R.string.focus_stats_longest)
        setLabel(binding.statPomodoros, R.string.focus_stats_pomodoros)
        setLabel(binding.statPomodoroRounds, R.string.focus_stats_rounds)
        setLabel(binding.statPomodoroTime, R.string.focus_stats_focus_time)

        binding.chipGroupRange.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            rangeDays = rangeChips[checkedId]
            render()
        }

        binding.weeklyBarGraph.setOnBarSelectedListener { bar -> showDay(bar) }

        setupGroupFilter()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allSessions.collectLatest { sessions ->
                    latestSessions = sessions
                    render()
                }
            }
        }
    }

    private fun setLabel(stat: ItemFocusStatBinding, label: Int) {
        stat.tvLabel.setText(label)
    }

    private fun setupGroupFilter() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collectLatest {
                    updateGroupChip()
                    render()
                }
            }
        }

        val showMenu = View.OnClickListener { showGroupMenu() }
        binding.chipGroup.setOnClickListener(showMenu)
        binding.chipGroup.setOnCloseIconClickListener(showMenu)
    }

    private fun showGroupMenu() {
        val options = groupOptions()
        val popup = PopupMenu(requireContext(), binding.chipGroup)
        options.forEachIndexed { index, option -> popup.menu.add(0, index, index, option.second) }
        popup.setOnMenuItemClickListener { item ->
            selectedGroupId = options.getOrNull(item.itemId)?.first
            updateGroupChip()
            render()
            true
        }
        popup.show()
    }

    // Auto DND groups never record focus sessions, so only manual groups are offered.
    private fun groupOptions(): List<Pair<String?, String>> =
        listOf<Pair<String?, String>>(null to getString(R.string.all_groups)) +
            viewModel.groups.value.map { it.groupId to it.groupName }

    private fun updateGroupChip() {
        if (!isAdded) return
        val options = groupOptions()
        if (options.none { it.first == selectedGroupId }) selectedGroupId = null
        binding.chipGroup.text = options.first { it.first == selectedGroupId }.second
    }

    private fun render() {
        val groupId = selectedGroupId
        val days = rangeDays
        val sessions = latestSessions ?: return
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) {
                FocusStatsCalculator.build(
                    rows = sessions.filter { groupId == null || it.groupId == groupId },
                    rangeDays = days,
                    now = System.currentTimeMillis(),
                    zone = ZoneId.systemDefault()
                )
            }
            if (_binding != null) showReport(report)
        }
    }

    private fun showReport(report: FocusStatsCalculator.Report) {
        val hasSessions = report.sessionCount > 0

        updateWeeklyGraph(report.lastSevenDays)
        updateStreak(report)

        binding.tvEmpty.isVisible = !hasSessions
        binding.cardOverview.isVisible = hasSessions
        binding.statTotalTime.tvValue.text = formatDuration(report.totalFocusMs)
        binding.statDailyAverage.tvValue.text = formatDuration(report.dailyAverageMs)
        binding.statSessions.tvValue.text = report.sessionCount.toString()
        binding.statCompletion.tvValue.text = getString(R.string.percent_value, report.completionPercent)
        binding.statAvgSession.tvValue.text = formatDuration(report.averageSessionMs)
        binding.statLongest.tvValue.text = formatDuration(report.longestSessionMs)

        binding.sectionPomodoro.isVisible = report.pomodoroCount > 0
        binding.statPomodoros.tvValue.text = report.pomodoroCount.toString()
        binding.statPomodoroRounds.tvValue.text = report.pomodoroRounds.toString()
        binding.statPomodoroTime.tvValue.text = formatDuration(report.pomodoroFocusMs)

        updateTimeOfDay(report)
        updateGroups(report)
        updateRecentSessions(report)
    }

    private fun updateWeeklyGraph(days: List<FocusStatsCalculator.DayTotal>) {
        val zone = ZoneId.systemDefault()
        val bars = days.map { day ->
            WeeklyBarGraphView.BarData(
                label = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                value = day.focusMs / 3_600_000f,
                dateMillis = day.date.atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
        weekFocusByDay = bars.zip(days).associate { (bar, day) -> bar.dateMillis to day.focusMs }
        binding.weeklyBarGraph.setData(bars, selected = bars.lastIndex)
        bars.lastOrNull()?.let { showDay(it) }
    }

    /** The big number shows today until the user taps another day in the graph. */
    private fun showDay(bar: WeeklyBarGraphView.BarData) {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(bar.dateMillis).atZone(zone).toLocalDate()
        val isToday = date == Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
        binding.tvDayLabel.text = if (isToday) {
            getString(R.string.focus_stats_today)
        } else {
            getString(R.string.focus_stats_focused_on, date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
        }
        binding.tvDayValue.text = formatDuration(weekFocusByDay[bar.dateMillis] ?: 0L)
    }

    private fun updateStreak(report: FocusStatsCalculator.Report) {
        val best = resources.getQuantityString(R.plurals.focus_stats_days, report.bestStreak, report.bestStreak)
        binding.tvStreak.isVisible = report.bestStreak > 0
        binding.tvStreak.text = if (report.currentStreak > 0) {
            val current = resources.getQuantityString(R.plurals.focus_stats_days, report.currentStreak, report.currentStreak)
            getString(R.string.focus_stats_streak, current, best)
        } else {
            getString(R.string.focus_stats_best_streak, best)
        }
    }

    private fun updateTimeOfDay(report: FocusStatsCalculator.Report) {
        val total = report.partsOfDay.sum()
        binding.sectionTimeOfDay.isVisible = total > 0
        binding.llTimeOfDay.removeAllViews()
        if (total <= 0) return

        val labels = listOf(
            R.string.analytics_part_night,
            R.string.analytics_part_morning,
            R.string.analytics_part_afternoon,
            R.string.analytics_part_evening
        )
        val maxPart = report.partsOfDay.maxOrNull() ?: 0L
        report.partsOfDay.forEachIndexed { index, ms ->
            addRow(
                container = binding.llTimeOfDay,
                label = getString(labels[index]),
                value = formatDuration(ms),
                progress = if (maxPart > 0) ms.toFloat() / maxPart else 0f
            )
        }
    }

    private fun updateGroups(report: FocusStatsCalculator.Report) {
        binding.sectionGroups.isVisible = selectedGroupId == null && report.groups.isNotEmpty()
        binding.llGroups.removeAllViews()
        if (!binding.sectionGroups.isVisible) return

        val maxGroup = report.groups.maxOf { it.focusMs }
        for (group in report.groups) {
            addRow(
                container = binding.llGroups,
                label = groupName(group.groupId),
                value = formatDuration(group.focusMs),
                progress = if (maxGroup > 0) group.focusMs.toFloat() / maxGroup else 0f,
                detail = resources.getQuantityString(R.plurals.focus_stats_group_detail, group.sessions, group.sessions)
            )
        }
    }

    private fun updateRecentSessions(report: FocusStatsCalculator.Report) {
        binding.sectionRecent.isVisible = report.recentSessions.isNotEmpty()
        binding.llRecent.removeAllViews()

        for (session in report.recentSessions) {
            val whenText = DateUtils.formatDateTime(
                requireContext(),
                session.startMs,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
            )
            val status = getString(
                when {
                    session.isRunning -> R.string.focus_stats_running
                    session.endedEarly -> R.string.focus_stats_ended_early
                    else -> R.string.focus_stats_finished
                }
            )
            val pomodoro = if (session.isPomodoro) {
                resources.getQuantityString(R.plurals.focus_stats_pomodoro_rounds, session.rounds, session.rounds)
            } else null
            addRow(
                container = binding.llRecent,
                label = groupName(session.groupId),
                value = formatDuration(session.focusMs),
                detail = listOfNotNull(whenText, pomodoro, status)
                    .joinToString(getString(R.string.analytics_detail_separator))
            )
        }
    }

    private fun addRow(
        container: LinearLayout,
        label: String,
        value: String,
        progress: Float? = null,
        detail: String? = null
    ) {
        val row = ItemAnalyticsRowBinding.inflate(layoutInflater, container, false)
        row.rowLabel.text = label
        row.rowValue.text = value
        row.rowProgress.isVisible = progress != null
        progress?.let { row.rowProgress.setProgressCompat((it * 1000).roundToInt().coerceIn(0, 1000), false) }
        row.rowDetail.isVisible = !detail.isNullOrEmpty()
        row.rowDetail.text = detail
        container.addView(row.root)
    }

    private fun groupName(groupId: String): String =
        viewModel.groups.value.find { it.groupId == groupId }?.groupName
            ?: getString(R.string.focus_unknown_group)

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000L
        if (totalMinutes < 60) {
            return getString(R.string.focus_duration_minutes_short, totalMinutes)
        }
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (mins > 0) {
            getString(R.string.focus_duration_hours_minutes_short, hours, mins)
        } else {
            getString(R.string.focus_duration_hours_short, hours)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        renderJob = null
        _binding = null
    }
}
