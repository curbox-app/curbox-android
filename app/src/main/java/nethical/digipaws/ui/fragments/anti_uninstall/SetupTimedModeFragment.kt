package nethical.digipaws.ui.fragments.anti_uninstall

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.databinding.FragmentSetupTimedModeBinding
import nethical.digipaws.services.GeneralFeaturesService

class SetupTimedModeFragment : Fragment() {

    private var _binding: FragmentSetupTimedModeBinding? = null
    private val binding get() = _binding!!  // Safe getter for binding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupTimedModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.calendarView.minDate = binding.calendarView.date
        var selectedDate: String? = null
        binding.calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            selectedDate = "${month + 1}/$dayOfMonth/$year"
        }
        binding.turnOnTimed.setOnClickListener {

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.alert))
                .setMessage(getString(R.string.are_you_sure_you_want_to_turn_on_anti_uninstall_there_is_no_turning_back))
                .setPositiveButton(getString(R.string.i_understand)) { _, dialog ->
                    selectedDate?.let { it1 -> turnOnTimedMode(it1) }
                }
                .setNegativeButton(getString(R.string.cancel)) { _, dialog ->
                    requireActivity().finish()
                }
                .show()
        }
        binding.blockChanges.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.alert))
                    .setMessage(getString(R.string.if_you_enable_this_you_won_t_be_able_to_change_configurations_such_as_adding_blocked_apps_keywords_and_more))
                    .setPositiveButton(getString(R.string.i_understand), null)
                    .show()
            }
        }

    }

    private fun turnOnTimedMode(selectedDate: String) {

        val editor =
            activity?.getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)?.edit()
        editor?.apply() {
            putBoolean("is_anti_uninstall_on", true)
            putString("date", selectedDate)
            putBoolean("is_configuring_blocked", binding.blockChanges.isChecked)
            putInt("mode", Constants.ANTI_UNINSTALL_TIMED_MODE)
            commit()
        }

        val intent = Intent(GeneralFeaturesService.INTENT_ACTION_REFRESH_ANTI_UNINSTALL)
        activity?.sendBroadcast(intent)

        activity?.finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}