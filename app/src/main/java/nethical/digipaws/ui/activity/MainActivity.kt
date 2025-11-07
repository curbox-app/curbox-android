package nethical.digipaws.ui.activity

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nethical.digipaws.Constants
import nethical.digipaws.R
import nethical.digipaws.databinding.ActivityMainBinding
import nethical.digipaws.databinding.DialogPermissionInfoBinding
import nethical.digipaws.databinding.DialogRemoveAntiUninstallBinding
import nethical.digipaws.receivers.AdminReceiver
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.services.GeneralFeaturesService
import nethical.digipaws.services.KeywordBlockerService
import nethical.digipaws.services.UsageTrackingService
import nethical.digipaws.services.ViewBlockerService
import nethical.digipaws.ui.dialogs.StartFocusMode
import nethical.digipaws.ui.dialogs.TweakAppBlockerWarning
import nethical.digipaws.ui.dialogs.TweakGrayScaleMode
import nethical.digipaws.ui.dialogs.TweakKeywordBlocker
import nethical.digipaws.ui.dialogs.TweakKeywordPack
import nethical.digipaws.ui.dialogs.TweakUsageTracker
import nethical.digipaws.ui.dialogs.TweakViewBlockerCheatHours
import nethical.digipaws.ui.dialogs.TweakViewBlockerWarning
import nethical.digipaws.ui.fragments.anti_uninstall.ChooseModeFragment
import nethical.digipaws.ui.fragments.installation.AccessibilityGuide
import nethical.digipaws.ui.fragments.installation.WelcomeFragment
import nethical.digipaws.ui.fragments.usage.AllAppsUsageFragment
import nethical.digipaws.utils.SavedPreferencesLoader
import nethical.digipaws.utils.ZipUtils
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var selectPinnedAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var selectGrayScaleApps: ActivityResultLauncher<Intent>

    private lateinit var selectBlockedAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var selectFocusModeUnblockedAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var selectOverlayAppsLauncher: ActivityResultLauncher<Intent>

    private lateinit var selectBlockedKeywords: ActivityResultLauncher<Intent>

    private lateinit var addCheatHoursActivity: ActivityResultLauncher<Intent>

    private lateinit var addAutoFocusHoursActivity: ActivityResultLauncher<Intent>

    private lateinit var directoryPicker: ActivityResultLauncher<Intent>


    private val savedPreferencesLoader = SavedPreferencesLoader(this)
    private lateinit var options: ActivityOptionsCompat
    private var isDeviceAdminOn = false
    private var isAntiUninstallOn = false

    private var isGeneralSettingsOn = false
    private var isDisplayOverOtherAppsOn = false

    private var isShizukuBinderRecieved = false
    private val BINDER_RECEIVED_LISTENER = OnBinderReceivedListener {
        if (!Shizuku.isPreV11()) {
            isShizukuBinderRecieved = true
            checkPermissions()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Permission granted, show notifications
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()

//                makeStartFocusModeDialog()
            } else {
                // Permission denied
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()

            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }


        // Listen for Shizuku permission results
        Shizuku.addRequestPermissionResultListener { requestCode, resultCode ->
            if (requestCode == 0 && resultCode == PackageManager.PERMISSION_GRANTED) {
                checkPermissions()
            }
        }

        options = ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fade_in, R.anim.fade_out)
        setupActivityLaunchers()
        setupClickListeners()

        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER);

        if (!isFirstLaunchComplete()) {
            val intent = Intent(this, FragmentActivity::class.java)
            intent.putExtra("fragment", WelcomeFragment.FRAGMENT_ID)
            startActivity(intent, options.toBundle())
        }
        showDonationDialog()
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER);
    }
    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun setupActivityLaunchers() {

        selectPinnedAppsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                selectedApps?.let {
                    savedPreferencesLoader.savePinned(it.toSet())
                }
            }
        }

        selectBlockedAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        savedPreferencesLoader.saveBlockedApps(it.toSet())
                        sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER)
                    }
                }
            }


        selectGrayScaleApps =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        savedPreferencesLoader.saveGrayScaleApps(it.toSet())
                        sendRefreshRequest(GeneralFeaturesService.INTENT_ACTION_REFRESH_GRAYSCALE)
                    }
                }
            }



        selectFocusModeUnblockedAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        savedPreferencesLoader.saveFocusModeSelectedApps(selectedApps)
                    }
                }
            }

        selectOverlayAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        savedPreferencesLoader.setOverlayApps(it.toSet())
                    }
                }
            }

        selectBlockedKeywords =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val blockedKeywords = result.data?.getStringArrayListExtra("SELECTED_KEYWORDS")
                    blockedKeywords?.let {
                        savedPreferencesLoader.saveBlockedKeywords(it.toSet())
                        sendRefreshRequest(KeywordBlockerService.INTENT_ACTION_REFRESH_BLOCKED_KEYWORD_LIST)
                    }
                }
            }

        addCheatHoursActivity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
                sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER)
            }

        addAutoFocusHoursActivity =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
                sendRefreshRequest(AppBlockerService.INTENT_ACTION_REFRESH_FOCUS_MODE)
            }
        // Register the directory picker
        directoryPicker = ZipUtils.registerDirectoryPicker(this) { directoryUri ->
            // Create the zip file in the selected directory
            val filename = ZipUtils.createZipFileName()
            val zipUri = createFileInDirectory(directoryUri, filename)
            zipUri?.let {
                ZipUtils.zipSharedPreferencesToUri(this, it)
            }
        }
    }

    private fun setupClickListeners() {
        // click listeners for configuration options
        binding.selectPinnedApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.loadPinnedApps())
            )

            selectPinnedAppsLauncher.launch(intent, options)

        }
        binding.selectMonochromeApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.loadGrayScaleApps())
            )

            selectGrayScaleApps.launch(intent, options)

        }
        binding.selectBlockedApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.loadBlockedApps())
            )
            selectBlockedAppsLauncher.launch(intent, options)
        }
        binding.selectBlockedKeywords.setOnClickListener {
            val intent = Intent(this, ManageKeywordsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SAVED_KEYWORDS",
                ArrayList(savedPreferencesLoader.loadBlockedKeywords())
            )
            selectBlockedKeywords.launch(intent, options)
        }


        binding.appBlockerSelectCheatHours.setOnClickListener {
            val intent = Intent(this, TimedActionActivity::class.java)
            intent.putExtra("selected_mode", TimedActionActivity.MODE_APP_BLOCKER_CHEAT_HOURS)
            addCheatHoursActivity.launch(intent, options)
        }
        binding.btnConfigAppblockerWarning.setOnClickListener {
            TweakAppBlockerWarning(savedPreferencesLoader).show(
                supportFragmentManager,
                "tweak_app_blocker_warning"
            )
        }
        binding.btnConfigViewblockerWarning.setOnClickListener {
            TweakViewBlockerWarning(savedPreferencesLoader).show(
                supportFragmentManager,
                "tweak_view_blocker_warning"
            )
        }
        binding.btnConfigViewblockerCheatHours.setOnClickListener {
            TweakViewBlockerCheatHours(savedPreferencesLoader).show(
                supportFragmentManager,
                "tweak_view_blocker_cheat_hours"
            )
        }
        binding.btnConfigTracker.setOnClickListener{
            TweakUsageTracker(savedPreferencesLoader).show(
                supportFragmentManager,
                "tweak_usage_tracker"
            )
        }
        binding.btnUnlockAntiUninstall.setOnClickListener {
            makeRemoveAntiUninstallDialog()
        }
        binding.btnManagePreinstalledKeywords.setOnClickListener {
            TweakKeywordPack().show(supportFragmentManager, "tweak_keyword_pack")
        }
        binding.btnManageKeywordBlocker.setOnClickListener {
            TweakKeywordBlocker(savedPreferencesLoader).show(
                supportFragmentManager,
                "tweak_keyword_blocker"
            )
        }
        binding.selectAppUsageStats.setOnClickListener {
            val intent = Intent(this, FragmentActivity::class.java)
            intent.putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
            startActivity(intent, options.toBundle())
        }

        binding.selectReelUsageStats.setOnClickListener {
            val intent = Intent(this, UsageMetricsActivity::class.java)
            startActivity(intent, options.toBundle())
        }
        binding.btnSelectAppsToShowOverlay.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.getOverlayApps())
            )
            selectOverlayAppsLauncher.launch(intent, options)
        }
        binding.selectFocusBlockedApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                ArrayList(savedPreferencesLoader.getFocusModeSelectedApps())
            )
            selectFocusModeUnblockedAppsLauncher.launch(intent, options)
        }
        binding.autoFocus.setOnClickListener {
            val intent = Intent(this, TimedActionActivity::class.java)
            intent.putExtra("selected_mode", TimedActionActivity.MODE_AUTO_FOCUS)
            addAutoFocusHoursActivity.launch(intent, options)
        }


        binding.startFocusMode.setOnClickListener {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS,options)
                    return@setOnClickListener
                }
            }


            createFocusModeShortcut()

            StartFocusMode(savedPreferencesLoader, onPositiveButtonPressed = {
                binding.selectFocusBlockedApps.isEnabled = false
                binding.startFocusMode.isEnabled = false

            }).show(
                supportFragmentManager,
                "start_focus_mode"
            )

        }

        // listeners for turn on/ off buttons
        binding.antiUninstallCardChip.setOnClickListener {
            if (!isDeviceAdminOn) {
                makeDeviceAdminPermissionDialog()
            } else {
                if (binding.antiUninstallWarning.visibility == View.GONE) {
                    val intent = Intent(this, FragmentActivity::class.java)
                    intent.putExtra("fragment", ChooseModeFragment.FRAGMENT_ID)
                    startActivity(intent, options.toBundle())
                } else {
                    makeAccessibilityInfoDialog(
                        "General Features",
                        GeneralFeaturesService::class.java
                    )
                }
            }
        }

        binding.monochromeStatusChip.setOnClickListener {
            if(!isGeneralSettingsOn){
                makeAccessibilityInfoDialog("General Features", GeneralFeaturesService::class.java)
                return@setOnClickListener
            }
            if(isShizukuBinderRecieved){
                if( (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)){
                    Shizuku.requestPermission(0)
                }
            }else{
                val packageManager = packageManager
                try {
                    // Check if Shizuku is installed
                    packageManager.getPackageInfo(
                        "moe.shizuku.privileged.api",
                        PackageManager.GET_ACTIVITIES
                    )
                    Toast.makeText(this,"Failed! Make sure that shizuku is active",Toast.LENGTH_SHORT).show()
                } catch (e: PackageManager.NameNotFoundException) {
                    // Shizuku is not installed
                    Log.d("Shizuku", "Shizuku is not installed on the device.")
                    makeShizukuInfoDialog()
                }
            }
        }

        binding.keywordBlockerStatusChip.setOnClickListener {
            makeAccessibilityInfoDialog("Keyword Blocker", KeywordBlockerService::class.java)
        }
        binding.focusModeStatusChip.setOnClickListener {
            makeAccessibilityInfoDialog("App Blocker", AppBlockerService::class.java)
        }
        binding.appBlockerStatusChip.setOnClickListener {
            makeAccessibilityInfoDialog("App Blocker", AppBlockerService::class.java)
        }
        binding.viewBlockerStatusChip.setOnClickListener {
            makeAccessibilityInfoDialog("View Blocker", ViewBlockerService::class.java)
        }
        binding.usageTrackerStatusChip.setOnClickListener {
            if (!isDisplayOverOtherAppsOn) {
                makeDrawOverOtherAppsDialog()
            } else {
                makeAccessibilityInfoDialog("Usage Tracker", UsageTrackingService::class.java)
            }
        }

        binding.setupMonochrome.setOnClickListener {
            TweakGrayScaleMode(savedPreferencesLoader).show(
                supportFragmentManager,
                "tweak_monochrome"
            )
        }

        // socials click listeners
        binding.btnDiscord.setOnClickListener {
            openUrl("https://discord.com/invite/Vs9mwUtuCN")
        }

        binding.btnTelegram.setOnClickListener {
            openUrl("https://t.me/digipaws6")
        }
        binding.btnGithub.setOnClickListener {
            openUrl("https://github.com/nethical6/digipaws")
        }
        binding.btnInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/digipaws.app")
        }
        binding.btnDonate.setOnClickListener {
            openUrl("https://digipaws.life/donate")
        }

        binding.btnCredits.setOnClickListener {
            openUrl("https://digipaws.life/credits")
        }
        binding.btnBackup.setOnClickListener {
            ZipUtils.showDirectoryPicker(directoryPicker)
        }
        binding.helpReelBlocker.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.about_view_blocker))
                .setMessage(getString(R.string.this_option_has_the_ability_to_block_youtube_shorts_and_instagram_reels_while_allowing_access_to_other_app_features))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }

        binding.btnBackup.setOnClickListener {
            ZipUtils.showDirectoryPicker(directoryPicker)
        }
        binding.btnShareErrors.setOnClickListener {
            shareCrashLog(this)
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent, options.toBundle())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No application found to open the link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {

        isDisplayOverOtherAppsOn = Settings.canDrawOverlays(this)
        lifecycleScope.launch {
            val isAppBlockerOn =
                withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(AppBlockerService::class.java) }
            val isViewBlockerOn =
                withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(ViewBlockerService::class.java) }
            val isKeywordBlockerOn =
                withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(KeywordBlockerService::class.java) }
            val isUsageTrackerOn =
                withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(UsageTrackingService::class.java) }
            isGeneralSettingsOn =
                withContext(Dispatchers.IO) { isAccessibilityServiceEnabled(GeneralFeaturesService::class.java) }

            val devicePolicyManager =
                getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(applicationContext, AdminReceiver::class.java)

            // Check if Device Admin is active
            isDeviceAdminOn = devicePolicyManager.isAdminActive(componentName)

            val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
            isAntiUninstallOn = antiUninstallInfo.getBoolean("is_anti_uninstall_on", false)
            val doesAntiUninstallBlockView =
                antiUninstallInfo.getBoolean("is_configuring_blocked", false)

            withContext(Dispatchers.Main) {
                // App Blocker
                updateChip(isAppBlockerOn, binding.appBlockerStatusChip, binding.appBlockerWarning)
                binding.apply {
                    selectBlockedApps.isEnabled = isAppBlockerOn
                    btnConfigAppblockerWarning.isEnabled = isAppBlockerOn
                    appBlockerSelectCheatHours.isEnabled = isAppBlockerOn
                }

                // View Blocker
                updateChip(
                    isViewBlockerOn, binding.viewBlockerStatusChip, binding.viewBlockerWarning
                )
                binding.apply {
                    btnConfigViewblockerCheatHours.isEnabled = isViewBlockerOn
                    btnConfigViewblockerWarning.isEnabled = isViewBlockerOn
                }

                // Keyword Blocker
                updateChip(
                    isKeywordBlockerOn,
                    binding.keywordBlockerStatusChip,
                    binding.keywordBlockerWarning
                )
                binding.apply {
                    selectBlockedKeywords.isEnabled = isKeywordBlockerOn
                    btnManagePreinstalledKeywords.isEnabled = isKeywordBlockerOn
                    btnManageKeywordBlocker.isEnabled = isKeywordBlockerOn
                }

                // Usage Tracker
                if (!isDisplayOverOtherAppsOn) {
                    binding.usageTrackerWarning.text =
                        getString(R.string.please_provide_display_over_other_apps_permission_to_access_this_feature)
                } else if (!isGeneralSettingsOn) {
                    binding.usageTrackerWarning.text =
                        getString(R.string.warning_usage_tracker_settings)
                }
                if (isUsageTrackerOn && isDisplayOverOtherAppsOn) {
                    updateChip(
                        true,
                        binding.usageTrackerStatusChip,
                        binding.usageTrackerWarning
                    )
                    binding.apply {
                        selectReelUsageStats.isEnabled = true
                        btnSelectAppsToShowOverlay.isEnabled = true
                        btnConfigTracker.isEnabled = true
                    }
                }


                // General Settings
                updateChip(
                    isAppBlockerOn,
                    binding.focusModeStatusChip,
                    binding.focusModeWarning
                )
                binding.apply {
                    startFocusMode.isEnabled = isAppBlockerOn
                    selectFocusBlockedApps.isEnabled = isAppBlockerOn
                    autoFocus.isEnabled = isAppBlockerOn
                }

                // Anti-Uninstall settings
                binding.btnUnlockAntiUninstall.isEnabled = isAntiUninstallOn

                // Update Anti-Uninstall warning
                if (!isDeviceAdminOn) {
                    binding.antiUninstallWarning.text =
                        getString(R.string.please_enable_device_admin)
                } else if (!isGeneralSettingsOn) {
                    binding.antiUninstallWarning.text = getString(R.string.warning_general_settings)
                }

                // Handle anti-uninstall UI changes
                if (isDeviceAdminOn && isGeneralSettingsOn) {
                    updateChip(true, binding.antiUninstallCardChip, binding.antiUninstallWarning)
                    binding.antiUninstallCardChip.isEnabled = !isAntiUninstallOn
                    binding.antiUninstallCardChip.text =
                        if (isAntiUninstallOn) getString(R.string.setup_complete) else getString(R.string.enter_setup)
                }

                if (doesAntiUninstallBlockView && isAntiUninstallOn) {
                    binding.apply {
                        btnConfigAppblockerWarning.isEnabled = false
                        btnManagePreinstalledKeywords.isEnabled = false
                        btnManageKeywordBlocker.isEnabled = false
                        btnConfigViewblockerCheatHours.isEnabled = false
                        selectBlockedKeywords.isEnabled = false
                        selectBlockedApps.isEnabled = false
                        appBlockerSelectCheatHours.isEnabled = false
                        btnConfigViewblockerWarning.isEnabled = false
                        startFocusMode.isEnabled = false
                    }
                }
                if (isAppBlockerOn) {
                    val isFocusedModeOn = savedPreferencesLoader.getFocusModeData().isTurnedOn
                    binding.selectFocusBlockedApps.isEnabled = !isFocusedModeOn
                    binding.startFocusMode.isEnabled = !isFocusedModeOn
                }

                if(isGeneralSettingsOn){
                    binding.monochromeWarning.text = "Authorize digipaws to access Shizuku"
                    if(isShizukuBinderRecieved){
                        setupShizukuFeatures()
                    }
                }else{
                    binding.monochromeWarning.text = getString(R.string.warning_general_settings)
                    binding.monochromeStatusChip.text = getString(R.string.disabled)
                }

            }
        }
    }


    private fun setupShizukuFeatures(){
        val isShizukuOn = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        updateChip(
            isShizukuOn,
            binding.monochromeStatusChip,
            binding.monochromeWarning
        )


        binding.setupMonochrome.isEnabled = isShizukuOn
        binding.selectMonochromeApps.isEnabled = isShizukuOn
    }

    private fun showDonationDialog() {
        val sharedPreferences = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        val firstDate = sharedPreferences.getString("first_date", null)
        if (firstDate == null) {
            // Store the current date as a string representation
            val currentDateString = LocalDate.now().toString()
            sharedPreferences.edit().putString("first_date", currentDateString).apply()
        }

        if (!(sharedPreferences.getBoolean("is_donation_alerted", false))) {
            // Parse the stored date string back to LocalDate
            val storedFirstDate = firstDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
            val daysPassed = ChronoUnit.DAYS.between(storedFirstDate, LocalDate.now())

            Log.d("days passed", daysPassed.toString())
            if (daysPassed > 5L) {
                sharedPreferences.edit().putBoolean("is_donation_alerted", true).apply()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Consider Donating?")
                    .setMessage(
                        "Hello, this is Nethical, the creator of digipaws. I'm a 17-year-old high school student with a passion for technology and computers. " +
                                "A few months ago, I couldn't find any free app blocker solution that perfectly suited my needs, so I decided to build digipaws myself. " +
                                "I have a strong vision for digipaws, including integrating gamification features. However, I may have to unfortunately halt this project due to a lack of funding. " +
                                "If you find digipaws useful, please consider donating even a small amount to support its continued development. Thank you!"
                    )
                    .setNegativeButton("Close") { dialog, _ ->
                        dialog.dismiss()


                    }
                    .setPositiveButton("Donate") { dialog, _ ->
                        openUrl("https://digipaws.life/donate")
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }
    private fun isFirstLaunchComplete(): Boolean {
        val sharedPreferences = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("isFirstLaunchComplete", false)
    }

    fun shareCrashLog(context: Context) {
        val logFile = File(context.filesDir, "crash_log.txt")
        if (!logFile.exists()) {
            Toast.makeText(context, "No crash logs found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", logFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Crash Log")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share Crash Log"))
    }
    private fun updateChip(isEnabled: Boolean,statusChip: Chip,warningText:TextView) {
        if (isEnabled) {
            statusChip.text = getString(R.string.enabled)
            statusChip.chipIcon = null
            warningText.visibility = View.GONE
        } else {
            statusChip.text = getString(R.string.disabled)
            statusChip.setChipIconResource(R.drawable.baseline_warning_24)
            warningText.visibility = View.VISIBLE
        }
    }
    private fun sendRefreshRequest(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }
    private fun isAccessibilityServiceEnabled(serviceClass: Class<out AccessibilityService>): Boolean {
        val serviceName = ComponentName(this, serviceClass).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val isAccessibilityEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        return isAccessibilityEnabled == 1 && enabledServices.contains(serviceName)
    }

    private fun makeDeviceAdminPermissionDialog() {
        val dialogDeviceAdmin =
            DialogPermissionInfoBinding.inflate(layoutInflater)
        dialogDeviceAdmin.title.text = getString(R.string.enable_2, "Device Admin")
        dialogDeviceAdmin.desc.text = getString(R.string.device_admin_perm)
        dialogDeviceAdmin.point1.text =
            getString(R.string.prevent_uninstallation_attempts_until_a_set_condition_is_met)
        dialogDeviceAdmin.point2.visibility = View.GONE
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogDeviceAdmin.root)
            .show()

        dialogDeviceAdmin.btnReject.setOnClickListener {
            dialog.dismiss()
        }
        dialogDeviceAdmin.btnAccept.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            val componentName = ComponentName(this, AdminReceiver::class.java)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable admin to enable anti uninstall."
            )
            startActivity(intent, options.toBundle())

        }
    }

    private fun makeDrawOverOtherAppsDialog() {
        val dialogDisplayOverOtherApps =
            DialogPermissionInfoBinding.inflate(layoutInflater)
        dialogDisplayOverOtherApps.title.text =
            getString(R.string.enable_2, "Display Over Other Apps")
        dialogDisplayOverOtherApps.desc.text = getString(R.string.device_perm_draw_over_other_apps)
        dialogDisplayOverOtherApps.point1.text = getString(R.string.show_time_elapsed_on_phone)
        dialogDisplayOverOtherApps.point2.text =
            getString(R.string.calculate_how_many_reels_tiktok_short_videos_you_scroll_per_day)
        dialogDisplayOverOtherApps.point4.text = getString(R.string.plan_a_robbery)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogDisplayOverOtherApps.root)
            .show()

        dialogDisplayOverOtherApps.btnReject.setOnClickListener {
            dialog.dismiss()
        }
        dialogDisplayOverOtherApps.btnAccept.setOnClickListener {
            dialog.dismiss()
            Toast.makeText(
                this,
                getString(R.string.find_digipaws_and_press_enable), Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent, options.toBundle())

        }
    }

    private fun makeShizukuInfoDialog() {
        val permissionBinding =
            DialogPermissionInfoBinding.inflate(layoutInflater)
        permissionBinding.title.text = "Integrate Shizuku"

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(permissionBinding.root)
            .show()

        permissionBinding.btnAccept.text = "Download Shizuku"
        permissionBinding.btnReject.text = "Cancel"
        permissionBinding.desc.text =
            "Shizuku is a powerful Android app that allows other apps to access system-level features securely without rooting your device. It acts as a bridge, enabling apps to perform advanced tasks by running commands with elevated permissions."

        permissionBinding.point1.text = "control of the Daltonizer."
        permissionBinding.point2.text = "Make your phone boring."
        permissionBinding.point3.text = "Feels like using a 90s dumbphone"
        permissionBinding.point4.visibility = View.GONE

        permissionBinding.btnReject.setOnClickListener {
            dialog.dismiss()
        }
        permissionBinding.btnAccept.setOnClickListener {
            openUrl("https://shizuku.rikka.app/")
        }
            }

    private fun makeAccessibilityInfoDialog(title: String, cls: Class<*>) {
        val dialogAccessibilityServiceInfoBinding =
            DialogPermissionInfoBinding.inflate(layoutInflater)
        dialogAccessibilityServiceInfoBinding.title.text = getString(R.string.enable_2, title)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogAccessibilityServiceInfoBinding.root)
            .show()

        dialogAccessibilityServiceInfoBinding.btnReject.setOnClickListener {
            dialog.dismiss()
        }
        dialogAccessibilityServiceInfoBinding.btnAccept.setOnClickListener {
            Toast.makeText(this, "Find '$title' and press enable", Toast.LENGTH_LONG).show()
            openAccessibilityServiceScreen(cls)
            dialog.dismiss()
        }
        dialogAccessibilityServiceInfoBinding.btnGuide.visibility = View.VISIBLE
        dialogAccessibilityServiceInfoBinding.btnGuide.setOnClickListener {
            val intent = Intent(this, FragmentActivity::class.java)
            intent.putExtra("fragment", AccessibilityGuide.FRAGMENT_ID)
            startActivity(intent, options.toBundle())
        }
    }


    private fun createFocusModeShortcut() {

        val sp = getSharedPreferences("shortcuts",Context.MODE_PRIVATE)
        if(sp.getBoolean("focus_mode",false)){
            return
        }
        val intent = Intent(this, ShortcutActivity::class.java).apply {
            action = Intent.ACTION_CREATE_SHORTCUT
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(this, "digipaws_focus_mode")
            .setShortLabel(getString(R.string.focus_mode))
            .setLongLabel(getString(R.string.focus_mode))
            .setIntent(intent)
            .setIcon(IconCompat.createWithResource(this, R.drawable.focus_mode_icon))
            .build()


        val supported = ShortcutManagerCompat.isRequestPinShortcutSupported(this)
        val dynamicShortcuts = ShortcutManagerCompat.getDynamicShortcuts(this)

        if(supported){
            if(dynamicShortcuts.contains(shortcutInfo)){
                return
            }
        }
        MaterialAlertDialogBuilder(this).apply {
            setTitle("Add Focus Mode to Home Screen")
            setMessage("Would you like to add Focus Mode to your home screen for quick access?")
            setPositiveButton("Ok") { dialog, _ ->
                sp.edit().putBoolean("focus_mode",true).apply()
                val pinnedShortcutCallbackIntent = Intent("example.intent.action.SHORTCUT_CREATED")

                val successCallback = PendingIntent.getBroadcast(
                    this@MainActivity,
                    1000,
                    pinnedShortcutCallbackIntent,
                    FLAG_IMMUTABLE
                )

                ShortcutManagerCompat.requestPinShortcut(
                    this@MainActivity,
                    shortcutInfo,
                    successCallback.intentSender
                )

            }
            setNegativeButton("Cancel", { _,_ ->
                sp.edit().putBoolean("focus_mode",false).apply()
            })
            show()
        }

    }

    private fun openAccessibilityServiceScreen(cls: Class<*>) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(this, cls)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            val bundle = Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)
            startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to general Accessibility Settings
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun makeRemoveAntiUninstallDialog() {
        val antiUninstallInfo = getSharedPreferences("anti_uninstall", Context.MODE_PRIVATE)
        val mode = antiUninstallInfo.getInt("mode", -1)
        when (mode) {

            Constants.ANTI_UNINSTALL_TIMED_MODE -> {
                val dateString = antiUninstallInfo.getString("date", null)
                val parts: List<String> = dateString!!.split("/")
                val selectedDate = Calendar.getInstance()
                selectedDate.set(
                    Integer.parseInt(parts[2]),  // Year
                    Integer.parseInt(parts[0]) - 1,  // Month (0-based)
                    Integer.parseInt(parts[1]),  // Day
                    0,  // Hour
                    0,  // Minute
                    0   // Second
                )
                selectedDate.set(Calendar.MILLISECOND, 0)

                val today = Calendar.getInstance()
                today.set(Calendar.HOUR_OF_DAY, 0)
                today.set(Calendar.MINUTE, 0)
                today.set(Calendar.SECOND, 0)
                today.set(Calendar.MILLISECOND, 0)

                val daysDiff =
                    (selectedDate.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)
                if (selectedDate.before(today) || daysDiff.toInt() == 0) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.anti_uninstall_removed),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                    antiUninstallInfo.edit().putBoolean("is_anti_uninstall_on", false).commit()
                    sendRefreshRequest(GeneralFeaturesService.INTENT_ACTION_REFRESH_ANTI_UNINSTALL)

                } else {

                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.failed))
                        .setMessage(getString(R.string.remaining_time_anti_uninstall, daysDiff))
                        .setPositiveButton("Ok", null)
                        .show()
                }

            }

            Constants.ANTI_UNINSTALL_PASSWORD_MODE -> {
                val dialogRemoveAntiUninstall =
                    DialogRemoveAntiUninstallBinding.inflate(layoutInflater)
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.remove_anti_uninstall))
                    .setView(dialogRemoveAntiUninstall.root)
                    .setPositiveButton(R.string.remove) { _, _ ->
                        if (antiUninstallInfo.getString(
                                "password",
                                "pass"
                            ) == dialogRemoveAntiUninstall.password.text.toString()
                        ) {
                            antiUninstallInfo.edit().putBoolean("is_anti_uninstall_on", false)
                                .commit()
                            sendRefreshRequest(GeneralFeaturesService.INTENT_ACTION_REFRESH_ANTI_UNINSTALL)

                            Snackbar.make(
                                binding.root,
                                "Anti Uninstall removed",
                                Snackbar.LENGTH_SHORT
                            )
                                .show()

                            checkPermissions()
                        } else {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.incorrect_password_please_try_again),
                                Snackbar.LENGTH_SHORT
                            )
                                .setAction(getString(R.string.retry)) {
                                    makeRemoveAntiUninstallDialog()
                                }
                                .show()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }

    }
    private fun createFileInDirectory(directoryUri: Uri, filename: String): Uri? {
        return try {
            val docTree = DocumentFile.fromTreeUri(this, directoryUri)
            docTree?.createFile("application/zip", filename)?.uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    data class WarningData(
        val message: String = "You can setup a custom message to appear here!",
        val timeInterval: Int = 120000, // default cooldown period
        val isDynamicIntervalSettingAllowed: Boolean = false,
        val isProceedDisabled: Boolean = false,
        val isWarningDialogHidden: Boolean = false, // perform back/home action directly without showing warning screen
        val proceedDelayInSecs: Int = 15
    )


}