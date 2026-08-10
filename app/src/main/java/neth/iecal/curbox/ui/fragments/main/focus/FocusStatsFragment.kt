package neth.iecal.curbox.ui.fragments.main.focus

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentFocusStatsBinding
import neth.iecal.curbox.data.db.FocusStatsEntity
import java.util.Calendar

class FocusStatsFragment : Fragment() {

    private var _binding: FragmentFocusStatsBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel with FocusFragment to get stats
    private val viewModel: FocusViewModel by activityViewModels()
    
    private var selectedGroupId: String? = null // null means "All Groups"

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
        
        setupGroupSpinner()
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allSessions.collectLatest { sessions: List<FocusStatsEntity> ->
                    updateUI(sessions)
                }
            }
        }
    }
    
    private fun setupGroupSpinner() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.groups.collectLatest { updateSpinner() }
                }
                launch {
                    viewModel.autoDndGroups.collectLatest { updateSpinner() }
                }
            }
        }
        
        binding.spinGroups.setOnItemClickListener { _, _, position, _ ->
            val allGroups = mutableListOf<Pair<String?, String>>()
            allGroups.add(null to getString(R.string.all_groups))
            viewModel.groups.value.forEach { allGroups.add(it.groupId to it.groupName) }
            viewModel.autoDndGroups.value.forEach { allGroups.add(it.groupId to it.groupName) }
            
            if (position < allGroups.size) {
                selectedGroupId = allGroups[position].first
                viewLifecycleOwner.lifecycleScope.launch {
                    val currentSessions = viewModel.allSessions.first()
                    updateUI(currentSessions)
                }
            }
        }
    }
    
    private fun updateSpinner() {
        val allGroupsLabel = getString(R.string.all_groups)
        val names = mutableListOf(allGroupsLabel)
        viewModel.groups.value.forEach { names.add(it.groupName) }
        viewModel.autoDndGroups.value.forEach { names.add(it.groupName) }
        
        if (!isAdded) return
        
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        binding.spinGroups.setAdapter(adapter)
        
        val selectedName = if (selectedGroupId == null) allGroupsLabel else {
            viewModel.groups.value.find { it.groupId == selectedGroupId }?.groupName
                ?: viewModel.autoDndGroups.value.find { it.groupId == selectedGroupId }?.groupName
                ?: allGroupsLabel
        }
        binding.spinGroups.setText(selectedName, false)
    }

    private fun updateUI(sessions: List<FocusStatsEntity>) {
        val completed = sessions.filter { 
            (it.status == 1 || it.status == 2) && 
            (selectedGroupId == null || it.groupId == selectedGroupId) 
        }
        val totalSessions = completed.size
        
        var totalDurationMs = 0L
        var successfulSessions = 0
        
        val tagCounts = mutableMapOf<String, Int>()
        
        for (session in completed) {
            val duration = session.actualEndTimeInMillis - session.startTimeInMillis
            if (duration > 0) {
                totalDurationMs += duration
            }
            if (session.status == 1) {
                successfulSessions++
            }
            
            val count = tagCounts.getOrDefault(session.groupId, 0)
            tagCounts[session.groupId] = count + 1
        }
        
        binding.tvTotalSessions.text = totalSessions.toString()
        binding.tvTotalDuration.text = formatDuration(totalDurationMs)
        
        val avgDuration = if (totalSessions > 0) totalDurationMs / totalSessions.toLong() else 0L
        binding.tvAvgDuration.text = formatDuration(avgDuration)
        
        val completionRate = if (totalSessions > 0) ((successfulSessions * 100).toFloat() / totalSessions.toFloat()).toInt() else 0
        binding.tvCompletionRate.text = getString(R.string.percent_value, completionRate)
        
        binding.tvStreak.text = computeStreak(completed).toString()
        
        updateTopTags(tagCounts)
        updateWeeklyGraph(completed)
    }
    
    private fun updateWeeklyGraph(sessions: List<FocusStatsEntity>) {
        val days = mutableListOf<neth.iecal.curbox.ui.views.WeeklyBarGraphView.DayData>()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val format = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())

        for (i in 6 downTo 0) {
            val dayStart = cal.timeInMillis - (i * 86400000L)
            val dayEnd = dayStart + 86400000L
            
            val daySessions = sessions.filter { it.startTimeInMillis in dayStart until dayEnd }
            var totalMs = 0L
            daySessions.forEach { 
                val dur = it.actualEndTimeInMillis - it.startTimeInMillis
                if (dur > 0) totalMs += dur
            }
            
            val hours = totalMs / 3600000f
            
            val c = Calendar.getInstance().apply { timeInMillis = dayStart }
            days.add(neth.iecal.curbox.ui.views.WeeklyBarGraphView.DayData(
                label = format.format(c.time),
                value = hours,
                dateMillis = dayStart
            ))
        }
        
        binding.weeklyBarGraph.setData(days, selected = -1)
    }

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
    
    private fun computeStreak(sessions: List<FocusStatsEntity>): Int {
        if (sessions.isEmpty()) return 0
        
        val sortedDesc = sessions.sortedByDescending { it.startTimeInMillis }
        
        var currentStreak = 0
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var expectedDayStart = cal.timeInMillis

        val uniqueDays = sortedDesc.map { 
            val c = Calendar.getInstance()
            c.timeInMillis = it.startTimeInMillis
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.timeInMillis
        }.distinct()

        if (uniqueDays.isEmpty()) return 0
        
        if (uniqueDays[0] == expectedDayStart) {
            currentStreak++
            expectedDayStart -= 86400000L
            for (i in 1 until uniqueDays.size) {
                if (uniqueDays[i] == expectedDayStart) {
                    currentStreak++
                    expectedDayStart -= 86400000L
                } else break
            }
        } else if (uniqueDays[0] == expectedDayStart - 86400000L) {
            currentStreak++
            expectedDayStart -= 86400000L * 2
            for (i in 1 until uniqueDays.size) {
                if (uniqueDays[i] == expectedDayStart) {
                    currentStreak++
                    expectedDayStart -= 86400000L
                } else break
            }
        }

        return currentStreak
    }
    
    private fun updateTopTags(tagCounts: Map<String, Int>) {
        if (selectedGroupId != null) {
             binding.cardTopTags.visibility = View.GONE
             return
        }
        
        if (tagCounts.isEmpty()) {
            binding.cardTopTags.visibility = View.GONE
            return
        }
        
        binding.cardTopTags.visibility = View.VISIBLE
        binding.llTagStats.removeAllViews()
        
        val sortedTags = tagCounts.entries.sortedByDescending { it.value }.take(3)
        val manualGroups = viewModel.groups.value
        val autoGroups = viewModel.autoDndGroups.value
        
        for (entry in sortedTags) {
            val groupId = entry.key
            val count = entry.value
            
            var name = getString(R.string.focus_unknown_group)
            val mGroup = manualGroups.find { it.groupId == groupId }
            if (mGroup != null) name = mGroup.groupName
            else {
                val aGroup = autoGroups.find { it.groupId == groupId }
                if (aGroup != null) name = aGroup.groupName
            }
            
            val tv = TextView(requireContext()).apply {
                text = resources.getQuantityString(
                    R.plurals.focus_top_tag_sessions,
                    count,
                    name,
                    count
                )
                textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
            }
            binding.llTagStats.addView(tv)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
