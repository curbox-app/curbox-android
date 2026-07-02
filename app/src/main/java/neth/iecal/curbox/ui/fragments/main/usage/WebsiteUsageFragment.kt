package neth.iecal.curbox.ui.fragments.main.usage

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.databinding.FragmentWebsiteUsageBinding
import neth.iecal.curbox.databinding.WebsiteUsageDomainItemBinding
import neth.iecal.curbox.utils.TimeTools

class WebsiteUsageFragment : Fragment() {

    companion object {
        private const val ARG_PACKAGE_NAME = "package_name"
        fun newInstance(packageName: String) = WebsiteUsageFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE_NAME, packageName) }
        }
    }

    private val targetPackageName by lazy { arguments?.getString(ARG_PACKAGE_NAME) ?: "" }
    private var _binding: FragmentWebsiteUsageBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: WebsiteUsageViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWebsiteUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WebsiteUsageViewModel(requireActivity().application, targetPackageName) as T
            }
        }
        viewModel = ViewModelProvider(this, factory)[WebsiteUsageViewModel::class.java]

        val adapter = WebsiteDomainAdapter()
        binding.websiteUsageRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.websiteUsageRecyclerView.adapter = adapter

        observeViewModel(adapter)

        binding.btnPrevWeek.setOnClickListener { viewModel.goToPreviousWeek() }
        binding.btnNextWeek.setOnClickListener { viewModel.goToNextWeek() }
        binding.weeklyBarGraph.setOnDaySelectedListener { dayData ->
            val index = viewModel.weeklyData.value?.indexOf(dayData) ?: return@setOnDaySelectedListener
            viewModel.selectDay(index)
        }

        viewModel.initialize()
    }

    private fun observeViewModel(adapter: WebsiteDomainAdapter) {
        viewModel.weeklyData.observe(viewLifecycleOwner) { data ->
            val selectedIdx = viewModel.selectedDayIndex.value ?: 6
            binding.weeklyBarGraph.setData(data, selectedIdx)
        }
        viewModel.selectedDayIndex.observe(viewLifecycleOwner) { index ->
            binding.weeklyBarGraph.setSelectedIndex(index)
        }
        viewModel.selectedDayWebsiteStats.observe(viewLifecycleOwner) { stats ->
            adapter.updateData(stats)
        }
        viewModel.totalTime.observe(viewLifecycleOwner) { totalMs ->
            binding.totalUsage.text = TimeTools.formatDuration(totalMs)
        }
        viewModel.weekRangeLabel.observe(viewLifecycleOwner) { label ->
            binding.tvWeekRange.text = label
        }
        viewModel.canGoNext.observe(viewLifecycleOwner) { canGo ->
            binding.btnNextWeek.alpha = if (canGo) 1f else 0.3f
            binding.btnNextWeek.isEnabled = canGo
        }
        viewModel.dateSublabel.observe(viewLifecycleOwner) { label ->
            binding.dateSublabel.text = label
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    inner class WebsiteDomainAdapter : RecyclerView.Adapter<WebsiteDomainViewHolder>() {
        private var groupedStats: List<Pair<String, List<WebsiteStatsEntity>>> = emptyList()
        private val expandedDomains = mutableSetOf<String>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WebsiteDomainViewHolder {
            val binding = WebsiteUsageDomainItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return WebsiteDomainViewHolder(binding)
        }

        override fun onBindViewHolder(holder: WebsiteDomainViewHolder, position: Int) {
            val (domain, stats) = groupedStats[position]
            holder.bind(domain, stats, expandedDomains.contains(domain)) {
                if (expandedDomains.contains(domain)) expandedDomains.remove(domain)
                else expandedDomains.add(domain)
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = groupedStats.size

        fun updateData(newStats: List<WebsiteStatsEntity>) {
            groupedStats = newStats.groupBy { it.domain }
                .map { it.key to it.value.sortedBy { s -> s.lastVisited } }
                .sortedByDescending { it.second.sumOf { s -> s.totalTime } }
            notifyDataSetChanged()
        }
    }

    inner class WebsiteDomainViewHolder(private val binding: WebsiteUsageDomainItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(domain: String, stats: List<WebsiteStatsEntity>, isExpanded: Boolean, onToggle: () -> Unit) {
            binding.domainName.text = domain
            binding.totalTime.text = TimeTools.formatDuration(stats.sumOf { it.totalTime })
            
            binding.pathContainer.removeAllViews()
            val displayStats = if (isExpanded) stats else stats.take(1)
            
            displayStats.forEachIndexed { index, stat ->
                val prefix = if (index == displayStats.size - 1) {
                    if (isExpanded || stats.size == 1) "└" else "├"
                } else "├"
                
                val tv = TextView(binding.root.context).apply {
                    val path = stat.urlIdentifier.removePrefix(domain)
                    text = "$prefix  ${if (path.isEmpty()) "/" else path} • ${TimeTools.formatDuration(stat.totalTime)}"
                    textSize = 13f
                    setPadding(0, 8, 0, 8)
                    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                binding.pathContainer.addView(tv)
            }

            if (stats.size > 1) {
                binding.expandButton.visibility = View.VISIBLE
                binding.expandButton.text = if (isExpanded) "Show Less" else "Show More (${stats.size - 1})"
                binding.expandButton.setOnClickListener { onToggle() }
            } else {
                binding.expandButton.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
