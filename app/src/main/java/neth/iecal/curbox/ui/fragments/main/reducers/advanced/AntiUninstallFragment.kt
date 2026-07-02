package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.data.models.AntiUninstallMode
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ViewUtils
import java.util.concurrent.TimeUnit

/**
 * Lets the user switch uninstall protection on with one of three unlock methods and, once it is on,
 * walk back through whichever method they chose. Protection itself is a device admin that the
 * AntiUninstallBlocker guards.
 */
class AntiUninstallFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "anti_uninstall"
    }

    private lateinit var dataStore: DataStoreManager

    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var groupSetup: LinearLayout
    private lateinit var groupProtected: LinearLayout
    private lateinit var groupUnlocking: LinearLayout
    private lateinit var radioPassword: MaterialRadioButton
    private lateinit var radioTimed: MaterialRadioButton
    private lateinit var radioCooldown: MaterialRadioButton
    private lateinit var daysInputLayout: TextInputLayout
    private lateinit var daysInput: TextInputEditText
    private lateinit var cancelUnlockButton: MaterialButton

    private var selectedMode = AntiUninstallMode.PASSWORD
    private var latestConfig = AntiUninstallConfig()

    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 1000)
        }
    }

    // Holds the choice made before the device admin prompt, applied once admin is granted.
    private var pendingMode = AntiUninstallMode.PASSWORD
    private var pendingHash = ""
    private var pendingDays = 7

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (AntiUninstallManager.isAdminActive(requireContext())) {
            enableProtection()
        } else {
            Toast.makeText(requireContext(), R.string.anti_uninstall_admin_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_anti_uninstall, container, false)
        dataStore = DataStoreManager(requireContext())

        statusTitle = view.findViewById(R.id.text_status_title)
        statusBody = view.findViewById(R.id.text_status_body)
        groupSetup = view.findViewById(R.id.group_setup)
        groupProtected = view.findViewById(R.id.group_protected)
        groupUnlocking = view.findViewById(R.id.group_unlocking)
        radioPassword = view.findViewById(R.id.radio_mode_password)
        radioTimed = view.findViewById(R.id.radio_mode_timed)
        radioCooldown = view.findViewById(R.id.radio_mode_cooldown)
        daysInputLayout = view.findViewById(R.id.input_layout_days)
        daysInput = view.findViewById(R.id.input_days)
        cancelUnlockButton = view.findViewById(R.id.btn_cancel_unlock)

        view.findViewById<MaterialCardView>(R.id.card_mode_password).setOnClickListener { selectMode(AntiUninstallMode.PASSWORD) }
        view.findViewById<MaterialCardView>(R.id.card_mode_timed).setOnClickListener { selectMode(AntiUninstallMode.TIMED) }
        view.findViewById<MaterialCardView>(R.id.card_mode_cooldown).setOnClickListener { selectMode(AntiUninstallMode.COOLDOWN) }
        selectMode(AntiUninstallMode.PASSWORD)

        view.findViewById<MaterialButton>(R.id.btn_enable).setOnClickListener { onEnableClicked() }
        view.findViewById<MaterialButton>(R.id.btn_turn_off).setOnClickListener { onTurnOffClicked() }
        cancelUnlockButton.setOnClickListener { cancelUnlock() }

        view.findViewById<MaterialButton>(R.id.btn_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        view.findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(
                it,
                getString(R.string.anti_uninstall_help_popup),
                "https://curbox.app/docs/"
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settings.collect { settings ->
                    val config = settings.antiUninstallConfig
                    // The user may have pulled the device admin from system settings. Keep our flag honest.
                    if (config.isEnabled && !AntiUninstallManager.isAdminActive(requireContext())) {
                        dataStore.updateAntiUninstallConfig { it.copy(isEnabled = false, unlockRequestedAtMs = 0L) }
                        return@collect
                    }
                    latestConfig = config
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

    private fun selectMode(mode: AntiUninstallMode) {
        selectedMode = mode
        radioPassword.isChecked = mode == AntiUninstallMode.PASSWORD
        radioTimed.isChecked = mode == AntiUninstallMode.TIMED
        radioCooldown.isChecked = mode == AntiUninstallMode.COOLDOWN
        daysInputLayout.isVisible = mode == AntiUninstallMode.TIMED
    }

    private fun render() {
        val config = latestConfig
        val isProtected = config.isEnabled && AntiUninstallManager.isAdminActive(requireContext())

        if (!isProtected) {
            groupSetup.isVisible = true
            groupProtected.isVisible = false
            groupUnlocking.isVisible = false
            statusTitle.text = getString(R.string.anti_uninstall_status_off_title)
            statusBody.text = getString(R.string.anti_uninstall_status_off_body)
            return
        }

        val completesAt = AntiUninstallManager.unlockCompletesAt(config)
        if (completesAt != null && System.currentTimeMillis() >= completesAt) {
            finishProtection()
            return
        }

        groupSetup.isVisible = false

        if (completesAt == null) {
            groupProtected.isVisible = true
            groupUnlocking.isVisible = false
            statusTitle.text = getString(R.string.anti_uninstall_status_on_title)
            statusBody.text = modeSummary(config)
        } else {
            groupProtected.isVisible = false
            groupUnlocking.isVisible = true
            cancelUnlockButton.isVisible = config.mode == AntiUninstallMode.COOLDOWN
            statusTitle.text = getString(R.string.anti_uninstall_status_unlocking_title)
            statusBody.text = getString(
                R.string.anti_uninstall_status_unlocking_body,
                formatRemaining(completesAt - System.currentTimeMillis())
            )
        }
    }

    private fun modeSummary(config: AntiUninstallConfig): String = when (config.mode) {
        AntiUninstallMode.PASSWORD -> getString(R.string.anti_uninstall_summary_password)
        AntiUninstallMode.TIMED -> resources.getQuantityString(
            R.plurals.anti_uninstall_summary_timed, config.timedUnlockDays, config.timedUnlockDays
        )
        AntiUninstallMode.COOLDOWN -> getString(R.string.anti_uninstall_summary_cooldown)
    }

    private fun onEnableClicked() {
        when (selectedMode) {
            AntiUninstallMode.PASSWORD -> showSetPasswordDialog { hash ->
                pendingMode = AntiUninstallMode.PASSWORD
                pendingHash = hash
                requestAdminThenEnable()
            }
            AntiUninstallMode.TIMED -> {
                val days = daysInput.text?.toString()?.toIntOrNull() ?: 0
                if (days < 1) {
                    daysInputLayout.error = getString(R.string.anti_uninstall_days_error)
                    return
                }
                daysInputLayout.error = null
                pendingMode = AntiUninstallMode.TIMED
                pendingDays = days
                requestAdminThenEnable()
            }
            AntiUninstallMode.COOLDOWN -> {
                pendingMode = AntiUninstallMode.COOLDOWN
                requestAdminThenEnable()
            }
        }
    }

    private fun requestAdminThenEnable() {
        if (AntiUninstallManager.isAdminActive(requireContext())) {
            enableProtection()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AntiUninstallManager.adminComponent(requireContext()))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.anti_uninstall_admin_explanation)
            )
        }
        adminLauncher.launch(intent)
    }

    private fun enableProtection() {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateAntiUninstallConfig {
                it.copy(
                    isEnabled = true,
                    mode = pendingMode,
                    passwordHash = if (pendingMode == AntiUninstallMode.PASSWORD) pendingHash else "",
                    timedUnlockDays = if (pendingMode == AntiUninstallMode.TIMED) pendingDays else it.timedUnlockDays,
                    unlockRequestedAtMs = 0L
                )
            }
            Toast.makeText(requireContext(), R.string.anti_uninstall_turned_on, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onTurnOffClicked() {
        when (latestConfig.mode) {
            AntiUninstallMode.PASSWORD -> showVerifyPasswordDialog {
                if (AntiUninstallManager.hashPassword(it) == latestConfig.passwordHash) {
                    finishProtection()
                } else {
                    Toast.makeText(requireContext(), R.string.anti_uninstall_wrong_password, Toast.LENGTH_SHORT).show()
                }
            }
            AntiUninstallMode.TIMED -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.anti_uninstall_turn_off)
                .setMessage(resources.getQuantityString(
                    R.plurals.anti_uninstall_timed_confirm, latestConfig.timedUnlockDays, latestConfig.timedUnlockDays
                ))
                .setNegativeButton(R.string.anti_uninstall_cancel, null)
                .setPositiveButton(R.string.anti_uninstall_start) { _, _ -> startUnlockRequest() }
                .show()
            AntiUninstallMode.COOLDOWN -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.anti_uninstall_turn_off)
                .setMessage(R.string.anti_uninstall_cooldown_confirm)
                .setNegativeButton(R.string.anti_uninstall_cancel, null)
                .setPositiveButton(R.string.anti_uninstall_start) { _, _ -> startUnlockRequest() }
                .show()
        }
    }

    private fun startUnlockRequest() {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateAntiUninstallConfig { it.copy(unlockRequestedAtMs = System.currentTimeMillis()) }
        }
    }

    private fun cancelUnlock() {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateAntiUninstallConfig { it.copy(unlockRequestedAtMs = 0L) }
        }
    }

    private fun finishProtection() {
        AntiUninstallManager.removeProtection(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateAntiUninstallConfig { it.copy(isEnabled = false, unlockRequestedAtMs = 0L) }
        }
    }

    private fun showSetPasswordDialog(onPassword: (hash: String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_anti_uninstall_password, null)
        val passwordField = dialogView.findViewById<TextInputEditText>(R.id.input_password)
        val confirmField = dialogView.findViewById<TextInputEditText>(R.id.input_confirm)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anti_uninstall_set_password_title)
            .setView(dialogView)
            .setNegativeButton(R.string.anti_uninstall_cancel, null)
            .setPositiveButton(R.string.anti_uninstall_save) { _, _ ->
                val password = passwordField.text?.toString().orEmpty()
                val confirm = confirmField.text?.toString().orEmpty()
                when {
                    password.length < 4 ->
                        Toast.makeText(requireContext(), R.string.anti_uninstall_password_too_short, Toast.LENGTH_SHORT).show()
                    password != confirm ->
                        Toast.makeText(requireContext(), R.string.anti_uninstall_password_mismatch, Toast.LENGTH_SHORT).show()
                    else -> onPassword(AntiUninstallManager.hashPassword(password))
                }
            }
            .show()
    }

    private fun showVerifyPasswordDialog(onPassword: (password: String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_anti_uninstall_password, null)
        val passwordField = dialogView.findViewById<TextInputEditText>(R.id.input_password)
        // Only a single field is needed to confirm an existing password.
        dialogView.findViewById<TextInputLayout>(R.id.input_layout_confirm).isVisible = false

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anti_uninstall_enter_password_title)
            .setView(dialogView)
            .setNegativeButton(R.string.anti_uninstall_cancel, null)
            .setPositiveButton(R.string.anti_uninstall_turn_off) { _, _ ->
                onPassword(passwordField.text?.toString().orEmpty())
            }
            .show()
    }

    private fun formatRemaining(millis: Long): String {
        val clamped = millis.coerceAtLeast(0)
        val days = TimeUnit.MILLISECONDS.toDays(clamped)
        val hours = TimeUnit.MILLISECONDS.toHours(clamped) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(clamped) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(clamped) % 60
        return if (days > 0) {
            getString(R.string.anti_uninstall_remaining_with_days, days, hours, minutes, seconds)
        } else {
            getString(R.string.anti_uninstall_remaining, hours, minutes, seconds)
        }
    }
}
