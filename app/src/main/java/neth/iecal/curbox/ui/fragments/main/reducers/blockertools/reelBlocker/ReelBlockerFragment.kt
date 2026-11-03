package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ReelBlockerFragmentBinding
import neth.iecal.curbox.hardcoded.ReelAppConfig
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.utils.SettingsChangeDelayUtils
import neth.iecal.curbox.utils.TemporaryDisableDialog
import neth.iecal.curbox.utils.ViewUtils

class ReelBlockerFragment : Fragment() {

    private var _binding: ReelBlockerFragmentBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ReelBlockerViewModel by activityViewModels()
    private var isUpdatingUi = false

    private val selectExcludedAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            result.data?.getStringArrayListExtra("SELECTED_APPS")?.let { packages ->
                viewModel.updateExcludedPackages(packages)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ReelBlockerFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.switchEnableBlocker.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                val config = viewModel.reelBlockerConfig.value
                if (!isChecked && config.isActive && viewModel.temporaryDisableAvailable.value) {
                    isUpdatingUi = true
                    binding.switchEnableBlocker.isChecked = true
                    isUpdatingUi = false
                    TemporaryDisableDialog.show(
                        this,
                        getString(R.string.temporary_disable_title, getString(R.string.reel_blocker_title))
                    ) { minutes ->
                        viewModel.temporarilyDisable(minutes)
                    }
                } else {
                    viewModel.setIsActive(isChecked)
                }
            }
        }

        binding.btnWarningConfig.setOnClickListener {
            val configFragment = neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.newInstance(viewModel.reelBlockerConfig.value.warningScreenConfig, "result_warning_config_reel")
            parentFragmentManager.beginTransaction()
                .hide(this)
                .add(R.id.fragment_holder, configFragment)
                .addToBackStack(null)
                .commit()
        }

        parentFragmentManager.setFragmentResultListener("result_warning_config_reel", viewLifecycleOwner) { _, bundle ->
            val configStr = bundle.getString("result_config")
            if (configStr != null) {
                viewModel.updateWarningConfig(com.google.gson.Gson().fromJson(configStr, neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig::class.java))
            }
        }

        binding.btnConfigureSchedule.setOnClickListener {
            ReelBlockerTimeSettingsFragment().show(childFragmentManager, ReelBlockerTimeSettingsFragment.FRAGMENT_ID)
        }

        binding.btnConfigureUsage.setOnClickListener {
            ReelBlockerUsageSettingsFragment().show(childFragmentManager, ReelBlockerUsageSettingsFragment.FRAGMENT_ID)
        }

        binding.btnConfigureCount.setOnClickListener {
            ReelBlockerCountSettingsFragment().show(childFragmentManager, ReelBlockerCountSettingsFragment.FRAGMENT_ID)
        }

        binding.btnAllowedApps.setOnClickListener {
            val config = viewModel.reelBlockerConfig.value
            val intent = Intent(requireContext(), SelectAppsActivity::class.java).apply {
                putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(config.excludedPackages))
                putStringArrayListExtra("APP_LIST", ArrayList(ReelAppConfig.reelData.keys.sorted()))
                putExtra("ALLOW_CUSTOM_APPS", false)
            }
            selectExcludedAppsLauncher.launch(intent)
        }

        binding.checkboxAllowInstagramDmReels.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setAllowInstagramReelsFromDmInbox(isChecked)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.reelBlockerConfig.collectLatest { config ->
                isUpdatingUi = true
                // Avoid infinite loops by checking if state actually changed before triggering listeners
                if (binding.switchEnableBlocker.isChecked != config.isActive) {
                    binding.switchEnableBlocker.isChecked = config.isActive
                }
                val remaining = config.temporarilyDisabledUntilMs - System.currentTimeMillis()
                val untilManuallyEnabled =
                    config.temporarilyDisabledUntilMs ==
                        TemporaryDisableDialog.UNTIL_MANUALLY_ENABLED
                binding.textTemporaryDisableStatus.isVisible =
                    untilManuallyEnabled || remaining > 0L
                if (untilManuallyEnabled) {
                    binding.textTemporaryDisableStatus.text =
                        getString(R.string.temporary_disable_until_manually_enabled)
                } else if (remaining > 0L) {
                    binding.textTemporaryDisableStatus.text = getString(
                        R.string.temporary_disable_until,
                        SettingsChangeDelayUtils.formatRemaining(requireContext(), remaining)
                    )
                }

                binding.btnAllowedApps.text = getString(
                    R.string.reel_blocker_allowed_apps_count,
                    config.excludedPackages.size
                )
                binding.checkboxAllowInstagramDmReels.isChecked =
                    config.allowInstagramReelsFromDmInbox
                isUpdatingUi = false
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "reel_blocker"
    }
}
