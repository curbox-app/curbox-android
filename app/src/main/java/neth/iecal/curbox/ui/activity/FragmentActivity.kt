package neth.iecal.curbox.ui.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.fragments.installation.AccessibilityGuide
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.ui.fragments.main.focus.FocusFragment
import neth.iecal.curbox.ui.fragments.main.reducers.ReducersFragment
import neth.iecal.curbox.ui.fragments.main.reducers.analytics.AdvancedAnalyticsFragment
import neth.iecal.curbox.ui.fragments.main.reducers.analytics.IntentsLogFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.CreateGrayscaleGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.GrayscaleFragment
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.CreateAppGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd.AutoDndFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd.CreateAutoDndGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker.ReelBlockerFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordBlockerFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.CreateKeywordGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider.UiHiderFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider.UiHiderEditorFragment
import neth.iecal.curbox.ui.fragments.main.reducers.advanced.AntiUninstallFragment
import neth.iecal.curbox.ui.fragments.main.reducers.advanced.ServiceProtectionFragment
import neth.iecal.curbox.ui.fragments.main.reducers.advanced.SettingsChangeDelayFragment
import androidx.core.view.isVisible
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.utils.BlurFadeAnimator

class FragmentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences("AppPreferences", android.content.Context.MODE_PRIVATE)
        val isFirstLaunchComplete = sharedPreferences.getBoolean("isFirstLaunchComplete", false)
        val selectedFragment = intent.getStringExtra("fragment") ?: if (!isFirstLaunchComplete) OnboardingFragment.FRAGMENT_ID else AllAppsUsageFragment.FRAGMENT_ID

        if (selectedFragment == OnboardingFragment.FRAGMENT_ID) {
            setTheme(R.style.Theme_Curbox_Onboarding)
        }

        super.onCreate(savedInstanceState)

        // Screens for features this flavor does not ship stay unreachable even
        // through shortcuts or external intents.
        val strippedFromFlavor =
            (!neth.iecal.curbox.BuildConfig.SUPPORTS_UI_HIDER &&
                (selectedFragment == UiHiderFragment.FRAGMENT_ID || selectedFragment == UiHiderEditorFragment.FRAGMENT_ID)) ||
            (!neth.iecal.curbox.BuildConfig.SUPPORTS_ANTI_UNINSTALL &&
                selectedFragment == AntiUninstallFragment.FRAGMENT_ID)
        if (strippedFromFlavor) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_fragment)

        maybeShowTermsConsent()
        maybeShowWhatsNew()

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // If bottom navigation is visible, it handles the bottom system bar inset itself.
            // We only apply the bottom padding from system bars if the bottom nav is hidden.
            val bottomPadding = if (bottomNav.isVisible) {
                ime.bottom // Only pad for keyboard if nav is visible
            } else {
                maxOf(systemBars.bottom, ime.bottom)
            }

            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }

        when (selectedFragment) {
            OnboardingFragment.FRAGMENT_ID,
            AccessibilityGuide.FRAGMENT_ID,
            AppBlockerGroupsFragment.FRAGMENT_ID,
            CreateAppGroupFragment.FRAGMENT_ID,
            ReelBlockerFragment.FRAGMENT_ID,
            AutoDndFragment.FRAGMENT_ID,
            CreateAutoDndGroupFragment.FRAGMENT_ID,
            neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID,
            GrayscaleFragment.FRAGMENT_ID,
            CreateGrayscaleGroupFragment.FRAGMENT_ID,
                UiHiderFragment.FRAGMENT_ID,
                UiHiderEditorFragment.FRAGMENT_ID,
                IntentsLogFragment.FRAGMENT_ID,
                AdvancedAnalyticsFragment.FRAGMENT_ID,
            neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID,
            KeywordBlockerFragment.FRAGMENT_ID,
            neth.iecal.curbox.ui.fragments.main.reducers.api.ApiFragment.FRAGMENT_ID,
            AntiUninstallFragment.FRAGMENT_ID,
            ServiceProtectionFragment.FRAGMENT_ID,
            SettingsChangeDelayFragment.FRAGMENT_ID,
            CreateKeywordGroupFragment.FRAGMENT_ID -> {
                // Hide bottom nav for these standalone fragments
                bottomNav.visibility = android.view.View.GONE
                
                val fragment = when (selectedFragment) {
                    OnboardingFragment.FRAGMENT_ID -> OnboardingFragment()
                    AppBlockerGroupsFragment.FRAGMENT_ID -> AppBlockerGroupsFragment()
                    CreateAppGroupFragment.FRAGMENT_ID -> CreateAppGroupFragment()
                    ReelBlockerFragment.FRAGMENT_ID -> ReelBlockerFragment()
                    KeywordBlockerFragment.FRAGMENT_ID -> KeywordBlockerFragment()
                    CreateKeywordGroupFragment.FRAGMENT_ID -> CreateKeywordGroupFragment()
                    UiHiderFragment.FRAGMENT_ID -> UiHiderFragment()
                    UiHiderEditorFragment.FRAGMENT_ID -> UiHiderEditorFragment()
                    AutoDndFragment.FRAGMENT_ID -> AutoDndFragment()
                    CreateAutoDndGroupFragment.FRAGMENT_ID -> CreateAutoDndGroupFragment()
                    neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID -> neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment()
                    GrayscaleFragment.FRAGMENT_ID -> GrayscaleFragment()
                    CreateGrayscaleGroupFragment.FRAGMENT_ID -> CreateGrayscaleGroupFragment()
                    neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID -> neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment()
                    IntentsLogFragment.FRAGMENT_ID -> IntentsLogFragment()
                    AdvancedAnalyticsFragment.FRAGMENT_ID -> AdvancedAnalyticsFragment()
                    neth.iecal.curbox.ui.fragments.main.reducers.api.ApiFragment.FRAGMENT_ID -> neth.iecal.curbox.ui.fragments.main.reducers.api.ApiFragment()
                    // Sync removed
                    AntiUninstallFragment.FRAGMENT_ID -> AntiUninstallFragment()
                    ServiceProtectionFragment.FRAGMENT_ID -> ServiceProtectionFragment()
                    SettingsChangeDelayFragment.FRAGMENT_ID -> SettingsChangeDelayFragment()
                    else -> AccessibilityGuide()
                }
                fragment.arguments = intent.extras

                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_holder, fragment)
                    .commit()
            }
            else -> {
                // Show bottom nav for main fragments
                bottomNav.visibility = android.view.View.VISIBLE

                // The last tab is "Info" on F-Droid. The Play Store build also
                // hosts account and sync here, so it reads "Settings" instead.
                if (!neth.iecal.curbox.BuildConfig.FDROID_VARIANT) {
                    bottomNav.menu.findItem(R.id.nav_info)?.apply {
                        title = getString(R.string.settings)
                        setIcon(R.drawable.baseline_settings_24)
                    }
                }

                neth.iecal.curbox.utils.DonationPrompt.maybeShow(this)

                if (savedInstanceState == null) {
                    bottomNav.selectedItemId = R.id.nav_usage
                    showTab(R.id.nav_usage, commitNow = true)
                }

                bottomNav.setOnItemSelectedListener { item ->
                    if (item.itemId == bottomNav.selectedItemId) return@setOnItemSelectedListener false

                    switchTab(item.itemId)
                    true
                }
            }
        }
    }

    private fun maybeShowTermsConsent() {
        val prefs = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        if (prefs.getBoolean("hasAcceptedTerms", false)) return

        val view = layoutInflater.inflate(R.layout.dialog_terms_consent, null)
        view.findViewById<TextView>(R.id.terms_link).setOnClickListener {
            openUrl(getString(R.string.terms_url))
        }
        view.findViewById<TextView>(R.id.privacy_link).setOnClickListener {
            openUrl(getString(R.string.privacy_url))
        }

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.terms_consent_agree) { _, _ ->
                prefs.edit().putBoolean("hasAcceptedTerms", true).apply()
            }
            .setNegativeButton(R.string.terms_consent_exit) { _, _ ->
                finishAffinity()
            }
            .show()
    }

    // The changelog shown here is the same file fastlane ships to the stores,
    // copied into assets/changelogs/<versionCode>.txt at build time.
    private fun maybeShowWhatsNew() {
        val prefs = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)

        // A user who has not agreed to the terms yet is looking at that dialog.
        // Leave the version unrecorded so the changelog shows on the next launch.
        if (!prefs.getBoolean("hasAcceptedTerms", false)) return

        val currentVersion = neth.iecal.curbox.BuildConfig.VERSION_CODE
        if (prefs.getInt("lastSeenVersionCode", 0) == currentVersion) return
        prefs.edit().putInt("lastSeenVersionCode", currentVersion).apply()

        // Someone still in onboarding just installed the app, so nothing is new to them.
        if (!prefs.getBoolean("isFirstLaunchComplete", false)) return

        val changelog = try {
            assets.open("changelogs/$currentVersion.txt").bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            return
        }
        if (changelog.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.whats_new_title)
            .setMessage(changelog)
            .setPositiveButton(R.string.whats_new_dismiss, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Tags used to keep each bottom nav tab's fragment (and its ViewModel/cached
    // data) alive across switches, instead of recreating it every time. Recreating
    // used to force a full reload (and its loading flash) on every tab revisit.
    private val tabTags = mapOf(
        R.id.nav_usage to "tab_usage",
        R.id.nav_focus to "tab_focus",
        R.id.nav_reducers to "tab_reducers",
        R.id.nav_info to "tab_info",
    )

    private fun createTabFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_usage -> AllAppsUsageFragment()
        R.id.nav_focus -> FocusFragment()
        R.id.nav_reducers -> ReducersFragment()
        R.id.nav_info -> neth.iecal.curbox.ui.fragments.main.InfoFragment()
        else -> AllAppsUsageFragment()
    }

    private fun showTab(itemId: Int, commitNow: Boolean = false) {
        val tag = tabTags[itemId] ?: return
        val fm = supportFragmentManager

          fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val transaction = fm.beginTransaction()

        for ((id, otherTag) in tabTags) {
            if (id != itemId) {
                fm.findFragmentByTag(otherTag)?.let { transaction.hide(it) }
            }
        }

        val target = fm.findFragmentByTag(tag)
        if (target == null) {
            transaction.add(R.id.fragment_holder, createTabFragment(itemId), tag)
        } else {
            transaction.show(target)
        }

        if (commitNow) transaction.commitNow() else transaction.commit()
    }

    private fun switchTab(itemId: Int) {
        val container = findViewById<android.view.View>(R.id.fragment_holder)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlurFadeAnimator.fadeOut(container) {
                showTab(itemId, commitNow = true)
                BlurFadeAnimator.fadeIn(container)
            }
        } else {
            showTab(itemId)
        }
    }
}
