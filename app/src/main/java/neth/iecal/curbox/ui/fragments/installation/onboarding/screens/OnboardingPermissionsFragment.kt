package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.databinding.FragmentOnboardingPermissionsBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.BackupViewModel
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingViewModel
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerSettingViewModel
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.utils.OnboardingState
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.setAsciiArt

class OnboardingPermissionsFragment : Fragment() {

    private var _binding: FragmentOnboardingPermissionsBinding? = null
    private val binding get() = _binding!!

    private val onboardingViewModel: OnboardingViewModel by activityViewModels()
    private val appBlockerViewModel: AppBlockerSettingViewModel by activityViewModels()
    private val backupViewModel: BackupViewModel by viewModels()
    private var completingSetup = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            updatePermissionsState()
        }

    private val shizukuPermissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001 && grantResult == PackageManager.PERMISSION_GRANTED) {
            activity?.runOnUiThread {
                runShizukuGrantAllCommand()
            }
        }
    }

    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && OnboardingState.canRestore(requireContext())) {
            backupViewModel.restore(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            try {
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.asciiArt.setAsciiArt("ascii_key")

        // Completed users also reach this screen when a required permission is lost.
        // Restore eligibility must not prevent them from granting it again.
        setupRestore()
        binding.btnAction.setOnClickListener {
            if (completingSetup || backupViewModel.status.value == BackupViewModel.Status.WORKING) {
                return@setOnClickListener
            }
            completingSetup = true
            updatePermissionsState()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    OnboardingState.complete(requireContext()) { createStarterGroup() }
                    openMainScreen()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    CrashLogger(requireContext()).logNonFatalError(error)
                    Toast.makeText(requireContext(), R.string.onboarding_finish_failed, Toast.LENGTH_LONG).show()
                } finally {
                    completingSetup = false
                    if (_binding != null) updatePermissionsState()
                }
            }
        }

        binding.overlayPermRoot.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) return@setOnClickListener
            showExplanationDialog(
                title = getString(R.string.onboarding_perm_overlay_title),
                rationale = getString(R.string.onboarding_perm_overlay_rationale),
                openSourceExplanation = getString(R.string.onboarding_perm_overlay_opensource)
            ) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
            }
        }

        binding.notifPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isNotificationPermissionGiven(requireContext())) return@setOnClickListener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showExplanationDialog(
                    title = getString(R.string.onboarding_perm_notif_title),
                    rationale = getString(R.string.onboarding_perm_notif_rationale),
                    openSourceExplanation = getString(R.string.onboarding_perm_notif_opensource)
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        binding.blockerAccPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)) return@setOnClickListener
            showExplanationDialog(
                title = getString(R.string.onboarding_perm_accessibility_title),
                rationale = getString(R.string.onboarding_perm_accessibility_rationale),
                openSourceExplanation = getString(R.string.onboarding_perm_accessibility_opensource)
            ) {
                PermissionUtils.openAccessibilityServiceScreen(requireContext(),AppBlockerService::class.java)
            }
        }

        binding.btnShowRestrictedTutorial.setOnClickListener {
            val manufacturer = Build.MANUFACTURER
            val query = Uri.encode("How to enable restricted setting on $manufacturer android 13")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))
            startActivity(intent)
        }


        binding.btnShizukuGrantAll.setOnClickListener {
            if (!neth.iecal.curbox.utils.PermissionUtils.hasShizukuPermission()) {
                try {
                    rikka.shizuku.Shizuku.requestPermission(1001)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                runShizukuGrantAllCommand()
            }
        }

        setupDescText()
        updatePermissionsState()
    }

    private fun setupRestore() {
        binding.btnRestoreBackup.setOnClickListener {
            if (!OnboardingState.canRestore(requireContext()) || completingSetup) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.restore_backup)
                .setMessage(R.string.backup_restore_confirmation)
                .setPositiveButton(R.string.restore_backup) { _, _ -> restorePicker.launch(arrayOf("*/*")) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                backupViewModel.status.collect { status ->
                    binding.restoreProgress.visibility =
                        if (status == BackupViewModel.Status.WORKING) View.VISIBLE else View.GONE
                    updatePermissionsState()
                    val message = when (status) {
                        BackupViewModel.Status.SUCCEEDED -> R.string.backup_restored
                        BackupViewModel.Status.FAILED -> R.string.backup_restore_failed
                        else -> null
                    }
                    if (message != null) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                        backupViewModel.dismissResult()
                    }
                }
            }
        }
    }

    private suspend fun createStarterGroup() {
        val targetApp = onboardingViewModel.targetAppPackage.value
        val limit = onboardingViewModel.dailyLimitMinutes.value ?: 30L
        val packageMap = mapOf(
            "Instagram" to "com.instagram.android",
            "TikTok" to "com.zhiliaoapp.musically",
            "YouTube" to "com.google.android.youtube",
            "Reddit" to "com.reddit.frontpage"
        )
        val pkg = packageMap[targetApp] ?: return
        appBlockerViewModel.addGroupAndWait(AppGroup(
            id = UUID.randomUUID().toString(),
            name = getString(R.string.onboarding_app_limit_group_name, targetApp),
            selectedPackages = listOf(pkg),
            config = AppGroupConfig(
                schedule = AppTimeConfig.allDay(),
                usage = AppUsageConfig(isDailyUniform = true, uniformLimit = limit, dailyLimits = LongArray(7) { limit })
            ),
            isActive = true,
            warningScreenConfig = AppBlockerWarningScreenConfig()
        ))
    }

    private fun openMainScreen() {
        startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
            putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
        })
        activity?.finish()
    }

    private fun setupDescText() {
        val baseText = getString(R.string.to_create_friction_and_give_you)
        val actionText = getString(R.string.onboarding_read_documentation)
        val fullText = "$baseText $actionText"
        val spannableString = SpannableString(fullText)

        val clickableSpan = object : ClickableSpan() {
            override fun onClick(view: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://curbox.app/docs"))
                startActivity(intent)
            }
        }

        val start = fullText.indexOf(actionText)
        val end = start + actionText.length

        spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(
            ForegroundColorSpan(MaterialColors.getColor(binding.desc, com.google.android.material.R.attr.colorPrimary)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannableString.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        binding.desc.text = spannableString
        binding.desc.movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updatePermissionsState()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            try {
                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showExplanationDialog(title: String, rationale: String, openSourceExplanation: String, onProceed: () -> Unit) {
        val privacy = getString(R.string.onboarding_privacy_note)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(rationale + privacy + openSourceExplanation)
            .setPositiveButton(R.string.proceed) { _, _ -> onProceed() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runShizukuGrantAllCommand() {
        binding.btnShizukuGrantAll.isEnabled = false
        binding.btnShizukuGrantAll.text = getString(R.string.onboarding_granting_permissions)

        val pkg = requireContext().packageName
        val svc1 = "$pkg/${AppBlockerService::class.java.name}"

        // Full and fdroid also take WRITE_SECURE_SETTINGS here so grayscale keeps
        // working without Shizuku later on.
        val secureSettingsGrant = if (neth.iecal.curbox.BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS) {
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
        } else {
            ""
        }

        val command = """
            appops set $pkg SYSTEM_ALERT_WINDOW allow
            pm grant $pkg android.permission.POST_NOTIFICATIONS
            $secureSettingsGrant
            cmd notification allow_dnd $pkg

            CURRENT_ACC_SVCS=${'$'}(settings get secure enabled_accessibility_services)
            if [ "${'$'}CURRENT_ACC_SVCS" = "null" ] || [ -z "${'$'}CURRENT_ACC_SVCS" ]; then
                settings put secure enabled_accessibility_services "$svc1"
            else
                NEW_SVCS="${'$'}CURRENT_ACC_SVCS"
                case "${'$'}CURRENT_ACC_SVCS" in
                    *"$svc1"*) ;;
                    *) NEW_SVCS="${'$'}NEW_SVCS:$svc1" ;;
                esac
                settings put secure enabled_accessibility_services "${'$'}NEW_SVCS"
            fi
            settings put secure accessibility_enabled 1
        """.trimIndent()

        neth.iecal.curbox.utils.ShizukuRunner.executeCommand(command, object : neth.iecal.curbox.utils.ShizukuRunner.CommandResultListener {
            override fun onCommandResult(output: String, done: Boolean) {
                if (done) {
                    activity?.runOnUiThread {
                        binding.btnShizukuGrantAll.text = getString(R.string.onboarding_permissions_granted)
                        binding.btnShizukuGrantAll.isEnabled = true
                        updatePermissionsState()
                    }
                }
            }

            override fun onCommandError(error: String) {
                activity?.runOnUiThread {
                    binding.btnShizukuGrantAll.isEnabled = true
                    binding.btnShizukuGrantAll.text = getString(R.string.onboarding_error_tap_retry)
                    updatePermissionsState()
                }
            }
        })
    }

    private fun updatePermissionsState() {
        val hasOverlay = Settings.canDrawOverlays(requireContext())
        val hasNotif = neth.iecal.curbox.utils.PermissionUtils.isNotificationPermissionGiven(requireContext())
        val hasBlocker = neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)
        val hasShizuku = neth.iecal.curbox.utils.PermissionUtils.hasShizukuPermission()

        val isNonSession = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val info = requireContext().packageManager.getInstallSourceInfo(requireContext().packageName)
                val initiatingPackage = info.initiatingPackageName
                initiatingPackage != "com.android.vending" && initiatingPackage != "org.fdroid.fdroid"
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }

        val showRestrictedWarning = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasBlocker && isNonSession
        binding.restrictedSettingsWarning.visibility = if (showRestrictedWarning) View.VISIBLE else View.GONE
        
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            binding.btnShizukuGrantAll.visibility = View.VISIBLE
        } else {
            binding.btnShizukuGrantAll.visibility = View.GONE
        }

        setPermissionIcon(hasOverlay, binding.overlayPermIcon)
        setPermissionIcon(hasNotif, binding.notifPermIcon)
        setPermissionIcon(hasBlocker, binding.blockerAccPermIcon)

        // Enforce Sequence
        binding.overlayPermRoot.isEnabled = !hasOverlay
        binding.overlayPermRoot.alpha = if (hasOverlay) 0.5f else 1.0f

        val canDoNotif = hasOverlay
        binding.notifPermRoot.isEnabled = canDoNotif && !hasNotif
        binding.notifPermRoot.alpha = if (canDoNotif) (if (hasNotif) 0.5f else 1.0f) else 0.3f

        val canDoBlocker = canDoNotif && hasNotif
        binding.blockerAccPermRoot.isEnabled = canDoBlocker && !hasBlocker
        binding.blockerAccPermRoot.alpha = if (canDoBlocker) (if (hasBlocker) 0.5f else 1.0f) else 0.3f

        val allGranted = hasOverlay && hasNotif && hasBlocker
        val busy = completingSetup || backupViewModel.status.value == BackupViewModel.Status.WORKING
        binding.btnAction.isEnabled = allGranted && !busy
        binding.btnRestoreBackup.isEnabled = !busy && OnboardingState.canRestore(requireContext())
        if (allGranted) {
            binding.btnAction.text = getString(R.string.onboarding_curb_me)
        } else {
            binding.btnAction.text = getString(R.string.onboarding_need_more_permissions)
        }
    }

    private fun setPermissionIcon(isEnabled: Boolean, icon: ImageView) {
        if (isEnabled) {
            icon.setImageResource(R.drawable.baseline_done_24)
            icon.setColorFilter(resources.getColor(R.color.md_theme_onSurface, requireContext().theme))
        } else {
            icon.setImageResource(R.drawable.baseline_close_24)
            icon.setColorFilter(resources.getColor(R.color.error_color, requireContext().theme))
        }
    }
}
