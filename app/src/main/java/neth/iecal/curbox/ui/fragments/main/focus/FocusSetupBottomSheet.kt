package neth.iecal.curbox.ui.fragments.main.focus

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.PomodoroState
import neth.iecal.curbox.databinding.DialogFocusSessionConfigBinding
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.ui.activity.SelectAppsActivity

class FocusSetupBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogFocusSessionConfigBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FocusViewModel by activityViewModels()

    private lateinit var autoCompleteAdapter: ArrayAdapter<ManualFocusGroup>

    // The view model may reselect a group whenever settings change, so it can't tell create from edit.
    private var editingGroupId: String? = null

    private val selectAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
            viewModel.newGroupSelectedApps = HashSet(selectedApps)
            binding.selectedAppCount.text = getString(R.string.selected_count, selectedApps.size)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFocusSessionConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupGroupSelectionDropdown()
        setupPomodoroOptions()
        observeViewModel()

        if (viewModel.groups.value.isEmpty()) {
            binding.createGroup.visibility = View.VISIBLE
            binding.selectGrouo.visibility = View.GONE
        } else {
            viewModel.selectedGroup?.let { group ->
                binding.groupDropdown.setText(group.toString(), false)
                binding.btnEditGroup.visibility = View.VISIBLE
                binding.btnDeleteGroup.visibility = View.VISIBLE
            }
        }

        binding.btnCreateGroup.setOnClickListener {
            // clear if creating new
            viewModel.selectedGroup = null
            editingGroupId = null
            binding.groupName.setText("")
            viewModel.newGroupSelectedApps = HashSet()
            viewModel.newGroupSelectedKeywords = hashSetOf()
            binding.selectedAppCount.text = getString(R.string.selected_count, 0)
            binding.selectedWebsiteCount.text = getString(R.string.selected_count, viewModel.newGroupSelectedKeywords.size)
            binding.exitable.isChecked = true
            binding.autoTurnOnDnd.isChecked = false
            
            binding.createGroup.visibility = View.VISIBLE
            binding.selectGrouo.visibility = View.GONE
        }

        binding.btnConfirmStart.setOnClickListener {
            val breakMinutes = binding.pomodoroBreakMinutes.text
                ?.toString()
                ?.toIntOrNull()
                ?: PomodoroState.DEFAULT_BREAK_MINUTES
            val focusIntervals = binding.pomodoroFocusIntervals.text
                ?.toString()
                ?.toIntOrNull()
                ?: PomodoroState.DEFAULT_FOCUS_INTERVALS
            viewModel.configurePomodoro(
                enabled = binding.switchPomodoro.isChecked,
                breakMinutes = breakMinutes,
                focusIntervals = focusIntervals
            )
            viewModel.startFocusing()
            dismiss()
        }

        binding.groupDropdown.setOnItemClickListener { parent, view, position, id ->
            val clickedItem = parent.getItemAtPosition(position)
            val selectedGroup = clickedItem as ManualFocusGroup
            viewModel.selectedGroup = selectedGroup
            binding.btnEditGroup.visibility = View.VISIBLE
            binding.btnDeleteGroup.visibility = View.VISIBLE
        }

        binding.btnEditGroup.setOnClickListener {
            val group = viewModel.selectedGroup ?: return@setOnClickListener
            editingGroupId = group.groupId
            // Pre-fill the create group form with this group's details
            binding.createGroup.visibility = View.VISIBLE
            binding.selectGrouo.visibility = View.GONE
            
            binding.groupName.setText(group.groupName)
            viewModel.newGroupSelectedApps = HashSet(group.packages)
            viewModel.newGroupSelectedKeywords = HashSet(group.keywords)
            binding.selectedAppCount.text = getString(R.string.selected_count, group.packages.size)
            binding.selectedWebsiteCount.text = getString(R.string.selected_count, group.keywords.size)
            if (group.blockMode == FocusBlockMode.BLOCK_SELECTED) {
                binding.selectedBlockAction.check(R.id.btn_selected)
            } else {
                binding.selectedBlockAction.check(R.id.btn_block_all_excpt_selected)
            }
            binding.exitable.isChecked = group.exitable
            binding.autoTurnOnDnd.isChecked = group.autoTurnOnDnd
        }

        binding.btnDeleteGroup.setOnClickListener {
            val group = viewModel.selectedGroup ?: return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.focus_delete_group_title)
                .setMessage(R.string.focus_delete_group_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.removeGroup(group)
                    viewModel.selectedGroup = null
                    binding.groupDropdown.setText("", false)
                    binding.btnEditGroup.visibility = View.GONE
                    binding.btnDeleteGroup.visibility = View.GONE
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(viewModel.newGroupSelectedApps))
            selectAppsLauncher.launch(intent)
        }

        binding.btnAddWebsites.setOnClickListener {
            showAddWebsitesDialog()
        }

        binding.autoTurnOnDnd.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !neth.iecal.curbox.utils.DndHelper.ensureDndAccess(requireContext())) {
                binding.autoTurnOnDnd.isChecked = false
            }
        }

        binding.saveGroup.setOnClickListener {
            val isEditing = editingGroupId != null
            val blockMode = if(binding.selectedBlockAction.checkedButtonId == R.id.btn_selected) FocusBlockMode.BLOCK_SELECTED else FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED
            
            if (viewModel.newGroupSelectedKeywords.isNotEmpty()) {
                val supportedBrowsers = URL_BAR_ID_LIST.keys
                val hasBrowserSelected = viewModel.newGroupSelectedApps.any { it in supportedBrowsers }
                
                if (!hasBrowserSelected) {
                    if (blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.focus_no_browser_title)
                            .setMessage(R.string.focus_no_browser_allow_message)
                            .setPositiveButton(R.string.focus_add_browser) { _, _ ->
                                binding.btnSelectApps.performClick()
                            }
                            .setNegativeButton(R.string.focus_save_anyway) { _, _ ->
                                saveFocusGroup(isEditing, blockMode)
                            }
                            .show()
                    } else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.focus_block_notice_title)
                            .setMessage(R.string.focus_block_notice_message)
                            .setPositiveButton(R.string.focus_add_browser) { _, _ ->
                                binding.btnSelectApps.performClick()
                            }
                            .setNegativeButton(R.string.focus_save_anyway) { _, _ ->
                                saveFocusGroup(isEditing, blockMode)
                            }
                            .show()
                    }
                    return@setOnClickListener
                }
            }

            saveFocusGroup(isEditing, blockMode)
        }
    }

    private fun saveFocusGroup(isEditing: Boolean, blockMode: FocusBlockMode) {
        val newGroup = ManualFocusGroup(
            groupId = if (isEditing) editingGroupId!! else java.util.UUID.randomUUID().toString(),
            groupName = binding.groupName.text.toString(),
            packages = viewModel.newGroupSelectedApps,
            keywords = viewModel.newGroupSelectedKeywords,
            blockMode = blockMode,
            exitable = binding.exitable.isChecked,
            autoTurnOnDnd = binding.autoTurnOnDnd.isChecked
        )

        if (isEditing) {
            viewModel.updateGroup(newGroup)
        } else {
            viewModel.addGroup(newGroup)
        }

        editingGroupId = null
        binding.createGroup.visibility = View.GONE
        binding.selectGrouo.visibility = View.VISIBLE

        // Select it after it was created/edited
        viewModel.selectedGroup = newGroup
        binding.groupDropdown.setText(newGroup.toString(), false)
        binding.btnEditGroup.visibility = View.VISIBLE
        binding.btnDeleteGroup.visibility = View.VISIBLE
    }

    private fun showAddWebsitesDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_focus_add_websites, null)
        val etWebsite = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_website)
        val btnAdd = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add)
        val rvWebsites = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_websites)
        
        val tempKeywords = ArrayList(viewModel.newGroupSelectedKeywords)
        val adapter = KeywordAdapter(tempKeywords)
        rvWebsites.adapter = adapter

        btnAdd.setOnClickListener {
            val text = etWebsite.text.toString().trim()
            if (text.isNotEmpty()) {
                val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                parts.forEach {
                    if (!tempKeywords.contains(it)) {
                        tempKeywords.add(it)
                    }
                }
                etWebsite.setText("")
                adapter.notifyDataSetChanged()
            }
        }

        etWebsite.setOnEditorActionListener { _, _, _ ->
            btnAdd.performClick()
            true
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.focus_add_websites_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.newGroupSelectedKeywords = HashSet(tempKeywords)
                binding.selectedWebsiteCount.text = getString(R.string.selected_count, tempKeywords.size)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    inner class KeywordAdapter(private val items: MutableList<String>) : RecyclerView.Adapter<KeywordAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvKeyword: android.widget.TextView = view.findViewById(R.id.tv_keyword)
            val btnRemove: android.widget.ImageButton = view.findViewById(R.id.btn_remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_keyword, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val keyword = items[position]
            holder.tvKeyword.text = keyword
            holder.btnRemove.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    items.removeAt(currentPos)
                    notifyItemRemoved(currentPos)
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private fun setupGroupSelectionDropdown() {
        autoCompleteAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )

        binding.groupDropdown.setAdapter(autoCompleteAdapter)
    }

    private fun setupPomodoroOptions() {
        binding.switchPomodoro.isChecked = viewModel.isPomodoroEnabled
        binding.pomodoroBreakMinutes.setText(viewModel.pomodoroBreakMins.toString())
        binding.pomodoroFocusIntervals.setText(viewModel.pomodoroFocusIntervals.toString())

        fun updateVisibility() {
            binding.pomodoroOptions.visibility = if (binding.switchPomodoro.isChecked) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        binding.switchPomodoro.setOnCheckedChangeListener { _, _ ->
            updateVisibility()
        }
        updateVisibility()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collect { suggestions ->
                    autoCompleteAdapter.clear()
                    autoCompleteAdapter.addAll(suggestions)
                    autoCompleteAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "focus_session_screen_config"
    }
}
