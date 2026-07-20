package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.content.Context
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.BarcodeFormat
import com.google.gson.Gson
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.databinding.FragmentWarningConfigBinding
import java.util.UUID

class WarningConfigFragment : Fragment() {

    private var _binding: FragmentWarningConfigBinding? = null
    private val binding get() = _binding!!

    private var initialConfig: AppBlockerWarningScreenConfig? = null
    private var currentQrMap = mutableMapOf<String, Long>()
    private var pendingQrDuration = -1L

    data class UnlockOption(
        val title: String,
        val subtext: String,
        val isRecommended: Boolean = false
    ) {
        override fun toString(): String = title
    }

    private val challengeOptions = listOf(
        UnlockOption("Never Unlock", "Total lockdown. No bypassing allowed."),
        UnlockOption("Require effort to unlock", "In behavioral psychology, adding physical friction breaks the automatic habit loop, giving your brain a necessary pause to reconsider.", true),
        UnlockOption("Wait to unlock", "Allows immediate access after a short wait.")
    )

    private val effortOptions = listOf(
        UnlockOption("Unlock requires QR/Barcode scanning", "Scan any code (like a product box) to unlock.", true),
        UnlockOption("Unlock requires typing a sentence", "Precisely type a long sentence to prove focus."),
        UnlockOption("Unlock requires stating an intent", "Briefly describe your goal before accessing.")
    )

    private val noEffortOptions = listOf(
        UnlockOption("Ask me how much time I need", "You choose the duration during each unlock."),
        UnlockOption("Only give me a fixed amount of time", "Automatically locks after a set duration.", true)
    )

    private class UnlockOptionAdapter(context: Context, options: List<UnlockOption>) :
        ArrayAdapter<UnlockOption>(context, R.layout.item_dropdown_with_subtext, options) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_dropdown_with_subtext, parent, false)
            
            val optionTitle = view.findViewById<TextView>(R.id.option_title)
            val optionSubtext = view.findViewById<TextView>(R.id.option_subtext)
            val recommendedBadge = view.findViewById<View>(R.id.recommended_badge)

            getItem(position)?.let { option ->
                optionTitle.text = option.title
                optionSubtext.text = option.subtext
                recommendedBadge.isVisible = option.isRecommended
            }
            
            return view
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return getView(position, convertView, parent)
        }
    }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val duration = pendingQrDuration
            currentQrMap[result.contents] = duration
            updateQrList()
            Toast.makeText(requireContext(), R.string.warning_qr_saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.warning_scan_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configStr = arguments?.getString(ARG_CONFIG)
        initialConfig = if (configStr != null) {
            Gson().fromJson(configStr, AppBlockerWarningScreenConfig::class.java)
        } else null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWarningConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInitialState()
        setupListeners()
    }

    private fun setupInitialState() {
        val config = initialConfig ?: AppBlockerWarningScreenConfig()
        val isOnOpen = arguments?.getBoolean(ARG_IS_ON_OPEN) == true
        config.isOnOpenConfig = isOnOpen
        
        val isNew = arguments?.getBoolean(ARG_IS_NEW) ?: (arguments?.getString(ARG_CONFIG) == null)
        
        currentQrMap = config.qrKeys.toMutableMap()
        updateQrList()

        val challengeAdapter = UnlockOptionAdapter(requireContext(), challengeOptions)
        binding.unlockChallengeDropdown.setAdapter(challengeAdapter)

        if (!isNew) {
            var (challengeIdx, secondaryIdx) = when {
                config.isProceedDisabled -> 0 to -1
                config.isQrUnlockRequirementEnabled -> 1 to 0
                config.isTypingRequirementEnabled -> 1 to 1
                config.isIntentRequirementEnabled -> 1 to 2
                config.isDynamicIntervalSettingAllowed -> 2 to 0
                else -> 2 to 1 // Fixed time
            }

            if (isOnOpen && challengeIdx == 2) {
                secondaryIdx = 1
            }

            binding.unlockChallengeDropdown.setText(challengeOptions[challengeIdx].title, false)
            updateSecondaryDropdown(challengeIdx)
            
            if (secondaryIdx != -1) {
                val options = if (challengeIdx == 1) effortOptions else noEffortOptions
                binding.secondaryBehaviorDropdown.setText(options[secondaryIdx].title, false)
            }
            updateUiVisibility(challengeIdx, secondaryIdx, animate = isOnOpen)
        } else {
            binding.secondaryBehaviorLayout.isVisible = false
            binding.timingContainer.isVisible = false
            binding.proceedDelayContainer.isVisible = false
            binding.qrSetupContainer.isVisible = false
            binding.typingSetupContainer.isVisible = false
        }
        
        binding.typingSentenceEdit.setText(config.typingSentence)

        binding.intentMinLengthSlider.value = config.minIntentLength.toFloat().coerceIn(1f, 100f)
        binding.intentMinLengthTitle.text = resources.getQuantityString(
            R.plurals.warning_intent_min_length,
            binding.intentMinLengthSlider.value.toInt(),
            binding.intentMinLengthSlider.value.toInt()
        )

        binding.fixedTimeSlider.value = (config.timeInterval / 60000).toFloat().coerceIn(1f, 120f)
        binding.timingTitle.text = getString(R.string.warning_fixed_unlock_duration, binding.fixedTimeSlider.value.toInt())

        binding.proceedDelaySlider.value = config.proceedDelayInSecs.toFloat().coerceIn(0f, 60f)
        binding.proceedDelayTitle.text = getString(R.string.warning_wait_before_unlock, binding.proceedDelaySlider.value.toInt())

        binding.proceedLimitSwitch.isChecked = config.proceedLimitEnabled
        binding.proceedLimitContainer.visibility = if (config.proceedLimitEnabled) View.VISIBLE else View.GONE

        binding.allowedProceedsSlider.value = config.allowedProceeds.toFloat().coerceIn(1f, 20f)
        binding.allowedProceedsTitle.text = getString(R.string.warning_allowed_proceeds, binding.allowedProceedsSlider.value.toInt())

        val unitOptions = listOf(
            getString(R.string.unit_minutes),
            getString(R.string.unit_hours),
            getString(R.string.unit_days)
        )

        val totalMins = config.proceedsTimeWindowMn
        val (initialUnitIdx, initialSliderValue) = when {
            totalMins > 0 && totalMins % 1440 == 0 -> 2 to (totalMins / 1440).toFloat().coerceIn(1f, 30f)
            totalMins > 0 && totalMins % 60 == 0 -> 1 to (totalMins / 60).toFloat().coerceIn(1f, 24f)
            else -> 0 to totalMins.toFloat().coerceIn(1f, 60f)
        }

        binding.proceedWindowUnitBtn.text = unitOptions[initialUnitIdx]
        updateProceedWindowSliderBounds(initialUnitIdx)
        binding.proceedWindowSlider.value = initialSliderValue
        updateProceedWindowTitle(initialUnitIdx, initialSliderValue.toInt())

        binding.warningMsgEdit.setText(config.message)
        binding.switchVibrateBrightness.isChecked = config.vibrateAndIncBrightness

        if (isOnOpen) {
            binding.timingContainer.visibility = View.GONE
        }
    }

    private fun updateSecondaryDropdown(challengeIdx: Int) {
        val isOnOpen = arguments?.getBoolean(ARG_IS_ON_OPEN) == true
        
        if (isOnOpen && challengeIdx == 2) {
            binding.secondaryBehaviorLayout.visibility = View.GONE
            binding.secondaryBehaviorDropdown.setText(noEffortOptions[1].title, false)
            return
        }

        val options = when (challengeIdx) {
            1 -> effortOptions
            2 -> noEffortOptions
            else -> null
        }

        if (options != null) {
            val adapter = UnlockOptionAdapter(requireContext(), options)
            binding.secondaryBehaviorDropdown.setAdapter(adapter)
            binding.secondaryBehaviorLayout.visibility = View.VISIBLE
        } else {
            binding.secondaryBehaviorLayout.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.unlockChallengeDropdown.setOnItemClickListener { _, _, position, _ ->
            updateSecondaryDropdown(position)
            
            val isOnOpen = arguments?.getBoolean(ARG_IS_ON_OPEN) == true
            // Clear secondary dropdown when parent changes
            if (!(isOnOpen && position == 2)) {
                binding.secondaryBehaviorDropdown.setText("", false)
            }
            
            val secondaryIdx = if (isOnOpen && position == 2) 1 else -1
            updateUiVisibility(position, secondaryIdx, animate = true)
        }

        binding.secondaryBehaviorDropdown.setOnItemClickListener { _, _, position, _ ->
            val challengeTitle = binding.unlockChallengeDropdown.text.toString()
            val challengeIdx = challengeOptions.indexOfFirst { it.title == challengeTitle }
            updateUiVisibility(challengeIdx, position, animate = true)
        }

        binding.fixedTimeSlider.addOnChangeListener { _, value, _ ->
            binding.timingTitle.text = getString(R.string.warning_fixed_unlock_duration, value.toInt())
        }

        binding.proceedDelaySlider.addOnChangeListener { _, value, _ ->
            binding.proceedDelayTitle.text = getString(R.string.warning_wait_before_unlock, value.toInt())
        }

        binding.proceedLimitSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.proceedLimitContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.allowedProceedsSlider.addOnChangeListener { _, value, _ ->
            binding.allowedProceedsTitle.text = getString(R.string.warning_allowed_proceeds, value.toInt())
        }

        binding.proceedWindowSlider.addOnChangeListener { _, value, _ ->
            val unitStr = binding.proceedWindowUnitBtn.text.toString()
            val unitOptions = listOf(
                getString(R.string.unit_minutes),
                getString(R.string.unit_hours),
                getString(R.string.unit_days)
            )
            val unitIdx = unitOptions.indexOf(unitStr).coerceAtLeast(0)
            updateProceedWindowTitle(unitIdx, value.toInt())
        }

        binding.proceedWindowUnitBtn.setOnClickListener { btn ->
            val unitOptions = listOf(
                getString(R.string.unit_minutes),
                getString(R.string.unit_hours),
                getString(R.string.unit_days)
            )
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), btn)
            unitOptions.forEachIndexed { index, option ->
                popup.menu.add(0, index, index, option)
            }
            popup.setOnMenuItemClickListener { menuItem ->
                val selectedUnitIdx = menuItem.itemId
                binding.proceedWindowUnitBtn.text = menuItem.title
                updateProceedWindowSliderBounds(selectedUnitIdx)
                val currentVal = binding.proceedWindowSlider.value
                updateProceedWindowTitle(selectedUnitIdx, currentVal.toInt())
                true
            }
            popup.show()
        }

        binding.intentMinLengthSlider.addOnChangeListener { _, value, _ ->
            binding.intentMinLengthTitle.text = resources.getQuantityString(
                R.plurals.warning_intent_min_length,
                value.toInt(),
                value.toInt()
            )
        }

        binding.advancedSettingsHeader.setOnClickListener {
            val isCurrentlyVisible = binding.advancedSettingsContent.isVisible
            TransitionManager.beginDelayedTransition(binding.mainContentContainer, AutoTransition())
            binding.advancedSettingsContent.isVisible = !isCurrentlyVisible
            binding.advancedSettingsArrow.animate().rotation(if (isCurrentlyVisible) 0f else 90f).start()
        }

        binding.btnGenerateQr.setOnClickListener {
            showQrConfigDialog { duration ->
                val uniqueStr = UUID.randomUUID().toString()
                currentQrMap[uniqueStr] = duration
                updateQrList()
                
                try {
                    val barcodeEncoder = BarcodeEncoder()
                    val bitmap = barcodeEncoder.encodeBitmap(uniqueStr, BarcodeFormat.QR_CODE, 800, 800)
                    val imageView = ImageView(requireContext()).apply {
                        setImageBitmap(bitmap)
                        setPadding(32, 32, 32, 32)
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.warning_qr_generated_title)
                        .setMessage(R.string.warning_qr_generated_message)
                        .setView(imageView)
                        .setPositiveButton(R.string.done, null)
                        .setNeutralButton(R.string.warning_save_to_gallery) { _, _ ->
                            saveImageToGallery(bitmap)
                        }
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), R.string.warning_qr_generate_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnScanExistingQr.setOnClickListener {
            showQrConfigDialog { duration ->
                pendingQrDuration = duration
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                options.setPrompt("Scan a QR code or barcode to unlock the blocker later. You can use almost any code, even one from a product box at home, so there’s no need to print a new one!")
                options.setCameraId(0)
                options.setBeepEnabled(false)
                options.setBarcodeImageEnabled(true)
                options.setCaptureActivity(neth.iecal.curbox.ui.activity.PortraitCaptureActivity::class.java)
                barcodeLauncher.launch(options)
            }
        }
        
        binding.saveconfigs.setOnClickListener {
            val challengeStr = binding.unlockChallengeDropdown.text.toString()
            val secondaryStr = binding.secondaryBehaviorDropdown.text.toString()
            
            val cIdx = challengeOptions.indexOfFirst { it.title == challengeStr }
            
            var isProceedDisabled = false
            var isQrUnlockRequirementEnabled = false
            var isTypingRequirementEnabled = false
            var isIntentRequirementEnabled = false
            var isDynamicIntervalSettingAllowed = false

            when (cIdx) {
                0 -> isProceedDisabled = true
                1 -> {
                    val sIdx = effortOptions.indexOfFirst { it.title == secondaryStr }
                    when (sIdx) {
                        0 -> isQrUnlockRequirementEnabled = true
                        1 -> isTypingRequirementEnabled = true
                        2 -> isIntentRequirementEnabled = true
                    }
                }
                2 -> {
                    val sIdx = noEffortOptions.indexOfFirst { it.title == secondaryStr }
                    if (sIdx == 0) isDynamicIntervalSettingAllowed = true
                }
            }

            val config = AppBlockerWarningScreenConfig(
                message = binding.warningMsgEdit.text.toString(),
                timeInterval = (binding.fixedTimeSlider.value.toInt()) * 60_000,
                isDynamicIntervalSettingAllowed = isDynamicIntervalSettingAllowed,
                isProceedDisabled = isProceedDisabled,
                isWarningDialogHidden = false,
                isQrUnlockRequirementEnabled = isQrUnlockRequirementEnabled,
                qrKeys = if (isQrUnlockRequirementEnabled) currentQrMap else mapOf(),
                isTypingRequirementEnabled = isTypingRequirementEnabled,
                typingSentence = binding.typingSentenceEdit.text.toString(),
                isIntentRequirementEnabled = isIntentRequirementEnabled,
                minIntentLength = binding.intentMinLengthSlider.value.toInt(),
                proceedDelayInSecs = binding.proceedDelaySlider.value.toInt(),
                vibrateAndIncBrightness = binding.switchVibrateBrightness.isChecked,
                proceedLimitEnabled = binding.proceedLimitSwitch.isChecked,
                allowedProceeds = binding.allowedProceedsSlider.value.toInt(),
                proceedsTimeWindowMn = run {
                    val unitStr = binding.proceedWindowUnitBtn.text.toString()
                    val unitOptions = listOf(
                        getString(R.string.unit_minutes),
                        getString(R.string.unit_hours),
                        getString(R.string.unit_days)
                    )
                    val unitIdx = unitOptions.indexOf(unitStr).coerceAtLeast(0)
                    val sliderVal = binding.proceedWindowSlider.value.toInt()
                    when (unitIdx) {
                        1 -> sliderVal * 60
                        2 -> sliderVal * 1440
                        else -> sliderVal
                    }
                },
                isOnOpenConfig = arguments?.getBoolean(ARG_IS_ON_OPEN) == true
            )
            
            val requestKey = arguments?.getString(ARG_REQUEST_KEY) ?: RESULT_KEY
            parentFragmentManager.setFragmentResult(requestKey, Bundle().apply {
                putString(RESULT_CONFIG, Gson().toJson(config))
            })
            parentFragmentManager.popBackStack()
        }
    }

    private fun saveImageToGallery(bitmap: android.graphics.Bitmap) {
        val context = requireContext()
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Unlock_QR_${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Curbox")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                Toast.makeText(context, R.string.warning_saved_to_gallery, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.warning_save_image_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, R.string.warning_mediastore_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUiVisibility(challengeIndex: Int, secondaryIndex: Int, animate: Boolean = false) {
        if (animate) {
            TransitionManager.beginDelayedTransition(
                binding.mainContentContainer,
                AutoTransition()
            )
        }

        val isOnOpen = arguments?.getBoolean(ARG_IS_ON_OPEN) == true

        binding.apply {
            // Timing container visible if "Requires no effort" + "Fixed time" OR "Require effort" + (Typing or Intent)
            timingContainer.visibility = if (!isOnOpen && ((challengeIndex == 2 && secondaryIndex == 1) || (challengeIndex == 1 && secondaryIndex != 0 && secondaryIndex != -1))) View.VISIBLE else View.GONE
            
            // Proceed delay visible for anything except "Never Unlock" or when nothing selected
            proceedDelayContainer.visibility = if (challengeIndex != 0 && challengeIndex != -1) View.VISIBLE else View.GONE
            
            qrSetupContainer.visibility = if (challengeIndex == 1 && secondaryIndex == 0) View.VISIBLE else View.GONE
            typingSetupContainer.visibility = if (challengeIndex == 1 && secondaryIndex == 1) View.VISIBLE else View.GONE
            intentSetupContainer.visibility = if (challengeIndex == 1 && secondaryIndex == 2) View.VISIBLE else View.GONE
        }
    }
    
    private fun showQrConfigDialog(onConfigured: (Long) -> Unit) {
        if (initialConfig?.isOnOpenConfig == true) {
            onConfigured(-1L) // Default to dynamic/manual duration, but WarningActivity will override it anyway
            return
        }
        val pickerContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        
        val switchDynamic = com.google.android.material.switchmaterial.SwitchMaterial(requireContext()).apply {
            text = "Use dynamic timing (User selects time during unlock)"
            isChecked = true
        }

        val pickerInnerContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
        
        val timeLabel = TextView(requireContext()).apply {
            text = "Fixed unlock duration: 5 mins"
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        val slider = com.google.android.material.slider.Slider(requireContext()).apply {
            valueFrom = 1f
            valueTo = 120f
            stepSize = 1f
            value = 5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addOnChangeListener { _, value, _ ->
                timeLabel.text = getString(R.string.warning_fixed_unlock_duration_lower, value.toInt())
            }
        }

        pickerInnerContainer.addView(timeLabel)
        pickerInnerContainer.addView(slider)

        switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            pickerInnerContainer.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        pickerContainer.addView(switchDynamic)
        pickerContainer.addView(pickerInnerContainer)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warning_qr_timing_title)
            .setMessage(R.string.warning_qr_timing_message)
            .setView(pickerContainer)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                val duration = if (switchDynamic.isChecked) -1L else slider.value.toLong() * 60_000L
                onConfigured(duration)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateQrList() {
        binding.qrListContainer.removeAllViews()
        if (!currentQrMap.isEmpty()) {
            currentQrMap.forEach { (uuid, duration) ->
                val itemView = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 16, 0, 16)
                    weightSum = 1f
                }
                
                val infoText = TextView(requireContext()).apply {
                    val durationText = if (duration == -1L) "Dynamic time" else "${duration / 60000} mins"
                    text = "QR/Barcode - $durationText"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(4, 4, 4, 4)
                }
                
                val removeBtn = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Remove"
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        currentQrMap.remove(uuid)
                        updateQrList()
                    }
                }
                
                itemView.addView(infoText)
                itemView.addView(removeBtn)
                binding.qrListContainer.addView(itemView)
            }
        }
    }

    private fun updateProceedWindowSliderBounds(unitIdx: Int) {
        val (minVal, maxVal) = when (unitIdx) {
            1 -> 1f to 24f  // Hours
            2 -> 1f to 30f  // Days
            else -> 1f to 60f // Minutes
        }
        val currentVal = binding.proceedWindowSlider.value
        val newVal = currentVal.coerceIn(minVal, maxVal)
        if (binding.proceedWindowSlider.value < minVal || binding.proceedWindowSlider.value > maxVal) {
            binding.proceedWindowSlider.value = minVal
        }
        binding.proceedWindowSlider.valueFrom = minVal
        binding.proceedWindowSlider.valueTo = maxVal
        binding.proceedWindowSlider.value = newVal
    }

    private fun updateProceedWindowTitle(unitIdx: Int, value: Int) {
        val unitOptions = listOf(
            getString(R.string.unit_minutes),
            getString(R.string.unit_hours),
            getString(R.string.unit_days)
        )
        binding.proceedWindowTitle.text = getString(R.string.warning_time_window, value, unitOptions[unitIdx])
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "warning_config_fragment"
        const val ARG_CONFIG = "arg_config"
        const val ARG_REQUEST_KEY = "arg_request_key"
        const val ARG_IS_NEW = "arg_is_new"
        const val ARG_IS_ON_OPEN = "arg_is_on_open"
        const val RESULT_KEY = "request_key_warning_config"
        const val RESULT_CONFIG = "result_config"


        fun newInstance(config: AppBlockerWarningScreenConfig, requestKey: String = RESULT_KEY, isNew: Boolean = false,isOnOpen: Boolean = false): WarningConfigFragment {
            return WarningConfigFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONFIG, Gson().toJson(config))
                    putString(ARG_REQUEST_KEY, requestKey)
                    putBoolean(ARG_IS_NEW, isNew)
                    putBoolean(ARG_IS_ON_OPEN, isOnOpen)

                }
            }
        }
    }
}
