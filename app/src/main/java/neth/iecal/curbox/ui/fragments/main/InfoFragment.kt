package neth.iecal.curbox.ui.fragments.main

import neth.iecal.curbox.R

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.data.sync.SyncGateway
import neth.iecal.curbox.databinding.FragmentInfoBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.sync.SyncFragment
import neth.iecal.curbox.utils.LanguageUtils
import neth.iecal.curbox.utils.DataStoreManager

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!
    private val dataStore by lazy { DataStoreManager(requireContext()) }
    private var renderingTrackingSettings = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAccountSection()
        setupUsageTrackingSettings()
        setupClickListeners()
        LanguageUtils.bindLanguageSelector(binding.languageSelector, binding.textCurrentLanguage)
    }

    private fun setupUsageTrackingSettings() {
        binding.switchAppUsageTracking.setOnCheckedChangeListener { _, checked ->
            if (!renderingTrackingSettings) viewLifecycleOwner.lifecycleScope.launch {
                dataStore.updateAppUsageTrackingEnabled(checked)
            }
        }
        binding.switchWebsiteUsageTracking.setOnCheckedChangeListener { _, checked ->
            if (!renderingTrackingSettings) viewLifecycleOwner.lifecycleScope.launch {
                dataStore.updateWebsiteUsageTrackingEnabled(checked)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settings.collect { settings ->
                    renderingTrackingSettings = true
                    binding.switchAppUsageTracking.isChecked = settings.isAppUsageTrackingEnabled
                    binding.switchWebsiteUsageTracking.isChecked = settings.isWebsiteUsageTrackingEnabled
                    renderingTrackingSettings = false
                }
            }
        }
    }

    // Account and sync live here in the Play Store build only. F-Droid stays
    // offline, so the card never appears and there is no login to be seen. The
    // login flow opens as its own screen so the keyboard has room to breathe.
    private fun setupAccountSection() {
        if (BuildConfig.FDROID_VARIANT) return

        binding.cardAccount.visibility = View.VISIBLE
        binding.btnLogin.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", SyncFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        // The card speaks to where someone is: signed out, mid setup, or fully on.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncGateway.provider.status.collect { s ->
                    val titleRes: Int
                    val pitchRes: Int
                    val buttonRes: Int
                    when {
                        s.unlocked -> {
                            titleRes = R.string.sync_is_on
                            pitchRes = R.string.sync_is_on_pitch
                            buttonRes = R.string.manage_sync
                        }
                        s.signedIn -> {
                            titleRes = R.string.sync_across_devices
                            pitchRes = R.string.sync_finish_setup_pitch
                            buttonRes = R.string.finish_sync_setup
                        }
                        else -> {
                            titleRes = R.string.sync_across_devices
                            pitchRes = R.string.sync_across_devices_pitch
                            buttonRes = R.string.log_in
                        }
                    }
                    binding.textAccountTitle.setText(titleRes)
                    binding.textAccountPitch.setText(pitchRes)
                    binding.btnLogin.setText(buttonRes)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnDocs.setOnClickListener {
            openUrl("https://curbox.app/docs/")
        }

        binding.btnSupport.setOnClickListener {
            openUrl("https://github.com/nethical6")
        }

        binding.btnDonate.setOnClickListener {
            openUrl("https://curbox.app/donate")
        }

        binding.btnShare.setOnClickListener {
            shareProject()
        }

        binding.cardDiscord.setOnClickListener {
            // Replace with actual Discord invite link
            openUrl("https://discord.com/invite/Vs9mwUtuCN")
        }

        binding.cardInstagram.setOnClickListener {
            openUrl("https://instagram.com/curbox.app")
        }

        binding.cardGithub.setOnClickListener {
            openUrl("https://github.com/curbox-app/curbox-android")
        }

        binding.cardBrowserExtension.setOnClickListener {
            openUrl("https://github.com/curbox-app/curbox-extension")
        }

        binding.btnActionCrashLogs.setOnClickListener {
            showCrashLogs()
        }
    }

    private fun shareProject() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_curbox_message))
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showCrashLogs() {
        val logFile = File(requireContext().filesDir, "crash_log.txt")
        val content = if (logFile.exists()) {
            try {
                val text = logFile.readText()
                if (text.isBlank()) "No crash logs available." else text
            } catch (e: Exception) {
                "Error reading crash logs."
            }
        } else {
            "No crash logs available."
        }
        
        val displayContent = if (content.length > 50000) {
            "...${content.takeLast(50000)}"
        } else {
            content
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crash_logs_title)
            .setMessage(displayContent)
            .setPositiveButton(R.string.share) { _, _ ->
                shareCrashLogs(content)
            }
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.clear) { _, _ ->
                if (logFile.exists() && logFile.delete()) {
                    Toast.makeText(requireContext(), getString(R.string.crash_logs_cleared), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareCrashLogs(content: String) {
        if (content == "No crash logs available." || content == "Error reading crash logs.") run {
            Toast.makeText(requireContext(), getString(R.string.nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Curbox Crash Logs")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "Share Crash Logs"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
