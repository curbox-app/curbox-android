package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentAdvancedAnalyticsBinding
import neth.iecal.curbox.databinding.ItemAnalyticsRowBinding
import neth.iecal.curbox.databinding.ItemAnalyticsStatBinding
import neth.iecal.curbox.utils.TimeTools
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A detailed look at how time is spent: daily and hourly patterns, a weekday by hour heatmap,
 * app categories, every app, websites, short videos, focus sessions and mindful pauses.
 */
class AdvancedAnalyticsFragment : Fragment() {

    private var _binding: FragmentAdvancedAnalyticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AdvancedAnalyticsViewModel by viewModels()

    private var showAllApps = false

    private val rangeChips by lazy {
        mapOf(
            R.id.chip_7_days to 7,
            R.id.chip_14_days to 14,
            R.id.chip_30_days to 30,
            R.id.chip_90_days to 90
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdvancedAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        rangeChips.entries.firstOrNull { it.value == viewModel.rangeDays }?.let {
            binding.chipGroupRange.check(it.key)
        }
        binding.chipGroupRange.setOnCheckedStateChangeListener { _, checkedIds ->
            val days = checkedIds.firstOrNull()?.let { rangeChips[it] } ?: return@setOnCheckedStateChangeListener
            viewModel.load(days)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
        viewModel.load()
    }

    private fun render(state: AdvancedAnalyticsViewModel.State) {
        when (state) {
            AdvancedAnalyticsViewModel.State.Loading -> {
                binding.progressLoading.isVisible = true
                binding.analyticsContent.isVisible = false
                binding.emptyView.isVisible = false
            }
            is AdvancedAnalyticsViewModel.State.Ready -> {
                val report = state.report
                binding.progressLoading.isVisible = false
                binding.textRange.text = getString(
                    R.string.analytics_range_label,
                    report.start.format(fullDayFormat),
                    report.end.format(fullDayFormat)
                )
                binding.emptyView.isVisible = !report.hasUsage
                binding.analyticsContent.isVisible = report.hasUsage
                if (report.hasUsage) {
                    bindReport(report)
                    binding.analyticsContent.alpha = 0f
                    binding.analyticsContent.animate().alpha(1f).setDuration(250).start()
                }
            }
        }
    }

    private fun bindReport(report: AdvancedAnalyticsReport) {
        bindOverview(report)
        bindDays(report)
        bindHours(report)
        bindWeek(report)
        bindCategories(report)
        bindApps(report)
        bindWebsites(report)
        bindReels(report)
        bindFocus(report)
    }

    private fun bindOverview(report: AdvancedAnalyticsReport) {
        val changeValue: String
        val changeDetail: String?
        if (report.previousTotalTime > 0L) {
            val percent = ((report.totalTime - report.previousTotalTime) * 100f / report.previousTotalTime).roundToInt()
            val percentText = getString(R.string.analytics_percent, abs(percent))
            changeValue = getString(if (percent >= 0) R.string.analytics_change_up else R.string.analytics_change_down, percentText)
            changeDetail = getString(R.string.analytics_change_detail, report.dayCount)
        } else {
            changeValue = getString(R.string.analytics_change_none)
            changeDetail = null
        }
        val opensPerDay = if (report.dayCount == 0) 0 else report.launches / report.dayCount

        addTiles(
            binding.containerOverview,
            listOf(
                Tile(formatDuration(report.totalTime), getString(R.string.analytics_total_time)),
                Tile(formatDuration(report.dailyAverage), getString(R.string.analytics_daily_average)),
                Tile(changeValue, getString(R.string.analytics_change), changeDetail),
                Tile(getString(R.string.analytics_days_of, report.activeDays, report.dayCount), getString(R.string.analytics_active_days)),
                Tile(report.launches.toString(), getString(R.string.analytics_app_opens),
                    getString(R.string.analytics_per_day, opensPerDay.toString())),
                Tile(formatDuration(report.averageSession), getString(R.string.analytics_average_visit)),
                Tile(hourLabel(report.peakHour), getString(R.string.analytics_peak_hour)),
                Tile(report.appsUsed.toString(), getString(R.string.analytics_apps_used))
            )
        )

        val dayShare = (report.dailyAverage * 100f / DAY_MS).roundToInt()
        val lines = mutableListOf(
            getString(R.string.analytics_summary_total, formatDuration(report.totalTime), formatDuration(report.dailyAverage), dayShare)
        )
        if (report.apps.size > 3) {
            lines += getString(R.string.analytics_summary_top_three, (report.topThreeShare * 100).roundToInt())
        }
        if (report.peakHour >= 0) lines += getString(R.string.analytics_summary_peak, hourLabel(report.peakHour))
        val weekendGap = report.weekendAverage - report.weekdayAverage
        if (report.weekendAverage > 0L && report.weekdayAverage > 0L && abs(weekendGap) >= 60_000L) {
            lines += getString(
                if (weekendGap > 0) R.string.analytics_summary_weekend_more else R.string.analytics_summary_weekend_less,
                formatDuration(abs(weekendGap))
            )
        }
        if (report.partsOfDay[0] >= 60_000L) {
            lines += getString(R.string.analytics_summary_night, formatDuration(report.partsOfDay[0]))
        }
        binding.textSummary.text = lines.joinToString("\n\n")
    }

    private fun bindDays(report: AdvancedAnalyticsReport) {
        val totals = report.dailyTotals
        val (labelFormat, labelEvery) = when {
            totals.size <= 7 -> weekdayFormat to 1
            totals.size <= 14 -> dayNumberFormat to 1
            totals.size <= 30 -> shortDayFormat to 7
            else -> shortDayFormat to 15
        }
        binding.textDaysHint.text = getString(R.string.analytics_days_hint)
        binding.chartDays.setData(
            values = totals.map { it.totalTime.toFloat() },
            labels = totals.map { it.date.format(labelFormat) },
            labelEvery = labelEvery,
            selected = totals.lastIndex,
            average = report.dailyAverage.toFloat()
        )
        fun showDay(index: Int) {
            val day = totals.getOrNull(index) ?: return
            binding.textDaysSelected.text = getString(
                R.string.analytics_day_selected, day.date.format(fullDayFormat), formatDuration(day.totalTime)
            )
        }
        binding.chartDays.setOnBarSelectedListener(::showDay)
        showDay(totals.lastIndex)

        binding.containerDays.removeAllViews()
        report.busiestDay?.let {
            addRow(binding.containerDays, getString(R.string.analytics_busiest_day), formatDuration(it.totalTime),
                detail = it.date.format(fullDayFormat))
        }
        report.quietestDay?.let {
            addRow(binding.containerDays, getString(R.string.analytics_quietest_day), formatDuration(it.totalTime),
                detail = it.date.format(fullDayFormat))
        }
        val maxAverage = maxOf(report.weekdayAverage, report.weekendAverage).coerceAtLeast(1L)
        addRow(binding.containerDays, getString(R.string.analytics_weekday_average), formatDuration(report.weekdayAverage),
            progress = report.weekdayAverage.toFloat() / maxAverage)
        addRow(binding.containerDays, getString(R.string.analytics_weekend_average), formatDuration(report.weekendAverage),
            progress = report.weekendAverage.toFloat() / maxAverage)
    }

    private fun bindHours(report: AdvancedAnalyticsReport) {
        binding.textHoursHint.text = getString(R.string.analytics_hours_hint)
        val initial = if (report.peakHour >= 0) report.peakHour else LocalTime.now().hour
        binding.chartHours.setData(
            values = report.hourly.map { it.toFloat() },
            labels = (0 until 24).map { hourLabel(it) },
            labelEvery = 6,
            selected = initial
        )
        fun showHour(hour: Int) {
            val total = report.hourly[hour]
            binding.textHoursSelected.text = getString(
                R.string.analytics_hour_selected,
                hourLabel(hour),
                formatDuration(total),
                formatDuration(total / report.dayCount.coerceAtLeast(1))
            )
        }
        binding.chartHours.setOnBarSelectedListener(::showHour)
        showHour(initial)

        val partLabels = listOf(
            R.string.analytics_part_night,
            R.string.analytics_part_morning,
            R.string.analytics_part_afternoon,
            R.string.analytics_part_evening
        )
        val maxPart = report.partsOfDay.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        binding.containerPartsOfDay.removeAllViews()
        report.partsOfDay.forEachIndexed { index, time ->
            addRow(
                binding.containerPartsOfDay,
                getString(partLabels[index]),
                formatDuration(time),
                progress = time.toFloat() / maxPart,
                detail = getString(R.string.analytics_share_of_time, percentOf(time, report.totalTime))
            )
        }
    }

    private fun bindWeek(report: AdvancedAnalyticsReport) {
        binding.textWeekHint.text = getString(R.string.analytics_week_hint)
        val locale = Locale.getDefault()
        val days = DayOfWeek.values().toList()
        binding.heatmapWeek.setData(
            report.weekHeatmap,
            days.map { it.getDisplayName(TextStyle.SHORT, locale) },
            listOf(0, 6, 12, 18).associateWith { hourLabel(it) }
        )
        fun showCell(row: Int, column: Int) {
            binding.textWeekSelected.text = getString(
                R.string.analytics_week_selected,
                days[row].getDisplayName(TextStyle.FULL, locale),
                hourLabel(column),
                formatDuration(report.weekHeatmap[row][column])
            )
        }
        binding.heatmapWeek.setOnCellSelectedListener(::showCell)
        var busiest = 0 to 0
        report.weekHeatmap.forEachIndexed { row, values ->
            values.forEachIndexed { column, value ->
                if (value > report.weekHeatmap[busiest.first][busiest.second]) busiest = row to column
            }
        }
        showCell(busiest.first, busiest.second)
    }

    private fun bindCategories(report: AdvancedAnalyticsReport) {
        binding.containerCategories.removeAllViews()
        val max = report.categories.firstOrNull()?.totalTime?.coerceAtLeast(1L) ?: 1L
        report.categories.forEach { category ->
            addRow(
                binding.containerCategories,
                category.category,
                formatDuration(category.totalTime),
                progress = category.totalTime.toFloat() / max,
                detail = joinDetails(
                    getString(R.string.analytics_share_of_time, (category.share * 100).roundToInt()),
                    resources.getQuantityString(R.plurals.analytics_apps_count, category.apps, category.apps)
                )
            )
        }
    }

    private fun bindApps(report: AdvancedAnalyticsReport) {
        fun populate() {
            binding.containerApps.removeAllViews()
            val max = report.apps.firstOrNull()?.totalTime?.coerceAtLeast(1L) ?: 1L
            val visible = if (showAllApps) report.apps else report.apps.take(APPS_PREVIEW_COUNT)
            visible.forEach { app ->
                val info = viewModel.appInfo(app.packageName)
                val trend = when {
                    app.previousTime <= 0L -> getString(R.string.analytics_trend_new)
                    app.totalTime >= app.previousTime ->
                        getString(R.string.analytics_trend_up, formatDuration(app.totalTime - app.previousTime))
                    else -> getString(R.string.analytics_trend_down, formatDuration(app.previousTime - app.totalTime))
                }
                val firstLine = joinDetails(
                    getString(R.string.analytics_share_of_time, (app.share * 100).roundToInt()),
                    getString(R.string.analytics_per_day, formatDuration(app.totalTime / report.dayCount.coerceAtLeast(1))),
                    getString(R.string.analytics_used_on_days, app.daysUsed, report.dayCount)
                )
                val secondLine = joinDetails(
                    resources.getQuantityString(R.plurals.analytics_opens, app.launches, app.launches),
                    if (app.launches > 0) getString(R.string.analytics_each_visit, formatDuration(app.totalTime / app.launches)) else null,
                    if (app.peakHour >= 0) getString(R.string.analytics_busiest_around, hourLabel(app.peakHour)) else null
                )
                val thirdLine = joinDetails(
                    app.longestDay?.let {
                        getString(R.string.analytics_longest_day, it.date.format(shortDayFormat), formatDuration(it.totalTime))
                    },
                    trend
                )
                addRow(
                    binding.containerApps,
                    info.label,
                    formatDuration(app.totalTime),
                    progress = app.totalTime.toFloat() / max,
                    detail = listOf(firstLine, secondLine, thirdLine).joinToString("\n"),
                    icon = info.icon ?: requireContext().getDrawable(R.drawable.logo)
                )
            }
            binding.btnShowAllApps.isVisible = report.apps.size > APPS_PREVIEW_COUNT
            binding.btnShowAllApps.setText(if (showAllApps) R.string.analytics_show_fewer_apps else R.string.analytics_show_all_apps)
        }
        binding.btnShowAllApps.setOnClickListener {
            showAllApps = !showAllApps
            populate()
        }
        populate()
    }

    private fun bindWebsites(report: AdvancedAnalyticsReport) {
        binding.containerWebsites.removeAllViews()
        if (report.websites.isEmpty()) {
            addMessage(binding.containerWebsites, getString(R.string.analytics_no_websites))
            return
        }
        val max = report.websites.first().totalTime.coerceAtLeast(1L)
        report.websites.take(WEBSITES_COUNT).forEach { site ->
            addRow(
                binding.containerWebsites,
                site.domain,
                formatDuration(site.totalTime),
                progress = site.totalTime.toFloat() / max,
                detail = joinDetails(
                    getString(R.string.analytics_share_of_browsing, (site.share * 100).roundToInt()),
                    getString(R.string.analytics_visited_on_days, site.daysVisited, report.dayCount),
                    if (site.peakHour >= 0) getString(R.string.analytics_busiest_around, hourLabel(site.peakHour)) else null
                )
            )
        }
    }

    private fun bindReels(report: AdvancedAnalyticsReport) {
        val perVideo = if (report.reelCount > 0) report.reelTime / report.reelCount else 0L
        addTiles(
            binding.containerReelStats,
            listOf(
                Tile(report.reelCount.toString(), getString(R.string.analytics_reels_watched)),
                Tile(formatDuration(report.reelTime), getString(R.string.analytics_reels_time)),
                Tile(getString(R.string.analytics_seconds, (perVideo / 1000L).toInt()), getString(R.string.analytics_reels_average))
            ),
            perRow = 3
        )
        binding.containerReels.removeAllViews()
        if (report.reelApps.isEmpty()) {
            addMessage(binding.containerReels, getString(R.string.analytics_no_reels))
            return
        }
        val max = report.reelApps.first().totalTime.coerceAtLeast(1L)
        report.reelApps.forEach { reel ->
            val info = viewModel.appInfo(reel.packageName)
            addRow(
                binding.containerReels,
                info.label,
                formatDuration(reel.totalTime),
                progress = reel.totalTime.toFloat() / max,
                detail = resources.getQuantityString(R.plurals.analytics_videos_count, reel.count, reel.count),
                icon = info.icon
            )
        }
    }

    private fun bindFocus(report: AdvancedAnalyticsReport) {
        addTiles(
            binding.containerFocusStats,
            listOf(
                Tile(report.focusSessions.toString(), getString(R.string.analytics_focus_sessions)),
                Tile(formatDuration(report.focusTime), getString(R.string.analytics_focus_time)),
                Tile(report.focusCompleted.toString(), getString(R.string.analytics_focus_finished)),
                Tile(report.focusStopped.toString(), getString(R.string.analytics_focus_ended_early)),
                Tile(report.intentCount.toString(), getString(R.string.analytics_mindful_pauses)),
                Tile(formatDuration(report.intentUnlockedTime), getString(R.string.analytics_time_unlocked))
            )
        )
        binding.containerIntents.removeAllViews()
        if (report.intentApps.isEmpty()) {
            addMessage(binding.containerIntents, getString(R.string.analytics_no_intents))
            return
        }
        val max = report.intentApps.first().second.coerceAtLeast(1)
        report.intentApps.forEach { (pkg, count) ->
            val info = viewModel.appInfo(pkg)
            addRow(
                binding.containerIntents,
                info.label,
                resources.getQuantityString(R.plurals.analytics_pauses_count, count, count),
                progress = count.toFloat() / max,
                icon = info.icon
            )
        }
    }

    private data class Tile(val value: String, val label: String, val detail: String? = null)

    private fun addTiles(container: LinearLayout, tiles: List<Tile>, perRow: Int = 2) {
        container.removeAllViews()
        val gap = (8 * resources.displayMetrics.density).toInt()
        tiles.chunked(perRow).forEachIndexed { rowIndex, rowTiles ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = gap })
            rowTiles.forEachIndexed { index, tile ->
                val item = ItemAnalyticsStatBinding.inflate(layoutInflater, row, false)
                item.statValue.text = tile.value
                item.statLabel.text = tile.label
                item.statDetail.isVisible = tile.detail != null
                item.statDetail.text = tile.detail
                (item.root.layoutParams as LinearLayout.LayoutParams).apply {
                    if (index > 0) marginStart = gap
                }
                row.addView(item.root)
            }
        }
    }

    private fun addRow(
        container: LinearLayout,
        label: CharSequence,
        value: String,
        progress: Float? = null,
        detail: String? = null,
        icon: android.graphics.drawable.Drawable? = null
    ) {
        val row = ItemAnalyticsRowBinding.inflate(layoutInflater, container, false)
        row.rowLabel.text = label
        row.rowValue.text = value
        row.rowProgress.isVisible = progress != null
        progress?.let { row.rowProgress.setProgressCompat((it * 1000).roundToInt().coerceIn(0, 1000), true) }
        row.rowDetail.isVisible = !detail.isNullOrEmpty()
        row.rowDetail.text = detail
        row.rowIcon.isVisible = icon != null
        row.rowIcon.setImageDrawable(icon)
        container.addView(row.root)
    }

    private fun addMessage(container: LinearLayout, message: String) {
        val row = ItemAnalyticsRowBinding.inflate(layoutInflater, container, false)
        row.rowLabel.text = message
        row.rowLabel.maxLines = 3
        row.rowLabel.setTextColor(row.rowDetail.currentTextColor)
        row.rowProgress.isVisible = false
        container.addView(row.root)
    }

    private fun joinDetails(vararg parts: String?): String =
        parts.filterNot { it.isNullOrEmpty() }.joinToString(getString(R.string.analytics_detail_separator))

    private fun formatDuration(ms: Long): String =
        if (ms < 60_000L) getString(R.string.analytics_under_minute) else TimeTools.formatTime(ms, showSeconds = false)

    private fun hourLabel(hour: Int): String =
        if (hour < 0) getString(R.string.analytics_none) else LocalTime.of(hour, 0).format(hourFormat)

    private fun percentOf(part: Long, total: Long): Int =
        if (total <= 0L) 0 else (part * 100f / total).roundToInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "advanced_analytics_fragment"
        private const val APPS_PREVIEW_COUNT = 10
        private const val WEBSITES_COUNT = 25
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private val hourFormat = DateTimeFormatter.ofPattern("h a")
        private val weekdayFormat = DateTimeFormatter.ofPattern("EEE")
        private val dayNumberFormat = DateTimeFormatter.ofPattern("d")
        private val shortDayFormat = DateTimeFormatter.ofPattern("MMM d")
        private val fullDayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
    }
}
