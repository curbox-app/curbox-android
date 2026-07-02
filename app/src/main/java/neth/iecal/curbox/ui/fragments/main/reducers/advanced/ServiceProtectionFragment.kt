package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.hardcoded.OemAutostartIntents
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.ServiceWatchdogJob
import neth.iecal.curbox.services.UsageTrackingService
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.ServiceProtectionManager
import neth.iecal.curbox.utils.ViewUtils

/**
 * Lets the user turn on the layered "keep my services alive" protection and shows, in plain words,
 * which layers are working right now. It is honest that nothing here is truly un killable.
 */
class ServiceProtectionFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "service_protection"
    }

    private lateinit var dataStore: DataStoreManager

    private lateinit var switchProtection: MaterialSwitch
    private lateinit var groupStatus: View
    private lateinit var iconServices: ImageView
    private lateinit var textServices: TextView
    private lateinit var iconBattery: ImageView
    private lateinit var textBattery: TextView
    private lateinit var btnFixBattery: MaterialButton
    private lateinit var cardAutostart: View
    private lateinit var iconShizuku: ImageView
    private lateinit var textShizuku: TextView
    private lateinit var btnSetupShizuku: MaterialButton
    private lateinit var deviceOwnerHeader: View
    private lateinit var btnDeviceOwner: MaterialButton

    private var isEnabled = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 2000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_service_protection, container, false)
        dataStore = DataStoreManager(requireContext())

        switchProtection = view.findViewById(R.id.switch_protection)
        groupStatus = view.findViewById(R.id.group_status)
        iconServices = view.findViewById(R.id.icon_services)
        textServices = view.findViewById(R.id.text_services_status)
        iconBattery = view.findViewById(R.id.icon_battery)
        textBattery = view.findViewById(R.id.text_battery_status)
        btnFixBattery = view.findViewById(R.id.btn_fix_battery)
        cardAutostart = view.findViewById(R.id.card_autostart)
        iconShizuku = view.findViewById(R.id.icon_shizuku)
        textShizuku = view.findViewById(R.id.text_shizuku_status)
        btnSetupShizuku = view.findViewById(R.id.btn_setup_shizuku)
        deviceOwnerHeader = view.findViewById(R.id.text_device_owner_header)
        btnDeviceOwner = view.findViewById(R.id.btn_device_owner)

        view.findViewById<MaterialButton>(R.id.btn_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        view.findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(
                it,
                getString(R.string.service_protection_help_popup),
                "https://curbox.app/docs/"
            )
        }

        switchProtection.setOnClickListener { onMasterToggled(switchProtection.isChecked) }
        btnFixBattery.setOnClickListener { openBatterySettings() }
        btnSetupShizuku.setOnClickListener { setupShizuku() }
        view.findViewById<MaterialButton>(R.id.btn_open_autostart).setOnClickListener { openAutostart() }
        view.findViewById<MaterialButton>(R.id.btn_repair_now).setOnClickListener { repairNow() }
        btnDeviceOwner.setOnClickListener { confirmDeviceOwner() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settings.collect { settings ->
                    isEnabled = settings.serviceProtectionConfig.isEnabled
                    switchProtection.isChecked = isEnabled
                    render()
                }
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tickRunnable)
    }

    private fun onMasterToggled(turnOn: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateServiceProtectionConfig { it.copy(isEnabled = turnOn) }
        }
        if (turnOn) {
            ServiceWatchdogJob.schedule(requireContext())
            ServiceProtectionManager.reinforceBackgroundExecution(requireContext())
            if (!ServiceProtectionManager.isIgnoringBatteryOptimizations(requireContext())) {
                openBatterySettings()
            }
        }
        render()
    }

    private fun render() {
        if (!isAdded) return
        val context = requireContext()

        groupStatus.isVisible = isEnabled

        // Both services running
        val bothOn = ServiceProtectionManager.areBothServicesEnabled(context)
        setRow(
            iconServices, textServices, bothOn,
            getString(R.string.service_protection_services_on),
            getString(R.string.service_protection_services_off)
        )

        // Battery exemption
        val batteryOk = ServiceProtectionManager.isIgnoringBatteryOptimizations(context)
        setRow(
            iconBattery, textBattery, batteryOk,
            getString(R.string.service_protection_battery_ok),
            getString(R.string.service_protection_battery_bad)
        )
        btnFixBattery.isVisible = !batteryOk

        // OEM autostart (only shown on makers that need it)
        cardAutostart.isVisible = OemAutostartIntents.isLikelyAggressiveOem()

        // Shizuku self heal
        val shizukuOk = ServiceProtectionManager.canSelfHeal()
        setRow(
            iconShizuku, textShizuku, shizukuOk,
            getString(R.string.service_protection_shizuku_on),
            getString(R.string.service_protection_shizuku_off)
        )
        btnSetupShizuku.isVisible = !shizukuOk

        // Device owner advanced option only makes sense with Shizuku
        deviceOwnerHeader.isVisible = shizukuOk
        btnDeviceOwner.isVisible = shizukuOk
    }

    private fun setRow(icon: ImageView, text: TextView, ok: Boolean, okText: String, badText: String) {
        text.text = if (ok) okText else badText
        icon.setImageResource(if (ok) R.drawable.baseline_done_24 else R.drawable.baseline_warning_24)
        val tint = if (ok) {
            com.google.android.material.color.MaterialColors.getColor(icon, com.google.android.material.R.attr.colorPrimary)
        } else {
            com.google.android.material.color.MaterialColors.getColor(icon, com.google.android.material.R.attr.colorError)
        }
        icon.setColorFilter(tint)
    }

    private fun openBatterySettings() {
        try {
            startActivity(ServiceProtectionManager.ignoreBatteryOptimizationsIntent(requireContext()))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.service_protection_no_screen, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupShizuku() {
        if (PermissionUtils.isShizukuAvailable()) {
            try {
                rikka.shizuku.Shizuku.requestPermission(1001)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            Toast.makeText(requireContext(), R.string.service_protection_shizuku_not_installed, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://shizuku.rikka.app/")
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun openAutostart() {
        val intent = OemAutostartIntents.resolveAutostartIntent(requireContext())
        if (intent != null) {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.service_protection_no_screen, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), R.string.service_protection_autostart_manual, Toast.LENGTH_LONG).show()
        }
    }

    private fun repairNow() {
        val context = requireContext()
        if (ServiceProtectionManager.canSelfHeal()) {
            ServiceProtectionManager.healNow(context)
            Toast.makeText(context, R.string.service_protection_repair_started, Toast.LENGTH_SHORT).show()
        } else {
            val downService = when {
                !ServiceProtectionManager.isAppBlockerEnabled(context) -> AppBlockerService::class.java
                !ServiceProtectionManager.isUsageTrackerEnabled(context) -> UsageTrackingService::class.java
                else -> null
            }
            if (downService != null) {
                PermissionUtils.openAccessibilityServiceScreen(context, downService)
            } else {
                Toast.makeText(context, R.string.service_protection_services_on, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeviceOwner() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.service_protection_device_owner)
            .setMessage(R.string.service_protection_device_owner_warning)
            .setNegativeButton(R.string.service_protection_cancel, null)
            .setPositiveButton(R.string.service_protection_continue) { _, _ ->
                ServiceProtectionManager.setDeviceOwner(requireContext())
                Toast.makeText(requireContext(), R.string.service_protection_repair_started, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
