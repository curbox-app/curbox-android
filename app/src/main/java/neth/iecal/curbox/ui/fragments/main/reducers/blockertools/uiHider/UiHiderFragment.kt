package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.ViewUtils
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.UiHiderScript
import neth.iecal.curbox.databinding.FragmentUiHiderBinding
import neth.iecal.curbox.hardcoded.allScripts
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.NodePickerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.PermissionUtils

class UiHiderFragment : Fragment() {

    private var _binding: FragmentUiHiderBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UiHiderViewModel by activityViewModels()
    private var isUpdatingUi = false

    private val adapter = UiHiderScriptAdapter(
        onClick = { openEditor(it) },
        onToggle = { id, checked -> viewModel.setScriptEnabled(id, checked) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUiHiderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvScripts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvScripts.adapter = adapter

        binding.switchEnableUiHider.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setIsActive(isChecked)
        }
        binding.btnAddScript.setOnClickListener { openEditor(null) }
        binding.btnStartNodePicker.setOnClickListener { startNodePicker() }

        binding.btnHelp.setOnClickListener {
            ViewUtils.showHelpPopup(it, getString(R.string.help_ui_hider), "https://curbox.app/docs/reducers/hide-ui-elements/")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.config.collectLatest { config ->
                isUpdatingUi = true
                if (binding.switchEnableUiHider.isChecked != config.isActive) {
                    binding.switchEnableUiHider.isChecked = config.isActive
                }
                adapter.submitList(config.allScripts())
                isUpdatingUi = false
            }
        }
    }

    private fun startNodePicker() {
        val context = requireContext()
        if (!PermissionUtils.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)) {
            Toast.makeText(context, R.string.node_picker_need_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        if (!PermissionUtils.hasOverlayPermission(context)) {
            Toast.makeText(context, R.string.node_picker_need_overlay, Toast.LENGTH_LONG).show()
            return
        }
        NodePickerService.start(context)
        Toast.makeText(context, R.string.node_picker_started_hint, Toast.LENGTH_LONG).show()
    }

    private fun openEditor(script: UiHiderScript?) {
        val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
            putExtra("fragment", UiHiderEditorFragment.FRAGMENT_ID)
            if (script != null) {
                putExtra(UiHiderEditorFragment.EXTRA_SCRIPT_ID, script.id)
                putExtra(UiHiderEditorFragment.EXTRA_PACKAGE_NAME, script.packageName)
                putExtra(UiHiderEditorFragment.EXTRA_LABEL, script.label)
                putExtra(UiHiderEditorFragment.EXTRA_SOURCE, script.source)
                putExtra(UiHiderEditorFragment.EXTRA_IS_ENABLED, script.isEnabled)
            }
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "ui_hider"
    }
}
