package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.urge_surf

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.CravingSessionEntity
import neth.iecal.curbox.databinding.FragmentUrgeSurfBinding
import java.util.Locale

class UrgeSurfFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "URGE_SURF"
    }

    private var _binding: FragmentUrgeSurfBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UrgeSurfViewModel by viewModels()
    private var isUpdatingFromViewModel = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUrgeSurfBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        binding.switchButton.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingFromViewModel) return@setOnCheckedChangeListener
            viewModel.updateButtonEnabled(isChecked)
        }
        binding.switchAutoOffer.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingFromViewModel) return@setOnCheckedChangeListener
            viewModel.updateAutoOfferEnabled(isChecked)
        }
        binding.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.urge_surf_clear_history_confirm)
                .setPositiveButton(R.string.urge_surf_clear) { _, _ -> viewModel.clearHistory() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.configState.collect { config ->
                        isUpdatingFromViewModel = true
                        binding.switchButton.isChecked = config.isButtonEnabled
                        binding.switchAutoOffer.isChecked = config.isAutoOfferEnabled
                        isUpdatingFromViewModel = false
                    }
                }
                launch {
                    viewModel.sessions.collect(::showStats)
                }
            }
        }
    }

    private fun showStats(sessions: List<CravingSessionEntity>) {
        val hasSessions = sessions.isNotEmpty()
        binding.tvStatsEmpty.visibility = if (hasSessions) View.GONE else View.VISIBLE
        binding.statsContent.visibility = if (hasSessions) View.VISIBLE else View.GONE
        if (!hasSessions) return

        val count = sessions.size
        val wentDown = sessions.count { it.ratingAfter < it.ratingBefore }
        binding.tvStatsSessions.text =
            resources.getQuantityString(R.plurals.urge_surf_stats_sessions, count, count)
        binding.tvStatsAverage.text = getString(
            R.string.urge_surf_stats_average,
            formatAverage(sessions.map { it.ratingBefore }.average()),
            formatAverage(sessions.map { it.ratingAfter }.average())
        )
        binding.tvStatsWentDown.text =
            resources.getQuantityString(R.plurals.urge_surf_stats_went_down, wentDown, wentDown, count)
    }

    private fun formatAverage(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
