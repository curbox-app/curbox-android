package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentScreenTimeEstimateBinding
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingViewModel
import neth.iecal.curbox.utils.setAsciiArt

class ScreenTimeEstimateFragment : Fragment() {

    private var _binding: FragmentScreenTimeEstimateBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: OnboardingViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScreenTimeEstimateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.asciiArt.setAsciiArt("ascii_pill")
        
        updateRealityText(viewModel.estimatedHours.value ?: 4)
        
        binding.hoursSlider.addOnChangeListener { _, value, _ ->
            val hours = value.toInt()
            viewModel.setEstimatedHours(hours)
            updateRealityText(hours)
        }

        binding.btnAction.setOnClickListener {
            (parentFragment as? OnboardingFragment)?.goToNextPage()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateRealityText(hours: Int) {
        binding.tvHoursDisplay.text = getString(R.string.screen_time_hours, hours)
        
        // Calculate weeks over a year (hours * 365) / 24 / 7
        // Let's use days instead to make it simpler and sound scarier: (hours * 365) / 24
        val totalHoursPerYear = hours * 365
        val daysPerYear = totalHoursPerYear / 24
        val remainingHours = totalHoursPerYear % 24
        
        val weeks = daysPerYear / 7
        
        binding.tvRealitySubtext.text = getString(R.string.screen_time_reality, weeks)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
