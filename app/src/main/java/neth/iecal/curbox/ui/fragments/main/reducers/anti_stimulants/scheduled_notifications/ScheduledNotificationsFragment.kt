package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.scheduled_notifications

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.ScheduledNotificationsConfig
import neth.iecal.curbox.databinding.FragmentScheduledNotificationsBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import java.util.Calendar

class ScheduledNotificationsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "SCHEDULED_NOTIFICATIONS"
    }

    private var _binding: FragmentScheduledNotificationsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScheduledNotificationsViewModel by viewModels()
    private var allowedApps = arrayListOf<String>()
    private var isUpdatingFromViewModel = false

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                allowedApps = apps
                updateAppsButtonText()
                viewModel.updateAllowedApps(apps.toList())
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScheduledNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateAccessCard()
    }

    private fun setupUI() {
        binding.switchIsActive.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingFromViewModel) return@setOnCheckedChangeListener
            if (isChecked && !hasNotificationAccess()) {
                binding.switchIsActive.isChecked = false
                askForNotificationAccess()
                return@setOnCheckedChangeListener
            }
            viewModel.updateIsActive(isChecked)
        }

        binding.btnAllowAccess.setOnClickListener { openNotificationAccessSettings() }
        binding.btnAddTime.setOnClickListener { pickDeliveryTime() }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", allowedApps)
            selectAppsLauncher.launch(intent)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.configState.collect { config ->
                    isUpdatingFromViewModel = true

                    binding.switchIsActive.isChecked = config.isActive
                    allowedApps = ArrayList(config.allowedApps)
                    updateAppsButtonText()
                    showDeliveryTimes(config.deliveryMinutes)
                    updateAccessCard()

                    isUpdatingFromViewModel = false
                }
            }
        }
    }

    private fun showDeliveryTimes(deliveryMinutes: List<Int>) {
        binding.chipsDeliveryTimes.removeAllViews()
        deliveryMinutes.sorted().forEach { minutes ->
            val chip = Chip(requireContext()).apply {
                text = formatTime(minutes)
                isCheckable = false
                isCloseIconVisible = deliveryMinutes.size > 1
                setOnCloseIconClickListener { viewModel.removeDeliveryTime(minutes) }
            }
            binding.chipsDeliveryTimes.addView(chip)
        }
        binding.btnAddTime.isVisible =
            deliveryMinutes.size < ScheduledNotificationsConfig.MAX_DELIVERY_TIMES
    }

    private fun formatTime(minutes: Int): String {
        val time = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }.time
        return DateFormat.getTimeFormat(requireContext()).format(time)
    }

    private fun pickDeliveryTime() {
        val clockFormat =
            if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(clockFormat)
            .setHour(12)
            .setMinute(0)
            .setTitleText(R.string.scheduled_notifications_pick_time)
            .build()
        picker.addOnPositiveButtonClickListener {
            viewModel.addDeliveryTime(picker.hour * 60 + picker.minute)
        }
        picker.show(childFragmentManager, "delivery_time_picker")
    }

    private fun hasNotificationAccess(): Boolean =
        requireContext().packageName in
            NotificationManagerCompat.getEnabledListenerPackages(requireContext())

    private fun askForNotificationAccess() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scheduled_notifications_access_title)
            .setMessage(R.string.scheduled_notifications_access_message)
            .setPositiveButton(R.string.proceed) { _, _ -> openNotificationAccessSettings() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** Shown when the feature is on but access was taken away, so nothing is being held. */
    private fun updateAccessCard() {
        val binding = _binding ?: return
        binding.cardAccess.isVisible = binding.switchIsActive.isChecked && !hasNotificationAccess()
    }

    private fun updateAppsButtonText() {
        binding.btnSelectApps.text = getString(R.string.select_apps_count, allowedApps.size)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
