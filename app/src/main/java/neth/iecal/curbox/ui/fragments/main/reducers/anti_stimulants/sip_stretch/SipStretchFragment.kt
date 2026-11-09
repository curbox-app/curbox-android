package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.sip_stretch

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.SipStretchConfig
import neth.iecal.curbox.databinding.FragmentSipStretchBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity

class SipStretchFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "SIP_STRETCH"
    }

    private var _binding: FragmentSipStretchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SipStretchViewModel by viewModels()
    private var selectedApps = arrayListOf<String>()
    private var isUpdatingFromViewModel = false

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                selectedApps = apps
                updateAppsButtonText()
                viewModel.updateSelectedApps(apps.toList())
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSipStretchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.switchIsActive.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingFromViewModel) return@setOnCheckedChangeListener
            viewModel.updateIsActive(isChecked)
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", selectedApps)
            selectAppsLauncher.launch(intent)
        }

        binding.sliderUsageLimit.addOnChangeListener { _, _, _ -> updateValueLabels() }
        binding.sliderBreakLength.addOnChangeListener { _, _, _ -> updateValueLabels() }

        // Saving only when the finger lifts keeps the change delay from parking every step.
        binding.sliderUsageLimit.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.updateUsageLimitMinutes(slider.value.toInt())
            }
        })
        binding.sliderBreakLength.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.updateBreakMinutes(slider.value.toInt())
            }
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.configState.collect { config ->
                    isUpdatingFromViewModel = true

                    binding.switchIsActive.isChecked = config.isActive
                    selectedApps = ArrayList(config.selectedApps)
                    updateAppsButtonText()

                    binding.sliderUsageLimit.value = snapToStep(
                        config.usageLimitMinutes,
                        SipStretchConfig.MIN_USAGE_LIMIT_MINUTES,
                        SipStretchConfig.MAX_USAGE_LIMIT_MINUTES,
                        binding.sliderUsageLimit.stepSize.toInt()
                    )
                    binding.sliderBreakLength.value = config.breakMinutes
                        .coerceIn(SipStretchConfig.MIN_BREAK_MINUTES, SipStretchConfig.MAX_BREAK_MINUTES)
                        .toFloat()
                    updateValueLabels()

                    isUpdatingFromViewModel = false
                }
            }
        }
    }

    private fun snapToStep(value: Int, min: Int, max: Int, step: Int): Float {
        val clamped = value.coerceIn(min, max)
        return (min + ((clamped - min) / step) * step).toFloat()
    }

    private fun updateValueLabels() {
        binding.tvUsageLimitValue.text =
            getString(R.string.sip_stretch_minutes_value, binding.sliderUsageLimit.value.toInt())
        binding.tvBreakLengthValue.text =
            getString(R.string.sip_stretch_minutes_value, binding.sliderBreakLength.value.toInt())
    }

    private fun updateAppsButtonText() {
        binding.btnSelectApps.text = getString(R.string.select_apps_count, selectedApps.size)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
