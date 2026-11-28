package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import neth.iecal.curbox.utils.ViewUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.TemporaryDisableDialog
import neth.iecal.curbox.utils.scheduleConflictsWith

class AppBlockerGroupsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "app_blocker_groups"

        private val DIFF = object : DiffUtil.ItemCallback<AppGroup>() {
            override fun areItemsTheSame(oldItem: AppGroup, newItem: AppGroup) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: AppGroup, newItem: AppGroup) =
                oldItem == newItem
        }
    }

    private lateinit var rvGroups: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var fabAddGroup: FloatingActionButton

    private val viewModel: AppBlockerSettingViewModel by activityViewModels()
    private val adapter = AppGroupAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_app_blocker_groups, container, false)
        
        rvGroups = view.findViewById(R.id.rv_app_groups)
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        fabAddGroup = view.findViewById(R.id.fab_add_group)

        view.findViewById<Button>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(it, getString(R.string.help_app_blocker), "https://curbox.app/docs/reducers/app-pause/")
        }


        fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateAppGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        rvGroups.layoutManager = LinearLayoutManager(requireContext())
        rvGroups.adapter = adapter

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collectLatest { groups ->
                    tvEmptyState.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
                    rvGroups.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
                    adapter.submitList(groups)
                }
            }
        }
    }

    inner class AppGroupAdapter :
        ListAdapter<AppGroup, AppGroupAdapter.ViewHolder>(DIFF) {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_group_name)
            val tvDetails: TextView = view.findViewById(R.id.tv_group_details)
            val tvRemaining: TextView = view.findViewById(R.id.tv_group_remaining)
            val switchActive: SwitchMaterial = view.findViewById(R.id.switch_group_active)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = getItem(position)
            holder.tvName.text = group.name

            holder.tvDetails.text = group.config?.let { config ->
                getString(
                    R.string.group_details_apps,
                    group.selectedPackages.size,
                    requireContext().appGroupScheduleSummary(config.schedule)
                )
            } ?: getString(R.string.group_details_apps_only, group.selectedPackages.size)

            holder.tvRemaining.visibility = View.GONE
            holder.tvRemaining.tag = group.id
            val temporaryRemaining = group.temporarilyDisabledUntilMs - System.currentTimeMillis()
            if (group.temporarilyDisabledUntilMs == TemporaryDisableDialog.UNTIL_MANUALLY_ENABLED) {
                holder.tvRemaining.text =
                    getString(R.string.temporary_disable_until_manually_enabled)
                holder.tvRemaining.visibility = View.VISIBLE
            } else if (temporaryRemaining > 0L) {
                holder.tvRemaining.text = getString(
                    R.string.temporary_disable_until,
                    neth.iecal.curbox.utils.SettingsChangeDelayUtils.formatRemaining(
                        requireContext(),
                        temporaryRemaining
                    )
                )
                holder.tvRemaining.visibility = View.VISIBLE
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    val remaining = viewModel.getRemainingUsageMillis(group)
                    if (holder.tvRemaining.tag != group.id) return@launch
                    if (remaining == null) {
                        holder.tvRemaining.visibility = View.GONE
                    } else {
                        holder.tvRemaining.text = if (remaining <= 0L) {
                            getString(R.string.group_no_usage_left)
                        } else {
                            getString(
                                R.string.group_usage_left,
                                TimeTools.formatTimeForWidget(remaining)
                            )
                        }
                        holder.tvRemaining.visibility = View.VISIBLE
                    }
                }
            }

            holder.switchActive.setOnCheckedChangeListener(null)
            holder.switchActive.isChecked = group.isActive

            holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
                val pos = holder.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                if (isChecked && !group.isActive) {
                    val conflicts = group.copy(isActive = true)
                        .scheduleConflictsWith(viewModel.groups.value)
                    if (conflicts.isNotEmpty()) {
                        holder.switchActive.setOnCheckedChangeListener(null)
                        holder.switchActive.isChecked = false
                        holder.switchActive.post {
                            if (pos in 0 until itemCount) notifyItemChanged(pos)
                        }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.schedule_conflict_title)
                            .setMessage(requireContext().appGroupConflictMessage(conflicts))
                            .setNegativeButton(R.string.schedule_conflict_cancel, null)
                            .setNeutralButton(R.string.schedule_conflict_edit_group) { _, _ ->
                                openGroup(group.id)
                            }
                            .setPositiveButton(R.string.schedule_conflict_keep_both) { _, _ ->
                                val currentIndex = viewModel.groups.value
                                    .indexOfFirst { it.id == group.id }
                                if (currentIndex != -1) {
                                    viewModel.updateGroupActiveState(currentIndex, true)
                                }
                            }
                            .show()
                    } else {
                        viewModel.updateGroupActiveState(pos, true)
                    }
                } else if (!isChecked &&
                    group.isActive &&
                    viewModel.temporaryDisableAvailable.value
                ) {
                    holder.switchActive.setOnCheckedChangeListener(null)
                    holder.switchActive.isChecked = true
                    holder.switchActive.post {
                        if (pos in 0 until itemCount) notifyItemChanged(pos)
                    }
                    TemporaryDisableDialog.show(
                        this@AppBlockerGroupsFragment,
                        getString(R.string.temporary_disable_title, group.name)
                    ) { minutes ->
                        viewModel.temporarilyDisableGroup(group.id, minutes)
                    }
                } else {
                    viewModel.updateGroupActiveState(pos, isChecked)
                }
            }

            holder.itemView.setOnClickListener {
                openGroup(group.id)
            }
        }
    }

    private fun openGroup(groupId: String) {
        val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
            putExtra("fragment", CreateAppGroupFragment.FRAGMENT_ID)
            putExtra("group_id", groupId)
        }
        startActivity(intent)
    }

}
