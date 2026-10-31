package neth.iecal.curbox.ui.fragments.main.usage

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.utils.ViewUtils
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.db.ReelUsageStatsEntity
import neth.iecal.curbox.databinding.AppUsageItemBinding

import neth.iecal.curbox.databinding.FragmentAllAppUsageBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.ui.widgets.ReelsWidgetProvider
import neth.iecal.curbox.ui.widgets.ScreentimeWidgetProvider
import neth.iecal.curbox.ui.views.WeeklyBarGraphView
import neth.iecal.curbox.utils.ColorUtils
import neth.iecal.curbox.utils.BlurFadeAnimator
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UsageStatsHelper
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class AllAppsUsageFragment : Fragment() {

        companion object {
            const val FRAGMENT_ID = "all_app_usage"
        }

        private var _binding: FragmentAllAppUsageBinding? = null
        private val binding get() = _binding!!

        private lateinit var viewModel: AllAppsUsageViewModel
        private lateinit var usageStatsHelper: UsageStatsHelper
        private var chartTransitionAnimator: ValueAnimator? = null
        private var chartTransitionActive = false
        private var chartTransitionHidden = false
        private var chartTransitionDataReady = false

        val selectIgnoredAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        lifecycleScope.launch(Dispatchers.IO) {
                            DataStoreManager(requireContext()).updateUsageTrackerIgnoredApps(it)
                        }
                        viewModel.ignoredPackages.addAll(it)
                        viewModel.reload()
                    }
                }
            }

        private var csvDataToExport: String = ""

        private val createCsvLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                uri?.let {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            requireContext().contentResolver.openOutputStream(it)?.use { stream ->
                                stream.write(csvDataToExport.toByteArray())
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.data_exported_successfully),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            Log.e("ExportCSV", "Error writing file", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.failed_to_export_data),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }

        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View {
            _binding = FragmentAllAppUsageBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // Right after install there are no stats to show yet, so greet the user instead.
            if (isFreshInstall()) {
                showWelcomeState()
                return
            }

            viewModel = ViewModelProvider(this)[AllAppsUsageViewModel::class.java]
            usageStatsHelper = UsageStatsHelper(requireContext().applicationContext)

            viewLifecycleOwner.lifecycleScope.launch {
                val selectedArt = DataStoreManager(requireContext()).settings.first().usageBackgroundArt
                applyBackgroundArt(selectedArt)
            }
            binding.asciiArt.foreground = createAsciiFade()

            if (!PermissionUtils.hasAllRequiredPermissions(requireContext())) {
                val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                    putExtra("fragment", OnboardingFragment.FRAGMENT_ID)
                }
                startActivity(intent)

            }

            val adapter = AppUsageAdapter(emptyList())
            binding.appUsageRecyclerView.layoutManager = LinearLayoutManager(requireContext())
            binding.appUsageRecyclerView.adapter = adapter

            binding.langowageLink.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://langowage.site")))
            }


            observeViewModel(adapter)

            binding.btnPrevWeek.setOnClickListener {
                viewModel.goToPreviousPeriod()
            }
            binding.btnNextWeek.setOnClickListener {
                viewModel.goToNextPeriod()
            }

            binding.weeklyBarGraph.setOnBarSelectedListener { barData ->
                val index =
                    viewModel.chartData.value?.indexOf(barData) ?: return@setOnBarSelectedListener
                viewModel.selectPeriod(index)
            }
            binding.weeklyBarGraph.enableZoom { zoomLevel ->
                val scale = when (zoomLevel) {
                    WeeklyBarGraphView.ZoomLevel.DAYS -> AppUsageChartScale.DAYS
                    WeeklyBarGraphView.ZoomLevel.WEEKS -> AppUsageChartScale.WEEKS
                }
                viewModel.setChartScale(scale)
            }

            binding.openMenu.setOnClickListener {
                val popupMenu = PopupMenu(requireContext(), binding.openMenu)
                popupMenu.menuInflater.inflate(R.menu.usage_tracker_options, popupMenu.menu)
                popupMenu.menu.findItem(R.id.show_weekly_usage).isChecked =
                    viewModel.chartScale.value == AppUsageChartScale.WEEKS

                popupMenu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.show_weekly_usage -> {
                            viewModel.setChartScale(
                                if (item.isChecked) AppUsageChartScale.DAYS
                                else AppUsageChartScale.WEEKS
                            )
                            true
                        }

                        R.id.open_advanced_analytics -> {
                            startActivity(
                                Intent(requireContext(), FragmentActivity::class.java).putExtra(
                                    "fragment",
                                    neth.iecal.curbox.ui.fragments.main.reducers.analytics.AdvancedAnalyticsFragment.FRAGMENT_ID
                                )
                            )
                            true
                        }

                        R.id.select_background_art -> {
                            showBackgroundArtDialog()
                            true
                        }

                        R.id.select_ignored -> {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val ignoredApps =
                                    DataStoreManager(requireContext()).settings.first().usageTrackerIgnoredApps
                                withContext(Dispatchers.Main) {
                                    val intent =
                                        Intent(requireContext(), SelectAppsActivity::class.java)
                                    intent.putStringArrayListExtra(
                                        "PRE_SELECTED_APPS",
                                        ArrayList(ignoredApps)
                                    )
                                    selectIgnoredAppsLauncher.launch(
                                        intent,
                                        ActivityOptionsCompat.makeCustomAnimation(
                                            requireContext(),
                                            R.anim.fade_in,
                                            R.anim.fade_out
                                        )
                                    )
                                }
                            }
                            true
                        }

                        R.id.export_as_csv -> {

                            val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
                                .setTitleText(getString(R.string.dialog_title_select_export_range))
                                .setSelection(
                                    Pair(
                                        MaterialDatePicker.thisMonthInUtcMilliseconds(),
                                        MaterialDatePicker.todayInUtcMilliseconds()
                                    )
                                )
                                .build()

                            dateRangePicker.addOnPositiveButtonClickListener { selection ->
                                val startDateMs = selection.first
                                val endDateMs = selection.second

                                val options =
                                    arrayOf(
                                        getString(R.string.usage_export_daily_breakdown),
                                        getString(R.string.usage_export_total_summary),
                                        getString(R.string.usage_export_both)
                                    )
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.usage_export_format_title)
                                    .setSingleChoiceItems(options, 0) { dialog, which ->
                                        generateAndExportCsv(startDateMs, endDateMs, which)
                                        dialog.dismiss()
                                    }
                                    .show()
                            }
                            dateRangePicker.show(childFragmentManager, "EXPORT_DATE_picker")
                            true
                        }

                        R.id.refresh_usage_stats -> {
                            viewModel.refresh()
                            true
                        }

                        R.id.add_widget_usage_tracker -> {
                            val appWidgetManager = AppWidgetManager.getInstance(requireContext())

                            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                                val options = arrayOf(getString(R.string.usage_widget_screentime), getString(R.string.usage_widget_reels))
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.usage_widget_pin_title)
                                    .setItems(options) { dialog, which ->
                                        val myProvider = if (which == 0) {
                                            ComponentName(
                                                requireContext(),
                                                ScreentimeWidgetProvider::class.java
                                            )
                                        } else {
                                            ComponentName(
                                                requireContext(),
                                                ReelsWidgetProvider::class.java
                                            )
                                        }
                                        appWidgetManager.requestPinAppWidget(myProvider, null, null)
                                        dialog.dismiss()
                                    }
                                    .show()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.pinning_widgets_is_not_supported_on),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            true
                        }

                        R.id.action_help -> {
                            ViewUtils.showHelpPopup(
                                binding.openMenu,
                                "Track your application usage statistics to understand your digital habits better.",
                                "https://curbox.app/docs/usage/usage-stats/"
                            )
                            true
                        }

                        else -> false
                    }
                }

                popupMenu.show()
            }

            viewModel.initialize()
        }


        private fun isFreshInstall(): Boolean {
            return try {
                val firstInstall = requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).firstInstallTime
                System.currentTimeMillis() - firstInstall < 10 * 60 * 1000L
            } catch (e: Exception) {
                false
            }
        }

        private fun showWelcomeState() {
            binding.main.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE
            binding.welcomeState.visibility = View.VISIBLE
            binding.welcomeAscii.text = getString(R.string.ascii_hello)
            binding.btnReadDocs.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://curbox.app/docs")))
            }

            binding.btnStarRepo.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nethical6/curbox")))
            }

            binding.btnJoinDiscord.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/invite/Vs9mwUtuCN")))
            }
        }

        private fun observeViewModel(adapter: AppUsageAdapter) {
            viewModel.chartData.observe(viewLifecycleOwner) { data ->
                val b = _binding ?: return@observe
                val selectedIdx = viewModel.selectedPeriodIndex.value ?: data.lastIndex
                b.weeklyBarGraph.setData(data, selectedIdx)
            }

            viewModel.selectedPeriodIndex.observe(viewLifecycleOwner) { index ->
                val b = _binding ?: return@observe
                b.weeklyBarGraph.setSelectedIndex(index)
            }

            viewModel.selectedPeriodAnalytics.observe(viewLifecycleOwner) { analytics ->
                val b = _binding ?: return@observe
                adapter.updateData(analytics.apps, analytics.websites, analytics.reels)
                b.totalUsage.text = TimeTools.formatTimeForWidget(analytics.totalTime)
                b.dateSublabel.text = analytics.totalLabel
            }

            viewModel.chartRangeLabel.observe(viewLifecycleOwner) { label ->
                val b = _binding ?: return@observe
                b.tvWeekRange.text = label
            }

            viewModel.chartScale.observe(viewLifecycleOwner) { scale ->
                val b = _binding ?: return@observe
                val isWeekly = scale == AppUsageChartScale.WEEKS
                b.weeklyBarGraph.setZoomLevel(
                    if (isWeekly) WeeklyBarGraphView.ZoomLevel.WEEKS
                    else WeeklyBarGraphView.ZoomLevel.DAYS
                )
                b.weeklyBarGraph.setContentDescription(
                    getString(
                        if (isWeekly) R.string.usage_weekly_chart_description
                        else R.string.usage_daily_chart_description
                    )
                )
                b.btnPrevWeek.setContentDescription(
                    getString(
                        if (isWeekly) R.string.desc_earlier_weeks
                        else R.string.desc_previous_week
                    )
                )
                b.btnNextWeek.setContentDescription(
                    getString(
                        if (isWeekly) R.string.desc_later_weeks
                        else R.string.desc_next_week
                    )
                )
            }

            viewModel.isChartTransitioning.observe(viewLifecycleOwner) { transitioning ->
                if (transitioning) beginChartTransition() else markChartDataReady()
            }

            viewModel.canGoNext.observe(viewLifecycleOwner) { canGo ->
                val b = _binding ?: return@observe
                b.btnNextWeek.alpha = if (canGo) 1f else 0.3f
                b.btnNextWeek.isEnabled = canGo
            }

            viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
                val b = _binding ?: return@observe

                b.loadingOverlay.animate().cancel()
                b.main.animate().cancel()

                if (isLoading) {
                    b.loadingOverlay.animate().alpha(1f).setDuration(250).withStartAction {
                        val bInner = _binding ?: return@withStartAction
                        bInner.loadingOverlay.visibility = View.VISIBLE
                    }
                    b.main.animate().alpha(0f).setDuration(250).withEndAction {
                        val bInner = _binding ?: return@withEndAction
                        bInner.main.visibility = View.INVISIBLE
                    }
                } else {
                    b.loadingOverlay.animate().alpha(0f).setDuration(350).withEndAction {
                        val bInner = _binding ?: return@withEndAction
                        bInner.loadingOverlay.visibility = View.GONE
                    }
                    b.main.animate().alpha(1f).setDuration(350).withStartAction {
                        val bInner = _binding ?: return@withStartAction
                        bInner.main.visibility = View.VISIBLE
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            if (::viewModel.isInitialized) {
                viewModel.reload()
            }
        }

        private fun beginChartTransition() {
            val content = _binding?.main ?: return
            chartTransitionAnimator?.cancel()
            chartTransitionActive = true
            chartTransitionHidden = false
            chartTransitionDataReady = false
            chartTransitionAnimator = BlurFadeAnimator.fadeOut(content) {
                chartTransitionHidden = true
                revealChartIfReady()
            }
        }

        private fun markChartDataReady() {
            if (!chartTransitionActive) return
            chartTransitionDataReady = true
            revealChartIfReady()
        }

        private fun revealChartIfReady() {
            if (!chartTransitionActive || !chartTransitionHidden || !chartTransitionDataReady) return
            val content = _binding?.main ?: return
            chartTransitionAnimator = BlurFadeAnimator.fadeIn(content) {
                chartTransitionActive = false
            }
        }

        private fun generateAndExportCsv(startMs: Long, endMs: Long, mode: Int) {
            Toast.makeText(
                requireContext(),
                getString(R.string.generating_analysis_csv),
                Toast.LENGTH_SHORT
            ).show()

            lifecycleScope.launch(Dispatchers.IO) {
                val sb = StringBuilder()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                val header =
                    "Date,App Name,Package Name,Category,Duration (ms),Duration (mins),Is System App,Install Date,Last Update,Sessions\n"

                suspend fun processStatsForRange(
                    start: Long,
                    end: Long,
                    dateLabel: String
                ): String {
                    val rangeSb = StringBuilder()
                    val statsMap = usageStatsHelper.getForegroundStatsByTimestamps(start, end)

                    statsMap.forEach { it ->
                        if (it.totalTime > 0) {
                            val metadata = viewModel.getAppMetadata(it.packageName)
                            val appName = metadata.label.toString().replace(",", " ")
                            val category = when (metadata.category) {
                                "GAME" -> "Game"
                                "SOCIAL NETWORKING" -> "Social"
                                "PRODUCTIVITY" -> "Productivity"
                                "VIDEO" -> "Video"
                                "AUDIO" -> "Audio"
                                else -> "Other"
                            }
                            val isSystem = if (metadata.isSystemApp) "Yes" else "No"
                            val installDate = metadata.installDate
                            val lastUpdate = metadata.lastUpdate

                            val minutes = it.totalTime / 1000 / 60

                            rangeSb.append("$dateLabel,$appName,${it.packageName},$category,${it.totalTime},$minutes,$isSystem,$installDate,$lastUpdate,${it.sessions}\n")
                        }
                    }
                    return rangeSb.toString()
                }

                if (mode == 0 || mode == 2) {
                    sb.append("--- DAILY BREAKDOWN ---\n")
                    sb.append(header)

                    val startInstant =
                        Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
                    val endInstant =
                        Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()).toLocalDate()

                    var current = startInstant
                    while (!current.isAfter(endInstant)) {
                        val dayStart =
                            current.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val dayEnd = current.atTime(23, 59, 59).atZone(ZoneId.systemDefault())
                            .toInstant().toEpochMilli()

                        sb.append(processStatsForRange(dayStart, dayEnd, current.toString()))
                        current = current.plusDays(1)
                    }
                    sb.append("\n")
                }

                if (mode == 1 || mode == 2) {
                    sb.append(
                        "--- TOTAL SUMMARY (${dateFormat.format(Date(startMs))} to ${
                            dateFormat.format(
                                Date(endMs)
                            )
                        }) ---\n"
                    )
                    sb.append(header)
                    sb.append(processStatsForRange(startMs, endMs, "TOTAL RANGE"))
                }

                csvDataToExport = sb.toString()

                withContext(Dispatchers.Main) {
                    val name = "UsageData_${dateFormat.format(Date(startMs))}.csv"
                    createCsvLauncher.launch(name)
                }
            }
        }

        // Softens the top and bottom edges of the background ascii art so tall
        // pieces (like ascii_god) melt into the surface instead of looking cut off.
        // The overlay fades to the surface color, which is the same color drawn
        // behind the art, so it stays clean even through the view's 0.5 alpha.
        private fun createAsciiFade(): Drawable {
            val surface = MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorSurface
            )
            val transparent = surface and 0x00FFFFFF
            val fadeHeight = (48 * resources.displayMetrics.density).toInt()

            val top = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(surface, transparent)
            )
            val bottom = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(surface, transparent)
            )

            return LayerDrawable(arrayOf(top, bottom)).apply {
                setLayerGravity(0, Gravity.TOP)
                setLayerHeight(0, fadeHeight)
                setLayerGravity(1, Gravity.BOTTOM)
                setLayerHeight(1, fadeHeight)
            }
        }

        private fun resizeIcon(icon: Drawable, width: Int, height: Int): Drawable {
            val bitmap = if (icon is BitmapDrawable) {
                icon.bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    icon.intrinsicWidth,
                    icon.intrinsicHeight,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                icon.setBounds(0, 0, canvas.width, canvas.height)
                icon.draw(canvas)
                bitmap
            }

            val density = Resources.getSystem().displayMetrics.density
            val targetWidth = (width * density).toInt()
            val targetHeight = (height * density).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                targetWidth,
                targetHeight,
                true
            )

            return BitmapDrawable(Resources.getSystem(), scaledBitmap)
        }

        inner class AppUsageViewHolder(private val binding: AppUsageItemBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(
                stats: AppUsageStat,
                websiteStats: List<WebsiteStatsEntity>,
                reelUsageStats: List<ReelUsageStatsEntity>
            ) {
                val metadata = viewModel.getAppMetadata(stats.packageName)
                binding.appIcon.setImageDrawable(
                    metadata.icon ?: ContextCompat.getDrawable(
                        binding.root.context,
                        R.drawable.baseline_warning_24
                    )
                )
                binding.root.setOnClickListener {
                    val destination = if (stats.packageName == neth.iecal.curbox.data.sync.SYNCED_WEB_PACKAGE) {
                        // There's no single app behind synced browsing, so per app
                        // stats don't apply here. Go straight to the website list.
                        WebsiteUsageFragment.newInstance(stats.packageName)
                    } else {
                        AppUsageBreakdown(stats)
                    }
                    activity?.supportFragmentManager?.beginTransaction()
                        ?.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                        ?.replace(R.id.fragment_holder, destination)
                        ?.addToBackStack(null)
                        ?.commit()
                }
                binding.root.setOnLongClickListener {

                    MaterialAlertDialogBuilder(binding.root.context)
                        .setTitle(R.string.usage_ignore_title)
                        .setMessage(R.string.usage_ignore_message)
                        .setCancelable(true)
                        .setPositiveButton(R.string.okay) { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val dataStore = DataStoreManager(binding.root.context)
                                val ignoredAppsSP =
                                    dataStore.settings.first().usageTrackerIgnoredApps.toMutableList()
                                if (!ignoredAppsSP.contains(stats.packageName)) {
                                    ignoredAppsSP.add(stats.packageName)
                                    dataStore.updateUsageTrackerIgnoredApps(ignoredAppsSP)
                                    withContext(Dispatchers.Main) {
                                        if (_binding != null) {
                                            viewModel.ignoredPackages.addAll(ignoredAppsSP)
                                            viewModel.reload()
                                        }
                                    }
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }

                binding.appName.text = metadata.label
                binding.appUsage.text = TimeTools.formatTimeForWidget(stats.totalTime)
                binding.appCategory.text = metadata.category

                binding.threadContainer.removeAllViews()
                val browserWebsites = websiteStats.filter { it.packageName == stats.packageName }
                    .groupBy { it.domain }
                    .map { (domain, stats) ->
                        domain to stats.sumOf { it.totalTime }
                    }
                    .sortedByDescending { it.second }
                val reelUsage = reelUsageStats.find {
                    it.packageName == stats.packageName && (it.totalTime > 0L || it.reelCount > 0)
                }

                Log.d("website stats", browserWebsites.toString())
                if (browserWebsites.isNotEmpty()) {
                    binding.threadContainer.visibility = View.VISIBLE
                    val top5 = browserWebsites.take(5)
                    for (i in top5.indices) {
                        val (domain, time) = top5[i]
                        val prefix =
                            if (i == top5.size - 1 && browserWebsites.size <= 5 && reelUsage == null) "└" else "├"
                        val tv = TextView(binding.root.context).apply {
                            text = "$prefix  $domain • ${TimeTools.formatDuration(time)}"
                            textSize = 12f
                            setTextColor(
                                MaterialColors.getColor(
                                    binding.root,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant
                                )
                            )
                            setPadding(0, 4, 0, 4)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }
                        binding.threadContainer.addView(tv)
                    }

                    if (browserWebsites.size > 5) {
                        val seeMoreTv = TextView(binding.root.context).apply {
                            text = if (reelUsage == null) {
                                "└  See more websites..."
                            } else {
                                "├  See more websites..."
                            }
                            textSize = 12f
                            setTextColor(
                                MaterialColors.getColor(
                                    binding.root,
                                    com.google.android.material.R.attr.colorPrimary
                                )
                            )
                            setPadding(0, 4, 0, 12)
                            setOnClickListener {
                                activity?.supportFragmentManager?.beginTransaction()
                                    ?.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                                    ?.replace(
                                        R.id.fragment_holder,
                                        WebsiteUsageFragment.newInstance(stats.packageName)
                                    )
                                    ?.addToBackStack(null)
                                    ?.commit()
                            }
                        }
                        binding.threadContainer.addView(seeMoreTv)
                    }
                } else if (reelUsage == null) {
                    binding.threadContainer.visibility = View.GONE
                }

                if (reelUsage != null) {
                    binding.threadContainer.visibility = View.VISIBLE
                    val averageAttentionSpan = reelUsage.totalTime / maxOf(reelUsage.reelCount, 1)
                    val shortFormVideoRows = listOf(
                        binding.root.context.getString(
                            R.string.usage_short_form_videos_scrolled,
                            reelUsage.reelCount
                        ),
                        binding.root.context.getString(
                            R.string.usage_short_form_video_duration,
                            TimeTools.formatDuration(reelUsage.totalTime)
                        ),
                        binding.root.context.getString(
                            R.string.usage_short_form_video_average_attention,
                            TimeTools.formatTimeInHHMM(averageAttentionSpan)
                        )
                    )
                    shortFormVideoRows.forEachIndexed { index, row ->
                        val prefix = if (index == shortFormVideoRows.lastIndex) "└" else "├"
                        val reelText = TextView(binding.root.context).apply {
                            text = "$prefix  $row"
                            textSize = 12f
                            setTextColor(
                                MaterialColors.getColor(
                                    binding.root,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant
                                )
                            )
                            setPadding(0, 4, 0, 4)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }
                        binding.threadContainer.addView(reelText)
                    }
                }
            }
        }

        inner class AppUsageAdapter(
            private var appUsageStats: List<AppUsageStat>,
            private var websiteStats: List<WebsiteStatsEntity> = emptyList(),
            private var reelUsageStats: List<ReelUsageStatsEntity> = emptyList()
        ) : RecyclerView.Adapter<AppUsageViewHolder>() {

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppUsageViewHolder {
                val binding =
                    AppUsageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return AppUsageViewHolder(binding)
            }

            override fun onBindViewHolder(holder: AppUsageViewHolder, position: Int) {
                holder.bind(appUsageStats[position], websiteStats, reelUsageStats)
            }

            @SuppressLint("NotifyDataSetChanged")
            fun updateData(
                newAppUsageStats: List<AppUsageStat>,
                newWebsiteStats: List<WebsiteStatsEntity> = emptyList(),
                newReelUsageStats: List<ReelUsageStatsEntity> = emptyList()
            ) {
                appUsageStats = newAppUsageStats
                websiteStats = newWebsiteStats
                reelUsageStats = newReelUsageStats
                notifyDataSetChanged()
            }

            override fun getItemCount(): Int = appUsageStats.size
        }


        private val backgroundArts by lazy {
            R.string::class.java.fields
                .filter { it.name.startsWith("ascii") && it.type == Int::class.javaPrimitiveType }
                .sortedBy { it.name }
                .map { it.name to it.getInt(null) }
        }
        private var backgroundArtDialog: androidx.appcompat.app.AlertDialog? = null

        private fun applyBackgroundArt(artName: String) {
            val art = backgroundArts.find { it.first == artName }
                ?: backgroundArts.randomOrNull()
            _binding?.asciiArt?.setText(art?.second ?: R.string.angel_fallen)
        }

        private fun showBackgroundArtDialog() {
            viewLifecycleOwner.lifecycleScope.launch {
                val dataStore = DataStoreManager(requireContext())
                val selectedArt = dataStore.settings.first().usageBackgroundArt
                val choices = listOf("" to getString(R.string.usage_background_art_random)) +
                    backgroundArts.map { (name, _) ->
                        name to name.removePrefix("ascii_").replace('_', ' ')
                            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                    }
                var selectedIndex = choices.indexOfFirst { it.first == selectedArt }.coerceAtLeast(0)
                backgroundArtDialog?.dismiss()
                backgroundArtDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.usage_select_background_art)
                    .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), selectedIndex) { _, which ->
                        selectedIndex = which
                        applyBackgroundArt(choices[which].first)
                    }
                    .setPositiveButton(R.string.okay) { _, _ ->
                        val artName = choices[selectedIndex].first
                        viewLifecycleOwner.lifecycleScope.launch {
                            dataStore.updateUsageBackgroundArt(artName)
                        }
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> applyBackgroundArt(selectedArt) }
                    .setOnCancelListener { applyBackgroundArt(selectedArt) }
                    .show()
            }
        }

        override fun onDestroyView() {
            backgroundArtDialog?.dismiss()
            backgroundArtDialog = null
            chartTransitionAnimator?.cancel()
            _binding?.main?.let(BlurFadeAnimator::reset)
            chartTransitionActive = false
            super.onDestroyView()
            _binding = null
        }
    }
