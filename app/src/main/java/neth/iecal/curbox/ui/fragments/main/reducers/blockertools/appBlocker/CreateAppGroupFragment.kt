package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import kotlinx.coroutines.flow.collectLatest
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.databinding.FragmentCreateAppGroupBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.utils.scheduleConflictsWith
import java.util.UUID

class CreateAppGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_app_group"
    }

    private var _binding: FragmentCreateAppGroupBinding? = null
    private val binding get() = _binding!!

    private var selectedApps: ArrayList<String> = arrayListOf()
    private var isPrefilled = false
    private var autoAddNewApps = false
    private val viewModel: AppBlockerSettingViewModel by activityViewModels()

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                selectedApps = apps
                autoAddNewApps = result.data?.getBooleanExtra(SelectAppsActivity.EXTRA_AUTO_ADD_NEW_APPS, false) == true
                updateSelectAppsButton()
            }
        }
    }

    private var isDeleting = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAppGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        var isEditing = false
        val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val prefillPackage = requireActivity().intent.getStringExtra("prefill_package")
        binding.btnDeleteGroup.visibility = View.GONE

        if (groupId == null && !isPrefilled && prefillPackage != null) {
            isPrefilled = true
            selectedApps = arrayListOf(prefillPackage)
            updateSelectAppsButton()
        }

        if (groupId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.groups.collectLatest { groups ->
                    val group = groups.find { it.id == groupId }
                    if (group != null && !isEditing) {
                        isEditing = true
                        binding.textView2.text = getString(R.string.app_group_edit_title)
                        binding.etGroupName.setText(group.name)
                        selectedApps = ArrayList(group.selectedPackages)
                        autoAddNewApps = group.autoAddNewApps
                        updateSelectAppsButton()

                        binding.btnDeleteGroup.visibility = View.VISIBLE
                        binding.btnDeleteGroup.setOnClickListener {
                            isDeleting = true
                            viewLifecycleOwner.lifecycleScope.launch {
                                viewModel.deleteGroup(group.id)
                                Toast.makeText(requireContext(), getString(R.string.group_deleted), Toast.LENGTH_SHORT).show()
                                requireActivity().finish()
                            }
                        }

                        group.config?.let { config ->
                            viewModel.currentTimeConfig = config.schedule
                            viewModel.currentUsageConfig = config.usage
                        }
                        viewModel.warningScrnConfig = group.warningScreenConfig
                    }
                }
            }
        }

        binding.btnSelectApps.setOnClickListener { openAppSelector() }

        binding.btnConfigureSchedule.setOnClickListener { openScheduleEditor() }
        binding.btnConfigureUsage.setOnClickListener { openUsageEditor() }
        binding.configureWarningScreen.setOnClickListener {
            val configFragment = neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.newInstance(
                viewModel.warningScrnConfig, 
                "result_warning_config",
                isNew = groupId == null,
                supportsOnEachOpen = true
            )
            parentFragmentManager.beginTransaction()
                .hide(this)
                .add(R.id.fragment_holder, configFragment)
                .addToBackStack(null)
                .commit()
        }

        parentFragmentManager.setFragmentResultListener("result_warning_config", viewLifecycleOwner) { _, bundle ->
            val configStr = bundle.getString("result_config")
            if (configStr != null) {
                viewModel.warningScrnConfig =
                    Gson().fromJson(configStr, AppBlockerWarningScreenConfig::class.java)
            }
        }
        binding.fabSaveGroup.setOnClickListener {
            saveGroup()
        }
    }

    private fun saveGroup(skipConflictCheck: Boolean = false) {
        if (_binding == null || isDeleting) return
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = getString(R.string.group_name_required)
            return
        }

        if (selectedApps.isEmpty() && !autoAddNewApps) {
            Toast.makeText(requireContext(), getString(R.string.please_select_at_least_one_app), Toast.LENGTH_SHORT).show()
            return
        }

        val savedGroupId =
            requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val isEditingRecord = savedGroupId != null
        val targetExistingGroup = viewModel.groups.value.find { it.id == savedGroupId }

        val newGroupId = if (isEditingRecord && targetExistingGroup != null) {
            targetExistingGroup.id
        } else {
            UUID.randomUUID().toString()
        }

        val newGroup = AppGroup(
            id = newGroupId,
            name = name,
            selectedPackages = selectedApps.toList(),
            config = AppGroupConfig(
                schedule = viewModel.currentTimeConfig,
                usage = viewModel.currentUsageConfig
            ),
            isActive =
                if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.isActive
                else true,
            temporarilyDisabledUntilMs = targetExistingGroup?.temporarilyDisabledUntilMs ?: 0L,
            warningScreenConfig = viewModel.warningScrnConfig,
            autoAddNewApps = autoAddNewApps,
            autoAddCheckedUntilMs =
                if (autoAddNewApps && targetExistingGroup?.autoAddNewApps == true) targetExistingGroup.autoAddCheckedUntilMs
                else System.currentTimeMillis()
        )

        val conflicts = newGroup.scheduleConflictsWith(viewModel.groups.value)
        if (!skipConflictCheck && conflicts.isNotEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.schedule_conflict_title)
                .setMessage(requireContext().appGroupConflictMessage(conflicts))
                .setNegativeButton(R.string.schedule_conflict_edit_apps) { _, _ ->
                    openAppSelector()
                }
                .setNeutralButton(R.string.schedule_conflict_edit_schedule) { _, _ ->
                    openScheduleEditor()
                }
                .setPositiveButton(R.string.schedule_conflict_keep_both) { _, _ ->
                    saveGroup(skipConflictCheck = true)
                }
                .show()
            return
        }

        binding.fabSaveGroup.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isEditingRecord && targetExistingGroup != null) {
                    viewModel.updateGroupById(newGroup)
                } else {
                    viewModel.addGroupAndWait(newGroup)
                    if (arguments == null) {
                        arguments = Bundle()
                    }
                    arguments?.putString("group_id", newGroupId)
                }

                Toast.makeText(requireContext(), getString(R.string.group_saved_successfully), Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(requireContext().applicationContext).logNonFatalError(e)
                Toast.makeText(requireContext(), R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                _binding?.fabSaveGroup?.isEnabled = true
            }
        }
    }

    private fun openAppSelector() {
        val intent = Intent(requireContext(), SelectAppsActivity::class.java).apply {
            putStringArrayListExtra("PRE_SELECTED_APPS", selectedApps)
            putExtra(SelectAppsActivity.EXTRA_ALLOW_CURBOX, true)
            putExtra(SelectAppsActivity.EXTRA_AUTO_ADD_NEW_APPS, autoAddNewApps)
        }
        selectAppsLauncher.launch(intent)
    }

    private fun updateSelectAppsButton() {
        binding.btnSelectApps.text = getString(
            if (autoAddNewApps) R.string.select_apps_count_plus_new else R.string.select_apps_count,
            selectedApps.size
        )
    }

    private fun openScheduleEditor() {
        TimeBasedSettingsFragment().show(
            parentFragmentManager,
            TimeBasedSettingsFragment.FRAGMENT_ID
        )
    }

    private fun openUsageEditor() {
        UsageBasedSettingsFragment().show(
            parentFragmentManager,
            UsageBasedSettingsFragment.FRAGMENT_ID
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
