package neth.iecal.curbox.ui.fragments.main

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentInfoBinding
import neth.iecal.curbox.ui.fragments.BackupViewModel
import neth.iecal.curbox.utils.CrashLogPreview
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.LanguageUtils

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!
    private val dataStore by lazy { DataStoreManager(requireContext()) }
    private var renderingTrackingSettings = false

    private val backupViewModel: BackupViewModel by viewModels()
    private val backupPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) backupViewModel.export(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.textAppVersion.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        setupAccountSection()
        setupUsageTrackingSettings()
        setupClickListeners()
        setupBackup()
        LanguageUtils.bindLanguageSelector(binding.languageSelector, binding.textCurrentLanguage)
    }

    private fun setupBackup() {
        binding.btnBackup.setOnClickListener {
            backupPicker.launch("curbox_settings_${java.time.LocalDate.now()}.json")
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                backupViewModel.status.collect { status ->
                    binding.btnBackup.isEnabled = status != BackupViewModel.Status.WORKING
                    binding.backupProgress.visibility =
                        if (status == BackupViewModel.Status.WORKING) View.VISIBLE else View.GONE
                    val message = when (status) {
                        BackupViewModel.Status.SUCCEEDED -> R.string.backup_saved
                        BackupViewModel.Status.FAILED -> R.string.backup_save_failed
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

    // Sync removed; keep account section hidden.
    private fun setupAccountSection() {
        binding.cardAccount.visibility = View.GONE
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
        viewLifecycleOwner.lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) {
                runCatching { if (logFile.exists()) CrashLogPreview.read(logFile) else null }
            }
            val content = preview.fold(
                onSuccess = { it ?: getString(R.string.crash_logs_none_available) },
                onFailure = { getString(R.string.crash_logs_read_error) }
            )

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.crash_logs_title)
                .setMessage(content)
                .setPositiveButton(R.string.share) { _, _ ->
                    shareCrashLogs(logFile)
                }
                .setNegativeButton(R.string.close, null)
                .setNeutralButton(R.string.clear) { _, _ ->
                    if (logFile.exists() && logFile.delete()) {
                        Toast.makeText(requireContext(), getString(R.string.crash_logs_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun shareCrashLogs(logFile: File) {
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(requireContext(), getString(R.string.nothing_to_share), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val context = requireContext()
            val logUri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.provider",
                logFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Curbox Crash Logs")
                putExtra(Intent.EXTRA_STREAM, logUri)
                clipData = ClipData.newUri(context.contentResolver, "Curbox Crash Logs", logUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Crash Logs"))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.unable_to_share_crash_logs, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
