package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.ViewUtils
import neth.iecal.curbox.data.models.AutoDndGroup
import neth.iecal.curbox.databinding.FragmentAutodndBinding
import neth.iecal.curbox.databinding.ItemAutodndGroupBinding

class AutoDndFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "autodnd_fragment"
    }

    private var _binding: FragmentAutodndBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AutoDndViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAutodndBinding.inflate(inflater, container, false)
        
        binding.fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                putExtra("fragment", CreateAutoDndGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.btnHelp.setOnClickListener {
            ViewUtils.showHelpPopup(it, getString(R.string.autodnd_help_popup), "https://curbox.app/docs/reducers/auto-dnd/")
        }

        binding.rvAutodndGroups.layoutManager = LinearLayoutManager(requireContext())
        
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collectLatest { groups ->
                    if (groups.isEmpty()) {
                        binding.tvEmptyState.visibility = View.VISIBLE
                        binding.rvAutodndGroups.visibility = View.GONE
                    } else {
                        binding.tvEmptyState.visibility = View.GONE
                        binding.rvAutodndGroups.visibility = View.VISIBLE
                        binding.rvAutodndGroups.adapter = AutoDndGroupAdapter(groups)
                    }
                }
            }
        }
    }

    inner class AutoDndGroupAdapter(private val groupList: List<AutoDndGroup>) :
        RecyclerView.Adapter<AutoDndGroupAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemAutodndGroupBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemAutodndGroupBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groupList[position]
            holder.itemBinding.tvGroupName.text = group.groupName
            
            holder.itemBinding.tvGroupDetails.text = getString(R.string.autodnd_scheduled)
            
            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateAutoDndGroupFragment.FRAGMENT_ID)
                    putExtra("group_id", group.groupId)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = groupList.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
