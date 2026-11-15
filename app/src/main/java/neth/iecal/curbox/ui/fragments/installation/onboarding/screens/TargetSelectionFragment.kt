package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import neth.iecal.curbox.databinding.FragmentTargetSelectionBinding
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingViewModel
import neth.iecal.curbox.utils.ExtensionPromo
import neth.iecal.curbox.utils.setAsciiArt

class TargetSelectionFragment : Fragment() {

    private var _binding: FragmentTargetSelectionBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: OnboardingViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTargetSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.asciiArt.setAsciiArt("ascii_aim")

        viewModel.setTargetApp("Instagram")
        viewModel.setDailyLimit(30L)

        binding.targetChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val target = when (checkedIds.first()) {
                R.id.chip_instagram -> "Instagram"
                R.id.chip_tiktok -> "TikTok"
                R.id.chip_youtube -> "YouTube"
                R.id.chip_reddit -> "Reddit"
                R.id.chip_other -> "Other"
                else -> "Instagram"
            }
            viewModel.setTargetApp(target)
        }

        binding.limitChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val limit = when (checkedIds.first()) {
                R.id.chip_15m -> 15L
                R.id.chip_30m -> 30L
                R.id.chip_1h -> 60L
                R.id.chip_2h -> 120L
                else -> 30L
            }
            viewModel.setDailyLimit(limit)
        }

        binding.btnAction.setOnClickListener {
            (parentFragment as? OnboardingFragment)?.goToNextPage()
        }

        binding.btnSkip.setOnClickListener {
            viewModel.skipTargetApp()
            (parentFragment as? OnboardingFragment)?.goToNextPage()
        }

        // New users learn about the extension here, so the update announcement
        // does not repeat it right after onboarding.
        ExtensionPromo.markSeen(requireContext())
        binding.cardExtension.setOnClickListener {
            (activity as? AppCompatActivity)?.let { ExtensionPromo.show(it) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
